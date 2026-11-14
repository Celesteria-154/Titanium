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
import org.lwjgl.opengl.GL33C;

import java.util.Locale;

/** 一人称の手を描画する直前の深度と、その後のクリア値を明示的な診断時に取得する。 */
public final class DepthSnapshotProbe implements AutoCloseable {
    private static final int MAX_EMPTY_ATTEMPTS = 120;
    private final DepthStatistics.Endpoint expectedEndpoint;
    private GpuTexture snapshot;
    private GpuBuffer beforeReadback;
    private GpuBuffer afterReadback;
    private int readFramebuffer;
    private int width;
    private int height;
    private volatile boolean beforeReady;
    private volatile boolean afterReady;
    private boolean beforeSubmitted;
    private boolean afterSubmitted;
    private boolean finished;
    private boolean passed;
    private int attempts;
    private String diagnostics = "waiting for scaled world depth";

    public DepthSnapshotProbe() {
        String expected = System.getProperty("titanium.depthProbe.expectedClear", "");
        expectedEndpoint = expected.isBlank() ? DepthStatistics.Endpoint.NONE
                : DepthStatistics.Endpoint.valueOf(expected.toUpperCase(Locale.ROOT));
    }

    /**
     * 手を描画するための深度クリア前に、ワールド深度を退避して読み戻しを予約する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param source 読み取り元の入力リソース
     */
    public void captureBeforeClear(RenderTarget source) {
        RenderSystem.assertOnRenderThread();
        if (finished || beforeSubmitted) return;
        try {
            GpuTexture sourceDepth = requireDepth(source);
            width = source.width;
            height = source.height;
            int samples = Math.multiplyExact(width, height);
            long bytes = Math.multiplyExact((long) samples, Float.BYTES);
            var device = RenderSystem.getDevice();
            snapshot = device.createTexture(() -> "Titanium P4C pre-hand depth", 3,
                    GpuFormat.D32_FLOAT, width, height, 1, 1);
            beforeReadback = device.createBuffer(() -> "Titanium P4C before-clear readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            afterReadback = device.createBuffer(() -> "Titanium P4C after-clear readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            readFramebuffer = GlStateManager.glGenFramebuffers();
            var encoder = device.createCommandEncoder();
            // 手の描画用クリアで元深度が失われる前に、同じ寸法の診断用テクスチャへ退避する。
            encoder.copyTextureToTexture(sourceDepth, snapshot, 0, 0, 0, 0, 0, width, height);
            readDepthToBuffer(snapshot, beforeReadback, () -> beforeReady = true);
            beforeSubmitted = true;
            attempts++;
            diagnostics = "waiting for before/after GPU fences";
        } catch (RuntimeException error) {
            fail("before-clear capture", error);
        }
    }

    /**
     * 同じ寸法のクリア後深度を読み戻しへ予約し、実際のクリア端点を後で検証する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param source 読み取り元の入力リソース
     */
    public void captureAfterClear(RenderTarget source) {
        RenderSystem.assertOnRenderThread();
        if (finished || !beforeSubmitted || afterSubmitted) return;
        try {
            GpuTexture sourceDepth = requireDepth(source);
            if (source.width != width || source.height != height) {
                throw new IllegalStateException("Depth dimensions changed inside clear boundary");
            }
            readDepthToBuffer(sourceDepth, afterReadback, () -> afterReady = true);
            afterSubmitted = true;
        } catch (RuntimeException error) {
            fail("after-clear capture", error);
        }
    }

    /**
     * 転送済みの診断結果を完了通知後に取得し、判定と診断状態を更新する。
     *
     * 描画スレッドから呼び出す。
     */
    public void poll() {
        RenderSystem.assertOnRenderThread();
        // 前後のGPU転送が両方完了した後だけ読み戻しをmapし、描画中のデータを解析しない。
        if (finished || !beforeReady || !afterReady) return;
        DepthStatistics beforeStats;
        DepthStatistics afterStats;
        try (var before = beforeReadback.map(true, false); var after = afterReadback.map(true, false)) {
            int samples = Math.multiplyExact(width, height);
            beforeStats = DepthStatistics.analyze(before.data(), samples);
            afterStats = DepthStatistics.analyze(after.data(), samples);
        } catch (RuntimeException error) {
            fail("readback analysis", error);
            return;
        }
        DepthStatistics.Endpoint observed = afterStats.uniformEndpoint();
        boolean endpointMatches = observed != DepthStatistics.Endpoint.NONE
                && (expectedEndpoint == DepthStatistics.Endpoint.NONE || observed == expectedEndpoint);
        diagnostics = "size=" + width + "x" + height + ";before={" + beforeStats.summary()
                + "};after={" + afterStats.summary() + "};expected="
                + (expectedEndpoint == DepthStatistics.Endpoint.NONE ? "ANY" : expectedEndpoint)
                + ";observed=" + observed + ";attempt=" + attempts;
        if (!beforeStats.isFiniteUnitRange() || !endpointMatches) {
            finished = true;
            passed = false;
            TitaniumClient.LOGGER.error("[Titanium/P4C] depth snapshot FAIL; {}", diagnostics);
        } else if (!beforeStats.isNonUniformWorldDepth() && attempts < MAX_EMPTY_ATTEMPTS) {
            diagnostics = "waiting for non-empty world depth after attempt " + attempts;
        } else {
            passed = beforeStats.isNonUniformWorldDepth();
            finished = true;
            if (passed) {
                TitaniumClient.LOGGER.info("[Titanium/P4C] depth snapshot PASS; {}", diagnostics);
            } else {
                TitaniumClient.LOGGER.error("[Titanium/P4C] depth snapshot FAIL; {}", diagnostics);
            }
        }
        releaseResources();
    }

    /**
     * 描画先が必要な深度形式を持つことを確認し、深度テクスチャを返す。
     *
     * @param source 読み取り元の入力リソース
     * @return 診断で使用できる形式を持つ借用深度テクスチャ
     */
    private static GpuTexture requireDepth(RenderTarget source) {
        GpuTexture depth = source.getDepthTexture();
        if (depth == null || depth.getFormat() != GpuFormat.D32_FLOAT) {
            throw new IllegalStateException("Expected D32_FLOAT main depth");
        }
        return depth;
    }

    /**
     * 指定した深度を読み戻しバッファへ転送し、GPU完了後にコールバックを実行する。
     *
     * @param source 読み取り元の入力リソース
     * @param destination GPU転送先の読み戻しバッファ
     * @param callback GPU転送完了後に呼び出す通知処理
     */
    private void readDepthToBuffer(GpuTexture source, GpuBuffer destination, Runnable callback) {
        if (!(source instanceof GlTexture texture) || !(destination instanceof GlBuffer buffer)) {
            throw new IllegalStateException("P4C requires OpenGL depth and readback resources");
        }
        int previousReadFramebuffer = GlStateManager.getFrameBuffer(GL33C.GL_READ_FRAMEBUFFER);
        int previousPixelPackBuffer = GL33C.glGetInteger(GL33C.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPackRowLength = GL33C.glGetInteger(GL33C.GL_PACK_ROW_LENGTH);
        GlStateManager.clearGlErrors();
        try {
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT,
                    GL33C.GL_TEXTURE_2D, texture.glId(), 0);
            GL33C.glReadBuffer(GL33C.GL_NONE);
            int status = GL33C.glCheckFramebufferStatus(GL33C.GL_READ_FRAMEBUFFER);
            if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Depth read framebuffer incomplete: 0x"
                        + Integer.toHexString(status));
            }
            GlStateManager._glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, buffer.handle());
            GlStateManager._pixelStore(GL33C.GL_PACK_ROW_LENGTH, width);
            GlStateManager._readPixels(0, 0, width, height, GL33C.GL_DEPTH_COMPONENT, GL33C.GL_FLOAT, 0L);
            RenderSystem.queueFencedTask(callback);
        } finally {
            GlStateManager._pixelStore(GL33C.GL_PACK_ROW_LENGTH, previousPackRowLength);
            GlStateManager._glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT,
                    GL33C.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
        int error = GlStateManager._getError();
        if (error != GL33C.GL_NO_ERROR) {
            throw new IllegalStateException("Depth PBO read failed: GL error 0x" + Integer.toHexString(error));
        }
    }

    /**
     * 失敗した操作と例外を記録し、不完全な診断結果の継続利用を停止する。
     *
     * @param operation 失敗時の診断に付ける操作名
     * @param error 失敗した操作の結果または例外
     */
    private void fail(String operation, RuntimeException error) {
        finished = true;
        passed = false;
        diagnostics = operation + " failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        TitaniumClient.LOGGER.error("[Titanium/P4C] {}; Bilinear remains active", diagnostics, error);
    }

    public boolean finished() {
        return finished;
    }

    public boolean passed() {
        return passed;
    }

    public String diagnostics() {
        return diagnostics;
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
        if (beforeReadback != null) beforeReadback.close();
        if (afterReadback != null) afterReadback.close();
        if (snapshot != null) snapshot.close();
        beforeReadback = null;
        afterReadback = null;
        snapshot = null;
        readFramebuffer = 0;
        beforeReady = false;
        afterReady = false;
        beforeSubmitted = false;
        afterSubmitted = false;
    }
}
