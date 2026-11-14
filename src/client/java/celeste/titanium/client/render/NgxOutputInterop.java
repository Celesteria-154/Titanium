package celeste.titanium.client.render;

import celeste.titanium.client.nativebridge.NativeBridge;
import celeste.titanium.client.nativebridge.NativeError;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL33C;

/** NGX出力のWGL共有所有権と、GL描画先への返却を管理する。 */
final class NgxOutputInterop implements AutoCloseable {
    private final DlssOutputSharpening sharpening = new DlssOutputSharpening();
    private int sharedTexture;
    private int readFramebuffer;
    private int drawFramebuffer;
    private int width;
    private int height;
    private long registrations;
    private long releases;
    private long returned;
    private long scissorGuards;
    private boolean registered;
    private boolean ownershipUncertain;

    /**
     * 指定した出力解像度のGL名とFBOを確保し、NGX出力へのWGL共有登録を作る。
     *
     * 描画スレッドから呼び出す。
     *
     * @param nextWidth 準備する幅。ピクセル単位
     * @param nextHeight 準備する高さ。ピクセル単位
     */
    void prepare(int nextWidth, int nextHeight) {
        RenderSystem.assertOnRenderThread();
        if (registered || nextWidth <= 0 || nextHeight <= 0) {
            throw new IllegalStateException("NGX invalid output prepare state");
        }
        width = nextWidth;
        height = nextHeight;
        sharedTexture = GlStateManager._genTexture();
        readFramebuffer = GlStateManager.glGenFramebuffers();
        drawFramebuffer = GlStateManager.glGenFramebuffers();
        // NGX所有の出力をGL名へ登録し、返却コピーに使用するFBOを用意する。
        NativeError result = NativeBridge.registerNgxOutput(sharedTexture);
        if (result != NativeError.OK) {
            releaseGlResources(true);
            throw new IllegalStateException("NGX output register failed: " + result + "; "
                    + NativeBridge.ngxDiagnostics());
        }
        registered = true;
        registrations++;
    }

    /**
     * 指定した評価番号の出力をGLで取得し、必要ならシャープ化して表示先へコピー後に所有権を返す。
     *
     * 読み取りと上下反転コピーはGLが出力を所有する区間に限定する。解除失敗時は安全な退避が不可能な状態として保持する。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param destination 書き込み先のGLテクスチャ識別子
     * @param destinationWidth 表示先の幅。ピクセル単位
     * @param destinationHeight 表示先の高さ。ピクセル単位
     */
    void returnOutput(long sequence, int destination, int destinationWidth, int destinationHeight) {
        returnOutput(sequence, destination, destinationWidth, destinationHeight, 0.0);
    }

    /**
     * 指定した評価番号の出力をGLで取得し、必要ならシャープ化して表示先へコピー後に所有権を返す。
     *
     * 描画スレッドから呼び出す。
     *
     * 読み取りと上下反転コピーはGLが出力を所有する区間に限定する。解除失敗時は安全な退避が不可能な状態として保持する。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param destination 書き込み先のGLテクスチャ識別子
     * @param destinationWidth 表示先の幅。ピクセル単位
     * @param destinationHeight 表示先の高さ。ピクセル単位
     * @param sharpness 出力へ適用する空間シャープ化の強度。0.0〜1.0
     */
    void returnOutput(long sequence, int destination, int destinationWidth, int destinationHeight,
                      double sharpness) {
        RenderSystem.assertOnRenderThread();
        if (!Double.isFinite(sharpness) || sharpness < 0.0 || sharpness > 1.0) {
            throw new IllegalArgumentException("Invalid DLSS output sharpness");
        }
        if (!registered) throw new IllegalStateException("NGX output is unavailable");
        if (destination == 0 || destinationWidth != width || destinationHeight != height) {
            throw new IllegalStateException("NGX native display target mismatch");
        }
        // 指定シーケンスの評価結果をGLが所有している区間だけ、表示先へのコピーを許可する。
        require(NativeBridge.lockNgxOutput(sequence), "lock");
        RuntimeException blitFailure = null;
        try {
            blitTo(destination, sharpness);
        } catch (RuntimeException error) {
            blitFailure = error;
        } finally {
            NativeError unlock = NativeBridge.unlockNgxOutput(sequence);
            // 所有権の返却に失敗した場合は、安全なBilinear退避ができる状態として扱わない。
            if (unlock != NativeError.OK) ownershipUncertain = true;
            require(unlock, "unlock");
        }
        if (blitFailure != null) throw blitFailure;
        returned++;
    }

    // 共有出力を参照するFBOを先に破棄し、ネイティブ側のFeature解放へ参照を残さない。
    /**
     * NGX Featureの出力を参照する読み取り・書き込みFBOを先に破棄する。
     *
     * 描画スレッドから呼び出す。
     */
    void beforeFeatureRelease() {
        RenderSystem.assertOnRenderThread();
        if (readFramebuffer != 0) GlStateManager._glDeleteFramebuffers(readFramebuffer);
        if (drawFramebuffer != 0) GlStateManager._glDeleteFramebuffers(drawFramebuffer);
        readFramebuffer = 0;
        drawFramebuffer = 0;
    }

    /**
     * ネイティブ側の出力共有解除後に、GL名と寸法の所有状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    void afterFeatureRelease() {
        RenderSystem.assertOnRenderThread();
        if (!registered) return;
        registered = false;
        releases++;
        releaseGlResources(false);
    }

    /**
     * 共有出力の所有権が不明な状態に陥っておらず、通常のコピーへ退避できるか返す。
     *
     * @return 共有出力の所有権が不明な状態に陥っておらず、通常のコピーへ退避できる場合はtrue
     */
    boolean fallbackSafe() {
        return !ownershipUncertain;
    }

    long returned() {
        return returned;
    }

    String diagnostics() {
        return "registered=" + registered + ",registrations=" + registrations + ",releases=" + releases
                + ",returned=" + returned + ",scissorGuards=" + scissorGuards
                + ",ownershipUncertain=" + ownershipUncertain;
    }

    /**
     * シザーの影響を外し、上下方向を戻して出力を等倍コピーする。強度指定時は同じ返却にシャープ化を統合する。
     *
     * @param destination 書き込み先のGLテクスチャ識別子
     * @param sharpness 出力へ適用する空間シャープ化の強度。0.0〜1.0
     */
    private void blitTo(int destination, double sharpness) {
        int previousRead = GlStateManager.getFrameBuffer(GL33C.GL_READ_FRAMEBUFFER);
        int previousDraw = GlStateManager.getFrameBuffer(GL33C.GL_DRAW_FRAMEBUFFER);
        boolean scissorEnabled = GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST);
        GlStateManager.clearGlErrors();
        try {
            // glBlitFramebuffer is clipped by the world render's low-resolution scissor box.
            // Use raw calls so Blaze3D's cache still describes the restored state.
            if (scissorEnabled) {
                GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
                scissorGuards++;
            }
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, sharedTexture, 0);
            requireFramebuffer(GL33C.GL_READ_FRAMEBUFFER, "read");
            GlStateManager._glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_DRAW_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, destination, 0);
            requireFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, "draw");
            // Undo the input-boundary row flip when returning D3D top-left output to GL.
            if (sharpness == 0.0) {
                GlStateManager._glBlitFrameBuffer(0, height, width, 0, 0, 0, width, height,
                        GL33C.GL_COLOR_BUFFER_BIT, GL33C.GL_NEAREST);
            } else {
                // Replace the existing return blit; no intermediate texture or second pass.
                sharpening.draw(sharedTexture, width, height, sharpness);
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, previousDraw);
            if (scissorEnabled) GL33C.glEnable(GL33C.GL_SCISSOR_TEST);
        }
        requireGl("1:1 output blit");
    }

    /**
     * GL側で所有するFBOとテクスチャ名を、共有登録の寿命に合わせて解放する。
     *
     * @param deleteSharedName 共有GL名自体も削除する場合はtrue
     */
    private void releaseGlResources(boolean deleteSharedName) {
        beforeFeatureRelease();
        if ((deleteSharedName || !registered) && sharedTexture != 0) {
            GlStateManager._deleteTexture(sharedTexture);
            sharedTexture = 0;
        }
        width = 0;
        height = 0;
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
            throw new IllegalStateException("NGX " + label + " framebuffer incomplete: 0x"
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
            throw new IllegalStateException("NGX " + operation + " GL error 0x"
                    + Integer.toHexString(error));
        }
    }

    private static void require(NativeError result, String operation) {
        if (result != NativeError.OK) {
            throw new IllegalStateException("NGX output " + operation + " failed: " + result + "; "
                    + NativeBridge.ngxDiagnostics());
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
        if (registered) throw new IllegalStateException("NGX GL output still registered during close");
        releaseGlResources(false);
        sharpening.close();
    }
}
