package celeste.titanium.client.nativebridge;

import celeste.titanium.client.TitaniumClient;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL33C;

/** 明示的な診断時にMinecraftのカラーをGL・D3D11間で往復させ、Bilinear描画へ返す。 */
public final class MinecraftTextureInteropProbe implements AutoCloseable {
    private int inputTexture;
    private int outputTexture;
    private int readFramebuffer;
    private int drawFramebuffer;
    private int width;
    private int height;
    private boolean failed;
    private boolean reported;
    private boolean registered;

    /**
     * 実カラーをGLとD3D11の共有テクスチャで往復コピーし、元のワールド色へ返す診断を実行する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param source 読み取り元の入力リソース
     */
    public void capture(RenderTarget source) {
        RenderSystem.assertOnRenderThread();
        if (failed || !(source.getColorTexture() instanceof GlTexture color)) return;
        try {
            prepare(source.width, source.height);
            GlStateManager.clearGlErrors();
            require(NativeBridge.lockFrameInput(), "input lock");
            try {
                blit(color.glId(), inputTexture, source.width, source.height);
                requireGl("input framebuffer blit");
            } finally {
                require(NativeBridge.unlockFrameInput(), "input unlock");
            }
            require(NativeBridge.consumeFrameTexture(), "D3D11 consume");
            require(NativeBridge.lockFrameOutput(), "output lock");
            try {
                blit(outputTexture, color.glId(), source.width, source.height);
                requireGl("output framebuffer blit");
            } finally {
                require(NativeBridge.unlockFrameOutput(), "output unlock");
            }
            if (!reported) {
                reported = true;
                TitaniumClient.LOGGER.info("[Titanium/P4B] Minecraft OpenGL color {}x{} -> D3D11 -> Minecraft OK; {}",
                        width, height, NativeBridge.frameDiagnostics());
            }
        } catch (RuntimeException error) {
            failed = true;
            TitaniumClient.LOGGER.error("[Titanium/P4] texture sharing disabled; Bilinear remains active", error);
        }
    }

    /**
     * 寸法の変化に合わせて診断用GL名とFBOを用意し、D3D11の入力・出力へ共有登録する。
     *
     * @param nextWidth 準備する幅。ピクセル単位
     * @param nextHeight 準備する高さ。ピクセル単位
     */
    private void prepare(int nextWidth, int nextHeight) {
        if (registered && width == nextWidth && height == nextHeight) return;
        release();
        width = nextWidth;
        height = nextHeight;
        inputTexture = GlStateManager._genTexture();
        outputTexture = GlStateManager._genTexture();
        try {
            require(NativeBridge.createFrameTextures(inputTexture, outputTexture, width, height), "create/register");
            registered = true;
        } catch (RuntimeException error) {
            NativeError cleanup = NativeBridge.releaseFrameTexture();
            if (cleanup == NativeError.OK) {
                GlStateManager._deleteTexture(inputTexture);
                GlStateManager._deleteTexture(outputTexture);
                inputTexture = 0;
                outputTexture = 0;
            } else {
                // Keep the GL names alive so native can retry unregister during close/shutdown.
                registered = true;
                error.addSuppressed(new IllegalStateException("create cleanup failed: " + cleanup));
            }
            throw error;
        }
        readFramebuffer = GlStateManager.glGenFramebuffers();
        drawFramebuffer = GlStateManager.glGenFramebuffers();
    }

    /**
     * 指定した色テクスチャ間を全領域コピーし、読み取り・書き込みFBOを元へ戻す。
     *
     * @param sourceTexture 読み取り元カラーのGLテクスチャ識別子
     * @param destinationTexture 書き込み先のGLテクスチャ識別子
     * @param sourceWidth 読み取り元の幅。ピクセル単位
     * @param sourceHeight 読み取り元の高さ。ピクセル単位
     */
    private void blit(int sourceTexture, int destinationTexture, int sourceWidth, int sourceHeight) {
        int previousRead = GlStateManager.getFrameBuffer(GL33C.GL_READ_FRAMEBUFFER);
        int previousDraw = GlStateManager.getFrameBuffer(GL33C.GL_DRAW_FRAMEBUFFER);
        try {
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, sourceTexture, 0);
            requireFramebuffer(GL33C.GL_READ_FRAMEBUFFER, "read");
            GlStateManager._glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_DRAW_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, destinationTexture, 0);
            requireFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, "draw");
            GlStateManager._glBlitFrameBuffer(0, 0, sourceWidth, sourceHeight, 0, 0, width, height,
                    GL33C.GL_COLOR_BUFFER_BIT, GL33C.GL_NEAREST);
        } finally {
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
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
            throw new IllegalStateException(label + " framebuffer incomplete: 0x" + Integer.toHexString(status));
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
            throw new IllegalStateException(operation + " GL error 0x" + Integer.toHexString(error));
        }
    }

    private static void require(NativeError result, String operation) {
        if (result != NativeError.OK) {
            throw new IllegalStateException(operation + " failed: " + result + "; " + NativeBridge.frameDiagnostics());
        }
    }

    public boolean passed() {
        return reported && !failed;
    }

    /**
     * 診断用FBOを破棄し、WGL登録の解除成功後に共有GL名を解放する。
     */
    private void release() {
        if (readFramebuffer != 0) GlStateManager._glDeleteFramebuffers(readFramebuffer);
        if (drawFramebuffer != 0) GlStateManager._glDeleteFramebuffers(drawFramebuffer);
        readFramebuffer = 0;
        drawFramebuffer = 0;
        if (registered) {
            require(NativeBridge.releaseFrameTexture(), "release");
            registered = false;
        }
        if (inputTexture != 0) GlStateManager._deleteTexture(inputTexture);
        if (outputTexture != 0) GlStateManager._deleteTexture(outputTexture);
        inputTexture = 0;
        outputTexture = 0;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        try {
            release();
        } catch (RuntimeException error) {
            TitaniumClient.LOGGER.error("[Titanium/P4] frame texture release failed", error);
        }
    }
}
