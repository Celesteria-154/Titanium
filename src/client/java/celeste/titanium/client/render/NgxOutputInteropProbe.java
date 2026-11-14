package celeste.titanium.client.render;

import celeste.titanium.client.nativebridge.NativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL33C;

/** NGX出力をWGL経由で検証用または表示用の描画先へ返す。 */
final class NgxOutputInteropProbe implements AutoCloseable {
    private static final int VALIDATION_EXTENT = 256;
    private static final int SLOT_COUNT = 4;
    private static final long BUFFER_BYTES = (long) VALIDATION_EXTENT * VALIDATION_EXTENT * 4;
    private static final long REQUIRED_RESET = 7;
    private static final long REQUIRED_STATIC = 7;
    private static final long REQUIRED_MOTION = 1;

    private static final class Slot {
        private GpuBuffer buffer;
        private long sequence;
        private int kind;
        private int samples;
        private boolean pending;
        private volatile boolean ready;
    }

    private final Slot[] slots = new Slot[SLOT_COUNT];
    private GpuTexture validationTarget;
    private final NgxOutputInterop output = new NgxOutputInterop();
    private int readFramebuffer;
    private int width;
    private int height;
    private long submitted;
    private long completed;
    private long skipped;
    private final long[] kinds = new long[4];
    private String failure;
    private final NgxDisplayMode displayMode = NgxDisplayMode.configured();

    /**
     * 出力共有と、表示モードに応じた診断ターゲットおよび読み戻しスロットを準備する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param nextWidth 準備する幅。ピクセル単位
     * @param nextHeight 準備する高さ。ピクセル単位
     */
    void prepare(int nextWidth, int nextHeight) {
        RenderSystem.assertOnRenderThread();
        if (nextWidth <= 0 || nextHeight <= 0) {
            throw new IllegalStateException("P10A invalid output prepare state");
        }
        ensureSlots();
        width = nextWidth;
        height = nextHeight;
        var device = RenderSystem.getDevice();
        if (!displayMode.visible()) {
            validationTarget = device.createTexture(() -> "Titanium P10A returned output",
                    GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            if (!(validationTarget instanceof GlTexture)) {
                validationTarget.close();
                validationTarget = null;
                throw new IllegalStateException("P10A requires an OpenGL validation target");
            }
        }
        readFramebuffer = GlStateManager.glGenFramebuffers();
        try {
            output.prepare(width, height);
        } catch (RuntimeException error) {
            releaseValidationResources();
            throw error;
        }
    }

    /**
     * 同一番号のNGX出力を指定先へ返し、必要な診断種別の読み戻しを予約する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param validationKind 読み戻し診断の種類。0は診断なし
     * @param displayTarget 診断出力を表示するターゲット。非表示診断ではnull
     */
    void returnOutput(long sequence, int validationKind, RenderTarget displayTarget) {
        RenderSystem.assertOnRenderThread();
        if (failure != null) throw new IllegalStateException("P10A output is unavailable");
        int destination = destinationTexture(displayTarget);
        output.returnOutput(sequence, destination, width, height);
        if (validationKind != 0 && !validationComplete()) {
            submitValidation(sequence, validationKind, destination);
        }
    }

    /**
     * 転送済みの診断結果を完了通知後に取得し、判定と診断状態を更新する。
     *
     * 描画スレッドから呼び出す。
     */
    void poll() {
        RenderSystem.assertOnRenderThread();
        if (failure != null) throw new IllegalStateException(failure);
        for (Slot slot : slots) {
            if (!slot.pending || !slot.ready) continue;
            boolean nonzero = false;
            boolean nonuniform = false;
            try (var mapped = slot.buffer.map(true, false)) {
                var data = mapped.data();
                int firstRed = Byte.toUnsignedInt(data.get(0));
                int firstGreen = Byte.toUnsignedInt(data.get(1));
                int firstBlue = Byte.toUnsignedInt(data.get(2));
                int firstAlpha = Byte.toUnsignedInt(data.get(3));
                for (int index = 0; index < slot.samples; index++) {
                    int offset = index * 4;
                    int red = Byte.toUnsignedInt(data.get(offset));
                    int green = Byte.toUnsignedInt(data.get(offset + 1));
                    int blue = Byte.toUnsignedInt(data.get(offset + 2));
                    int alpha = Byte.toUnsignedInt(data.get(offset + 3));
                    nonzero |= red != 0 || green != 0 || blue != 0;
                    nonuniform |= red != firstRed || green != firstGreen || blue != firstBlue
                            || alpha != firstAlpha;
                }
            }
            slot.pending = false;
            slot.ready = false;
            if (!nonzero || !nonuniform) {
                failure = "P10A output content;sequence=" + slot.sequence + ",kind=" + slot.kind
                        + ",nonzero=" + nonzero + ",nonuniform=" + nonuniform;
                throw new IllegalStateException(failure);
            }
            completed++;
            kinds[slot.kind]++;
        }
    }

    /**
     * ネイティブのFeature解放前に、出力共有へのGL参照を返す。
     */
    void beforeFeatureRelease() {
        try {
            poll();
        } finally {
            output.beforeFeatureRelease();
            if (readFramebuffer != 0) GlStateManager._glDeleteFramebuffers(readFramebuffer);
            readFramebuffer = 0;
        }
    }

    /**
     * Feature解放完了後に、出力共有のJava側状態と診断用資源を解放する。
     */
    void afterFeatureRelease() {
        output.afterFeatureRelease();
        releaseValidationResources();
    }

    boolean passed() {
        boolean pending = false;
        for (Slot slot : slots) pending |= slot.pending;
        return validationComplete() && !pending
                && output.returned() > 0 && NativeBridge.ngxOutputInteropPassed();
    }

    /**
     * 返却用の共有出力が安全な退避状態にあるか確認する。
     *
     * @return 返却用の共有出力が安全な退避状態にある場合はtrue
     */
    boolean fallbackSafe() {
        return output.fallbackSafe();
    }

    String diagnostics() {
        int pending = 0;
        for (Slot slot : slots) if (slot.pending) pending++;
        return "output={" + output.diagnostics() + "},validation={submitted=" + submitted
                + ",completed=" + completed + ",pending=" + pending + ",skipped=" + skipped
                + ",reset=" + kinds[1] + ",static=" + kinds[2] + ",motion=" + kinds[3] + "}"
                + ",failure=" + (failure == null ? "none" : failure);
    }

    /**
     * 未生成の診断スロットを初期化する。
     */
    private void ensureSlots() {
        var device = RenderSystem.getDevice();
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] != null) continue;
            Slot slot = new Slot();
            int slotIndex = index;
            slot.buffer = device.createBuffer(() -> "Titanium P10A output readback " + slotIndex,
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, BUFFER_BYTES);
            slots[index] = slot;
        }
    }

    private boolean validationComplete() {
        return failure == null && skipped == 0
                && kinds[1] >= REQUIRED_RESET && kinds[2] >= REQUIRED_STATIC && kinds[3] >= REQUIRED_MOTION;
    }

    /**
     * 返却先カラーの診断領域を空きスロットへ転送し、GPU完了通知を予約する。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param kind リセット・静止・移動などの読み戻し診断種別
     * @param textureId 読み戻し対象のGLテクスチャ識別子
     */
    private void submitValidation(long sequence, int kind, int textureId) {
        if (kind < 1 || kind > 3) throw new IllegalArgumentException("P10A validation kind " + kind);
        Slot slot = null;
        for (Slot candidate : slots) {
            if (!candidate.pending) {
                slot = candidate;
                break;
            }
        }
        if (slot == null) {
            skipped++;
            failure = "P10A validation ring full";
            throw new IllegalStateException(failure);
        }
        if (!(slot.buffer instanceof GlBuffer buffer)) {
            throw new IllegalStateException("P10A requires OpenGL readback resources");
        }
        int sampleWidth = Math.min(width, VALIDATION_EXTENT);
        int sampleHeight = Math.min(height, VALIDATION_EXTENT);
        int left = (width - sampleWidth) / 2;
        int bottom = (height - sampleHeight) / 2;
        int previousRead = GlStateManager.getFrameBuffer(GL33C.GL_READ_FRAMEBUFFER);
        int previousPackBuffer = GL33C.glGetInteger(GL33C.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPackRowLength = GL33C.glGetInteger(GL33C.GL_PACK_ROW_LENGTH);
        GlStateManager.clearGlErrors();
        try {
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, textureId, 0);
            requireFramebuffer(GL33C.GL_READ_FRAMEBUFFER, "validation read");
            GL33C.glReadBuffer(GL33C.GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, buffer.handle());
            GlStateManager._pixelStore(GL33C.GL_PACK_ROW_LENGTH, sampleWidth);
            GlStateManager._readPixels(left, bottom, sampleWidth, sampleHeight,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, 0L);
            Slot submittedSlot = slot;
            submittedSlot.sequence = sequence;
            submittedSlot.kind = kind;
            submittedSlot.samples = Math.multiplyExact(sampleWidth, sampleHeight);
            submittedSlot.pending = true;
            submittedSlot.ready = false;
            RenderSystem.queueFencedTask(() -> submittedSlot.ready = true);
            submitted++;
        } finally {
            GlStateManager._pixelStore(GL33C.GL_PACK_ROW_LENGTH, previousPackRowLength);
            GlStateManager._glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, previousRead);
        }
        requireGl("output validation readback");
    }

    /**
     * 未完了の診断読み戻しを無効化し、診断用バッファとターゲットを解放する。
     */
    private void releaseValidationResources() {
        if (readFramebuffer != 0) GlStateManager._glDeleteFramebuffers(readFramebuffer);
        readFramebuffer = 0;
        if (validationTarget != null) validationTarget.close();
        validationTarget = null;
        width = 0;
        height = 0;
    }

    /**
     * 表示モードに応じた出力先を検証し、そのGLテクスチャ識別子を返す。
     *
     * @param displayTarget 診断出力を表示するターゲット。非表示診断ではnull
     * @return 実表示先または内部診断ターゲットのGLカラー識別子
     */
    private int destinationTexture(RenderTarget displayTarget) {
        if (displayTarget == null) {
            if (validationTarget instanceof GlTexture texture) return texture.glId();
            throw new IllegalStateException("P10A validation target missing");
        }
        if (!displayMode.visible() || displayTarget.width != width || displayTarget.height != height
                || !(displayTarget.getColorTexture() instanceof GlTexture texture)) {
            throw new IllegalStateException(displayMode.phase() + " native display target mismatch");
        }
        return texture.glId();
    }

    /**
     * 指定した読み取り・書き込みFBOが完全な状態で接続されているか確認する。
     *
     * @param target 完全性を検証するGL_READ_FRAMEBUFFERまたはGL_DRAW_FRAMEBUFFER
     * @param label 表示欄または診断対象を識別する名前
     */
    private static void requireFramebuffer(int target, String label) {
        int status = GL33C.glCheckFramebufferStatus(target);
        if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("P10A " + label + " framebuffer incomplete: 0x"
                    + Integer.toHexString(status));
        }
    }

    /**
     * 直前のGL処理のエラーを確認し、失敗した操作名とエラーコードを例外へ含める。
     *
     * @param operation 失敗時の診断に付ける操作名
     */
    private static void requireGl(String operation) {
        int error = GlStateManager._getError();
        if (error != GL33C.GL_NO_ERROR) {
            throw new IllegalStateException("P10A " + operation + " GL error 0x"
                    + Integer.toHexString(error));
        }
    }

    /**
     * 所有するリソースと保留状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        output.close();
        releaseValidationResources();
        for (Slot slot : slots) {
            if (slot != null && slot.buffer != null) slot.buffer.close();
        }
    }
}
