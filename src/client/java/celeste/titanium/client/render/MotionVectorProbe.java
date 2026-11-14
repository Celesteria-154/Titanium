package celeste.titanium.client.render;

import celeste.titanium.client.TitaniumClient;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL33C;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 共有MV生成器のRG32F出力を読み戻し、履歴リセット・静止・カメラ移動時の値を検証する。 */
final class MotionVectorProbe implements AutoCloseable {
    private static final List<String> CASES = List.of("reset", "static", "yaw", "pitch", "forward", "strafe");
    private final CameraMotionVectorProducer producer;
    private GpuBuffer motionReadback;
    private GpuBuffer depthReadback;
    private int readFramebuffer;
    private int width;
    private int height;
    private boolean readbackReady;
    private boolean pending;
    private String pendingCase;
    private FrameInputPacket pendingPrevious;
    private FrameInputPacket pendingCurrent;
    private final Map<String, CaseResult> results = new LinkedHashMap<>();
    private String requestedCase;

    MotionVectorProbe(CameraMotionVectorProducer producer) {
        this.producer = producer;
    }

    /**
     * 履歴開始または連続入力に応じたMV診断ケースを選び、生成済みのMVと深度の読み戻しを予約する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param previous 参照する直前の確定済み入力パケット
     * @param current 解析対象の現在フレーム
     */
    void capture(FrameInputPacket previous, FrameInputPacket current) {
        RenderSystem.assertOnRenderThread();
        if (pending || finished()) return;
        boolean continuous = previous != null && current.continuousWith(previous);
        String captureCase = !continuous && !caseFinished("reset") ? "reset"
                : continuous && !caseFinished("static") && !current.cameraChangedFrom(previous) ? "static"
                : continuous && requestedCase != null && current.cameraChangedFrom(previous) ? requestedCase : null;
        if (captureCase == null) return;
        if (captureCase.equals(requestedCase)) requestedCase = null;
        try {
            if ("reset".equals(captureCase)) {
                submitReset(current);
            } else {
                submitPair(previous, current, captureCase);
            }
        } catch (RuntimeException error) {
            pending = false;
            readbackReady = false;
            completeCase(captureCase, false, error.getClass().getSimpleName() + ": " + error.getMessage());
            TitaniumClient.LOGGER.error("[Titanium/P6B] {} capture failed; Bilinear remains active",
                    captureCase, error);
        }
    }

    /**
     * 転送済みの診断結果を完了通知後に取得し、判定と診断状態を更新する。
     *
     * 描画スレッドから呼び出す。
     */
    void poll() {
        RenderSystem.assertOnRenderThread();
        if (!pending || !readbackReady) return;
        try (var motion = motionReadback.map(true, false)) {
            MotionVectorStatistics statistics;
            // 履歴開始時は0のMVを確認し、連続フレームは同じ深度から求めたCPU参照値と比較する。
            if ("reset".equals(pendingCase)) {
                statistics = MotionVectorStatistics.analyzeReset(motion.data(), width, height);
            } else {
                try (var depth = depthReadback.map(true, false)) {
                    statistics = MotionVectorStatistics.analyze(motion.data(), depth.data(),
                            pendingPrevious, pendingCurrent, producer.clearEndpoint());
                }
            }
            Path directory = Path.of("screenshots").toAbsolutePath();
            Files.createDirectories(directory);
            Path imagePath = directory.resolve("titanium-p6b-motion-" + pendingCase + ".png");
            if (!ImageIO.write(statistics.vectorImage(), "png", imagePath.toFile())) {
                throw new IOException("PNG writer unavailable");
            }
            String result = statistics.summary() + ";image=" + imagePath;
            boolean passed = statistics.passed();
            completeCase(pendingCase, passed, result);
            if (passed) {
                TitaniumClient.LOGGER.info("[Titanium/P6B] {} PASS; {}", pendingCase, result);
            } else {
                TitaniumClient.LOGGER.error("[Titanium/P6B] {} FAIL; {}", pendingCase, result);
            }
        } catch (RuntimeException | IOException error) {
            completeCase(pendingCase, false, error.getClass().getSimpleName() + ": " + error.getMessage());
            TitaniumClient.LOGGER.error("[Titanium/P6B] {} analysis failed", pendingCase, error);
        } finally {
            pending = false;
            readbackReady = false;
            pendingCase = null;
            pendingPrevious = null;
            pendingCurrent = null;
        }
    }

    /**
     * 履歴開始フレームのMVが0であるか確認する読み戻しを予約する。
     *
     * @param current 解析対象の現在フレーム
     */
    private void submitReset(FrameInputPacket current) {
        ensureResources(current.width(), current.height());
        pendingCase = "reset";
        pendingCurrent = current;
        submitReadback(current.sequence(), false);
    }

    /**
     * 連続する入力ペアとケース名を保持し、MVと対応する深度の読み戻しを予約する。
     *
     * @param previous 参照する直前の確定済み入力パケット
     * @param current 解析対象の現在フレーム
     * @param name 対象または診断区間を識別する名前
     */
    private void submitPair(FrameInputPacket previous, FrameInputPacket current, String name) {
        if (producer.clearEndpoint() == DepthStatistics.Endpoint.NONE) {
            completeCase(name, false, "clear endpoint is not configured");
            return;
        }
        ensureResources(current.width(), current.height());
        pendingCase = name;
        pendingPrevious = previous;
        pendingCurrent = current;
        submitReadback(current.sequence(), true);
    }

    /**
     * 入力オブジェクトと寸法を確認し、必要な場合だけ描画・読み戻し用リソースを再生成する。
     *
     * @param requestedWidth 要求された入力幅。ピクセル単位
     * @param requestedHeight 要求された入力高さ。ピクセル単位
     */
    private void ensureResources(int requestedWidth, int requestedHeight) {
        if (requestedWidth == width && requestedHeight == height && motionReadback != null) return;
        releaseResources();
        width = requestedWidth;
        height = requestedHeight;
        int samples = Math.multiplyExact(width, height);
        var device = RenderSystem.getDevice();
        motionReadback = device.createBuffer(() -> "Titanium P6B motion readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                Math.multiplyExact((long) samples, 2L * Float.BYTES));
        depthReadback = device.createBuffer(() -> "Titanium P6B depth readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                Math.multiplyExact((long) samples, Float.BYTES));
        readFramebuffer = GlStateManager.glGenFramebuffers();
    }

    /**
     * 同一番号のMVと必要なら深度をバッファへ転送し、フェンス完了後に解析可能な状態へする。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param includeDepth MVと対になる深度も読み戻す場合はtrue
     */
    private void submitReadback(long sequence, boolean includeDepth) {
        GpuTexture producedMotion = producer.motionTexture(sequence);
        GpuTexture producedDepth = producer.depthInput(sequence);
        if (!(producedMotion instanceof GlTexture motion) || !(motionReadback instanceof GlBuffer motionBuffer)
                || includeDepth && (!(producedDepth instanceof GlTexture) || !(depthReadback instanceof GlBuffer))) {
            throw new IllegalStateException("P6B requires OpenGL resources");
        }
        int previousFramebuffer = GlStateManager.getFrameBuffer(GL33C.GL_READ_FRAMEBUFFER);
        int previousPackBuffer = GL33C.glGetInteger(GL33C.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousRowLength = GL33C.glGetInteger(GL33C.GL_PACK_ROW_LENGTH);
        GlStateManager.clearGlErrors();
        try {
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, motion.glId(), 0);
            if (includeDepth) {
                GlTexture depth = (GlTexture) producedDepth;
                GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT,
                        GL33C.GL_TEXTURE_2D, depth.glId(), 0);
            }
            if (GL33C.glCheckFramebufferStatus(GL33C.GL_READ_FRAMEBUFFER) != GL33C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("P6B read framebuffer incomplete");
            }
            GlStateManager._pixelStore(GL33C.GL_PACK_ROW_LENGTH, width);
            GL33C.glReadBuffer(GL33C.GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, motionBuffer.handle());
            GlStateManager._readPixels(0, 0, width, height, GL33C.GL_RG, GL33C.GL_FLOAT, 0L);
            if (includeDepth) {
                GlBuffer depthBuffer = (GlBuffer) depthReadback;
                GlStateManager._glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, depthBuffer.handle());
                GlStateManager._readPixels(0, 0, width, height,
                        GL33C.GL_DEPTH_COMPONENT, GL33C.GL_FLOAT, 0L);
            }
            pending = true;
            // 読み戻し完了をフェンスで通知し、待機を追加せず後続のpollで解析する。
            RenderSystem.queueFencedTask(() -> readbackReady = true);
        } finally {
            GlStateManager._pixelStore(GL33C.GL_PACK_ROW_LENGTH, previousRowLength);
            GlStateManager._glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT,
                    GL33C.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, previousFramebuffer);
        }
        int error = GlStateManager._getError();
        if (error != GL33C.GL_NO_ERROR) {
            throw new IllegalStateException("P6B readback failed: GL error 0x" + Integer.toHexString(error));
        }
    }

    private void completeCase(String name, boolean passed, String diagnostics) {
        if (!CASES.contains(name)) throw new IllegalArgumentException("Unknown P6B case: " + name);
        results.put(name, new CaseResult(passed, diagnostics));
    }

    /**
     * 入力生成の失敗を記録し、継続中の診断と関連リソースを終了する。
     *
     * @param error 失敗した操作の結果または例外
     */
    void failProducer(RuntimeException error) {
        String diagnostics = error.getClass().getSimpleName() + ": " + error.getMessage();
        for (String name : CASES) {
            results.putIfAbsent(name, new CaseResult(false, "producer failed: " + diagnostics));
        }
        requestedCase = null;
        pending = false;
        readbackReady = false;
        pendingCase = null;
        pendingPrevious = null;
        pendingCurrent = null;
    }

    /**
     * 次の適切な入力ペアで実行する診断ケースを指定する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param name 対象または診断区間を識別する名前
     */
    void armCase(String name) {
        RenderSystem.assertOnRenderThread();
        if (!CASES.subList(2, CASES.size()).contains(name)) {
            throw new IllegalArgumentException("Not a controlled P6B case: " + name);
        }
        if (pending || requestedCase != null) throw new IllegalStateException("P6B capture is already armed");
        if (caseFinished(name)) throw new IllegalStateException("P6B case already finished: " + name);
        requestedCase = name;
    }

    boolean caseFinished(String name) {
        return results.containsKey(name);
    }

    boolean finished() {
        return CASES.stream().allMatch(results::containsKey);
    }

    boolean passed() {
        return finished() && CASES.stream().allMatch(name -> results.get(name).passed());
    }

    /**
     * 現在の実行状態、進行カウンター、直近の失敗理由を診断文字列へまとめる。
     *
     * @return 取得済みの実行状態、カウンターと直近の失敗理由を含む診断文字列
     */
    String diagnostics() {
        StringBuilder diagnostics = new StringBuilder();
        for (String name : CASES) {
            if (!diagnostics.isEmpty()) diagnostics.append(';');
            CaseResult result = results.get(name);
            diagnostics.append(name).append("={")
                    .append(result == null ? requestedCase != null && requestedCase.equals(name) ? "armed" : "pending"
                            : result.diagnostics())
                    .append('}');
        }
        return diagnostics.toString();
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        releaseResources();
    }

    /**
     * 再生成または終了に備え、所有するテクスチャ・ビュー・バッファを解放する。
     */
    private void releaseResources() {
        if (readFramebuffer != 0) GlStateManager._glDeleteFramebuffers(readFramebuffer);
        if (motionReadback != null) motionReadback.close();
        if (depthReadback != null) depthReadback.close();
        readFramebuffer = 0;
        motionReadback = null;
        depthReadback = null;
        width = 0;
        height = 0;
        pending = false;
        readbackReady = false;
    }

    private record CaseResult(boolean passed, String diagnostics) {
    }
}
