package celeste.titanium.client.render;

import celeste.titanium.client.TitaniumClient;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL33C;

/** 同一シーケンスのCPUメタデータとGPUのカラー・深度を照合する。深度復元にも読み戻しを共有する。 */
public final class FrameInputProbe implements AutoCloseable {
    private static final int MAX_EMPTY_ATTEMPTS = 120;
    private final DepthReconstructionProbe depthReconstructionProbe =
            Boolean.getBoolean("titanium.depthReconstructionProbe") ? new DepthReconstructionProbe() : null;
    private final FrameInputRuntime runtime;
    private final MotionVectorProbe motionVectorProbe;
    private final FrameInteropProbe frameInteropProbe =
            Boolean.getBoolean("titanium.frameInteropProbe") ? new FrameInteropProbe() : null;
    private GpuTexture colorSnapshot;
    private GpuTexture depthSnapshot;
    private GpuBuffer colorReadback;
    private GpuBuffer depthReadback;
    private int readFramebuffer;
    private int snapshotWidth;
    private int snapshotHeight;
    private long snapshotSequence;
    private FrameInputPacket snapshotPacket;
    private int snapshotAttempts;
    private volatile boolean snapshotReady;
    private boolean snapshotSubmitted;
    private boolean gpuFinished;
    private boolean gpuPassed;
    private String gpuDiagnostics = "GPU snapshot pending";
    private String diagnostics = "waiting for world projection";

    public FrameInputProbe() {
        boolean motionProbeEnabled = Boolean.getBoolean("titanium.motionVectorProbe");
        CameraMotionVectorProducer motionProducer = motionProbeEnabled || frameInteropProbe != null
                ? CameraMotionVectorProducer.configuredProbe() : null;
        runtime = new FrameInputRuntime(motionProducer);
        motionVectorProbe = motionProbeEnabled ? new MotionVectorProbe(motionProducer) : null;
    }

    public void captureProjection(Matrix4fc unjitteredProjection, Matrix4fc renderProjection, FrameJitter jitter) {
        runtime.captureProjection(unjitteredProjection, renderProjection, jitter);
        diagnostics = runtime.diagnostics();
    }

    public void captureWorldInputs(Matrix4fc modelView, CameraRenderState camera, RenderTarget target,
                                   String dimension) {
        runtime.captureWorldInputs(modelView, camera, target, dimension);
        diagnostics = runtime.diagnostics();
    }

    /**
     * 同一フレームの入力を確定し、必要な診断スナップショット・MV・NGX評価を進める。
     *
     * 描画スレッドから呼び出す。
     *
     * @param target 操作対象の描画先またはリソース
     * @param fieldOfView この入力で実際に使用するFOV。度単位
     * @param outputWidth 出力幅。ピクセル単位
     * @param outputHeight 出力高さ。ピクセル単位
     * @param displayTarget 診断出力を表示するターゲット。非表示診断ではnull
     * @return NGX出力を実表示先へ返した場合はtrue。診断だけの実行や未完了入力ではfalse
     */
    public boolean complete(RenderTarget target, float fieldOfView, int outputWidth, int outputHeight,
                            RenderTarget displayTarget) {
        RenderSystem.assertOnRenderThread();
        // 描画と共通の入力確定処理を使い、診断だけ別の行列や履歴を組み立てない。
        FrameInputRuntime.Completion completion = runtime.complete(target, fieldOfView);
        if (completion == null) return false;
        boolean displayed = false;
        FrameInputHistory.Result frame = completion.frame();
        FrameInputPacket packet = frame.current();
        diagnostics = runtime.diagnostics();
        if (!frame.valid()) {
            TitaniumClient.LOGGER.error("[Titanium/P4D] {}", diagnostics);
        } else {
            if (completion.motionFailure() != null) {
                failMotionProducer(completion.motionFailure());
            } else if (completion.motionReady()) {
                try {
                    if (motionVectorProbe != null) motionVectorProbe.capture(frame.previous(), packet);
                    if (frameInteropProbe != null) {
                        displayed = frameInteropProbe.capture(target, runtime.motionProducer(), frame.previous(), packet,
                                outputWidth, outputHeight, displayTarget);
                    }
                } catch (RuntimeException error) {
                    runtime.failMotionProducer();
                    failMotionProducer(error);
                }
            }
            if (!gpuFinished && !snapshotSubmitted) submitSnapshot(target, packet);
        }
        return displayed;
    }

    public void complete(RenderTarget target, float fieldOfView, int outputWidth, int outputHeight) {
        complete(target, fieldOfView, outputWidth, outputHeight, null);
    }

    /**
     * 転送済みの診断結果を完了通知後に取得し、判定と診断状態を更新する。
     *
     * 描画スレッドから呼び出す。
     */
    public void poll() {
        RenderSystem.assertOnRenderThread();
        if (motionVectorProbe != null) motionVectorProbe.poll();
        if (frameInteropProbe != null) frameInteropProbe.poll();
        if (!snapshotReady || gpuFinished) return;
        try (var color = colorReadback.map(true, false); var depth = depthReadback.map(true, false)) {
            int samples = Math.multiplyExact(snapshotWidth, snapshotHeight);
            ColorStatistics colorStats = ColorStatistics.analyze(color.data(), samples);
            DepthStatistics depthStats = DepthStatistics.analyze(depth.data(), samples);
            String gpu = "gpuSequence=" + snapshotSequence + ";attempt=" + snapshotAttempts
                    + ";color={" + colorStats.summary() + "};depth={" + depthStats.summary() + "}";
            gpuDiagnostics = gpu;
            if (colorStats.isNonUniform() && depthStats.isNonUniformWorldDepth()) {
                gpuFinished = true;
                gpuPassed = true;
                if (depthReconstructionProbe != null) {
                    depthReconstructionProbe.analyze(snapshotPacket, depth.data());
                }
                TitaniumClient.LOGGER.info("[Titanium/P4D] GPU snapshot PASS; {}", diagnostics());
            } else if (snapshotAttempts >= MAX_EMPTY_ATTEMPTS) {
                gpuFinished = true;
                gpuDiagnostics = "GPU snapshot remained empty;" + gpu;
                TitaniumClient.LOGGER.error("[Titanium/P4D] GPU snapshot FAIL; {}", diagnostics());
            }
        } catch (RuntimeException error) {
            fail("snapshot analysis", error);
        }
        releaseGpuResources();
    }

    /**
     * 同一境界の色と深度を退避し、GPU完了後の読み戻しを予約する。
     *
     * @param target 操作対象の描画先またはリソース
     * @param packet 同じ描画に使用した確定済み入力パケット
     */
    private void submitSnapshot(RenderTarget target, FrameInputPacket packet) {
        try {
            snapshotWidth = target.width;
            snapshotHeight = target.height;
            snapshotSequence = packet.sequence();
            snapshotPacket = packet;
            snapshotAttempts++;
            int samples = Math.multiplyExact(snapshotWidth, snapshotHeight);
            long bytes = Math.multiplyExact((long) samples, 4L);
            var device = RenderSystem.getDevice();
            colorSnapshot = device.createTexture(() -> "Titanium P4D color", 3,
                    GpuFormat.RGBA8_UNORM, snapshotWidth, snapshotHeight, 1, 1);
            depthSnapshot = device.createTexture(() -> "Titanium P4D depth", 3,
                    GpuFormat.D32_FLOAT, snapshotWidth, snapshotHeight, 1, 1);
            colorReadback = device.createBuffer(() -> "Titanium P4D color readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            depthReadback = device.createBuffer(() -> "Titanium P4D depth readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            readFramebuffer = GlStateManager.glGenFramebuffers();
            var encoder = device.createCommandEncoder();
            // 同じ境界で色と深度を退避し、後続の描画変更を受けない入力ペアとして解析する。
            encoder.copyTextureToTexture(target.getColorTexture(), colorSnapshot,
                    0, 0, 0, 0, 0, snapshotWidth, snapshotHeight);
            encoder.copyTextureToTexture(target.getDepthTexture(), depthSnapshot,
                    0, 0, 0, 0, 0, snapshotWidth, snapshotHeight);
            readSnapshots();
            snapshotSubmitted = true;
        } catch (RuntimeException error) {
            fail("snapshot submission", error);
        }
    }

    /**
     * 完了した色・深度の読み戻しを解析し、必要なら同じ深度を復元診断へ渡す。
     */
    private void readSnapshots() {
        if (!(colorSnapshot instanceof GlTexture color) || !(depthSnapshot instanceof GlTexture depth)
                || !(colorReadback instanceof GlBuffer colorBuffer) || !(depthReadback instanceof GlBuffer depthBuffer)) {
            throw new IllegalStateException("P4D requires OpenGL snapshot resources");
        }
        int previousFramebuffer = GlStateManager.getFrameBuffer(GL33C.GL_READ_FRAMEBUFFER);
        int previousPackBuffer = GL33C.glGetInteger(GL33C.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousRowLength = GL33C.glGetInteger(GL33C.GL_PACK_ROW_LENGTH);
        GlStateManager.clearGlErrors();
        try {
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, color.glId(), 0);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT,
                    GL33C.GL_TEXTURE_2D, depth.glId(), 0);
            if (GL33C.glCheckFramebufferStatus(GL33C.GL_READ_FRAMEBUFFER) != GL33C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("P4D read framebuffer incomplete");
            }
            GlStateManager._pixelStore(GL33C.GL_PACK_ROW_LENGTH, snapshotWidth);
            GL33C.glReadBuffer(GL33C.GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, colorBuffer.handle());
            GlStateManager._readPixels(0, 0, snapshotWidth, snapshotHeight,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, 0L);
            GlStateManager._glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, depthBuffer.handle());
            GlStateManager._readPixels(0, 0, snapshotWidth, snapshotHeight,
                    GL33C.GL_DEPTH_COMPONENT, GL33C.GL_FLOAT, 0L);
            RenderSystem.queueFencedTask(() -> snapshotReady = true);
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
            throw new IllegalStateException("P4D snapshot read failed: GL error 0x" + Integer.toHexString(error));
        }
    }

    /**
     * 失敗した操作と例外を記録し、不完全な診断結果の継続利用を停止する。
     *
     * @param operation 失敗時の診断に付ける操作名
     * @param error 失敗した操作の結果または例外
     */
    private void fail(String operation, RuntimeException error) {
        gpuFinished = true;
        gpuPassed = false;
        diagnostics = operation + " failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        TitaniumClient.LOGGER.error("[Titanium/P4D] {}; Bilinear remains active", diagnostics, error);
    }

    /**
     * MV生成器の失敗を記録し、後続の診断へ失敗状態を伝える。
     *
     * @param error 失敗した操作の結果または例外
     */
    private void failMotionProducer(RuntimeException error) {
        if (motionVectorProbe != null) motionVectorProbe.failProducer(error);
        if (frameInteropProbe != null) frameInteropProbe.failProducer(error);
        TitaniumClient.LOGGER.error("[Titanium/P6B] camera motion producer disabled; "
                + "Bilinear remains active", error);
    }

    public void breakHistory() {
        runtime.breakHistory();
        if (frameInteropProbe != null) frameInteropProbe.breakHistory();
    }

    public long packets() {
        return runtime.packets();
    }

    public long resets() {
        return runtime.resets();
    }

    public long continuousFrames() {
        return runtime.continuousFrames();
    }

    public long cameraMotionFrames() {
        return runtime.cameraMotionFrames();
    }

    public String diagnostics() {
        return diagnostics + ";invalidPackets=" + runtime.invalidPackets() + ";" + gpuDiagnostics;
    }

    public boolean gpuFinished() {
        return gpuFinished;
    }

    public boolean passed() {
        return gpuPassed && runtime.passed();
    }

    public boolean historyPassed() {
        return gpuPassed && runtime.historyPassed();
    }

    public boolean jitterPassed() {
        return gpuPassed && runtime.jitterPassed();
    }

    public boolean depthReconstructionFinished() {
        return depthReconstructionProbe != null && depthReconstructionProbe.finished();
    }

    public boolean depthReconstructionPassed() {
        return depthReconstructionProbe != null && depthReconstructionProbe.passed();
    }

    public String depthReconstructionDiagnostics() {
        return depthReconstructionProbe == null ? "disabled" : depthReconstructionProbe.diagnostics();
    }

    public boolean motionVectorFinished() {
        return motionVectorProbe != null && motionVectorProbe.finished();
    }

    public void armMotionVectorCase(String name) {
        if (motionVectorProbe == null) throw new IllegalStateException("P6B motion-vector probe is disabled");
        motionVectorProbe.armCase(name);
    }

    public boolean motionVectorCaseFinished(String name) {
        return motionVectorProbe != null && motionVectorProbe.caseFinished(name);
    }

    public boolean motionVectorPassed() {
        return motionVectorProbe != null && motionVectorProbe.passed();
    }

    public String motionVectorDiagnostics() {
        return motionVectorProbe == null ? "disabled" : motionVectorProbe.diagnostics()
                + ";producer={" + runtime.motionDiagnostics() + "}";
    }

    public boolean frameInteropPassed() {
        return frameInteropProbe != null && frameInteropProbe.passed();
    }

    public boolean frameOutputFallbackSafe() {
        return frameInteropProbe == null || frameInteropProbe.outputFallbackSafe();
    }

    public String frameInteropDiagnostics() {
        return frameInteropProbe == null ? "disabled" : frameInteropProbe.diagnostics()
                + ";motion={" + runtime.motionDiagnostics() + "}";
    }

    public boolean dlssInitialized() {
        return frameInteropProbe != null && frameInteropProbe.ngxInitialized();
    }

    public boolean dlssFeatureReady() {
        return frameInteropProbe != null && frameInteropProbe.ngxFeatureReady();
    }

    public long dlssFeatureGeneration() {
        return frameInteropProbe == null ? 0 : frameInteropProbe.ngxGenerations();
    }

    public String dlssEvaluateResult() {
        return frameInteropProbe == null ? "N/A" : frameInteropProbe.ngxEvaluateResult();
    }

    public double dlssFrameTimeMilliseconds() {
        return frameInteropProbe == null ? Double.NaN : frameInteropProbe.lastFrameTimeMilliseconds();
    }

    public FrameJitter latestJitter() {
        return runtime.latestJitter();
    }

    public boolean latestReset() {
        return runtime.latestReset();
    }

    public boolean historyValid() {
        return runtime.historyValid();
    }

    /**
     * 所有するリソースと保留状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        releaseGpuResources();
        if (motionVectorProbe != null) motionVectorProbe.close();
        if (frameInteropProbe != null) frameInteropProbe.close();
        runtime.close();
    }

    /**
     * 診断で所有するGPUスナップショットと読み戻し用リソースを解放する。
     */
    private void releaseGpuResources() {
        if (readFramebuffer != 0) GlStateManager._glDeleteFramebuffers(readFramebuffer);
        if (colorReadback != null) colorReadback.close();
        if (depthReadback != null) depthReadback.close();
        if (colorSnapshot != null) colorSnapshot.close();
        if (depthSnapshot != null) depthSnapshot.close();
        readFramebuffer = 0;
        colorReadback = null;
        depthReadback = null;
        colorSnapshot = null;
        depthSnapshot = null;
        snapshotPacket = null;
        snapshotReady = false;
        snapshotSubmitted = false;
    }
}
