package celeste.titanium.client.render;

import celeste.titanium.client.nativebridge.NativeBridge;
import celeste.titanium.client.nativebridge.NativeError;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL33C;

import java.util.Optional;

/** 描画スレッドで共有D3D11入力へ転送するカラー・深度・MVのリソースを所有する。 */
final class FrameInteropInput implements AutoCloseable {
    private static final BindGroupLayout DEPTH_LAYOUT = BindGroupLayout.builder()
            .withSampler("DepthSampler")
            .build();
    private static final RenderPipeline DEPTH_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("titanium", "pipeline/depth_to_r32f"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("titanium", "core/depth_to_r32f"))
            .withBindGroupLayout(DEPTH_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT,
                    ColorTargetState.WRITE_COLOR))
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    private GpuTexture depthFloat;
    private GpuTextureView depthFloatView;
    private GpuTextureView sourceDepthView;
    private GpuSampler depthSampler;
    private int colorTexture;
    private int depthTexture;
    private int motionTexture;
    private int readFramebuffer;
    private int drawFramebuffer;
    private int width;
    private int height;
    private long registrations;
    private boolean registered;
    private boolean recreateRequested = true;
    private final boolean validationEnabled;

    /**
     * 共有入力の読み戻し検証を追加するかを保持する。通常の製品入力では検証用資源を使わない。
     *
     * @param validationEnabled 追加のGPU読み戻し診断を生成する場合はtrue
     */
    FrameInteropInput(boolean validationEnabled) {
        this.validationEnabled = validationEnabled;
    }

    /**
     * 現在の登録が有効で、入力寸法と借用深度オブジェクトも一致するか判定する。
     *
     * @param target 操作対象の描画先またはリソース
     * @param sourceDepth ワールド入力と同じフレームのGL深度テクスチャ
     * @return 現在の登録が有効で、入力寸法と借用深度オブジェクトも一致する場合はtrue
     */
    boolean matches(RenderTarget target, GlTexture sourceDepth) {
        return !recreateRequested && registered && width == target.width && height == target.height
                && sourceDepthView != null && sourceDepthView.texture() == sourceDepth;
    }

    /**
     * 深度変換用のR32Fと入力共有GL名を確保し、D3D11側の色・深度・MVへ登録する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param target 操作対象の描画先またはリソース
     * @param sourceDepth ワールド入力と同じフレームのGL深度テクスチャ
     */
    void prepare(RenderTarget target, GlTexture sourceDepth) {
        RenderSystem.assertOnRenderThread();
        if (registered || depthFloat != null || colorTexture != 0) {
            throw new IllegalStateException("frame input must be released before recreation");
        }
        boolean nativeCreateAttempted = false;
        try {
            width = target.width;
            height = target.height;
            var device = RenderSystem.getDevice();
            // 深度をサンプリング可能なR32Fの色へ変換し、カラー・MVと同じWGL転送方式で扱う。
            depthFloat = device.createTexture(() -> "Titanium P8 raw depth R32F",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.R32_FLOAT, width, height, 1, 1);
            depthFloatView = device.createTextureView(depthFloat);
            sourceDepthView = device.createTextureView(sourceDepth);
            depthSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, java.util.OptionalDouble.empty());
            colorTexture = GlStateManager._genTexture();
            depthTexture = GlStateManager._genTexture();
            motionTexture = GlStateManager._genTexture();
            // GL名を先に確保し、D3D11側で生成する3枚の共有テクスチャへ登録する。
            nativeCreateAttempted = true;
            require(NativeBridge.createFrameInterop(
                    colorTexture, depthTexture, motionTexture, width, height, validationEnabled),
                    "create/register");
            registered = true;
            registrations++;
            readFramebuffer = GlStateManager.glGenFramebuffers();
            drawFramebuffer = GlStateManager.glGenFramebuffers();
            recreateRequested = false;
        } catch (RuntimeException error) {
            // 途中失敗でも登録解除の成否を確認する。解除できないGL名は保持し、所有者不明のまま破棄しない。
            NativeError cleanup = nativeCreateAttempted ? NativeBridge.releaseFrameInterop() : NativeError.OK;
            if (cleanup == NativeError.OK) {
                registered = false;
                deleteSharedNames();
                closeGlResources();
            } else {
                registered = true;
                error.addSuppressed(new IllegalStateException("frame input cleanup failed: " + cleanup));
            }
            throw error;
        }
    }

    /**
     * 深度をR32Fへ変換し、入力3枚をGL所有中に転送した後、番号とジッターを付けてD3D11へ返す。
     *
     * 描画スレッドから呼び出す。
     *
     * GLロック中に3枚をコピーし、finallyで同じシーケンスのメタデータとともにD3D11へ返す。
     *
     * @param sourceColor 同一フレームのカラー入力GL識別子
     * @param sourceMotion 同一フレームのMV入力GL識別子
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param validationKind 読み戻し診断の種類。0は診断なし
     */
    void transfer(int sourceColor, int sourceMotion, FrameInputPacket packet, int validationKind) {
        RenderSystem.assertOnRenderThread();
        if (!registered) throw new IllegalStateException("frame input is not registered");
        convertDepth();
        if (!(depthFloat instanceof GlTexture convertedDepth)) {
            throw new IllegalStateException("P8 requires an OpenGL R32F texture");
        }
        GlStateManager.clearGlErrors();
        // 共有テクスチャの所有権をGLへ移してから、同一フレームの色・深度・MVをまとめて書く。
        require(NativeBridge.lockFrameInterop(), "frame lock");
        boolean scissorEnabled = GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST);
        try {
            // These are complete input copies, not world/GUI draws. A caller's clip box
            // must not leave stale color, depth or motion in the shared textures.
            // Restore the raw state so Blaze3D's cached state stays consistent.
            if (scissorEnabled) GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
            blit(sourceColor, colorTexture);
            blit(convertedDepth.glId(), depthTexture);
            blit(sourceMotion, motionTexture);
            requireGl("shared frame writes");
        } finally {
            if (scissorEnabled) GL33C.glEnable(GL33C.GL_SCISSOR_TEST);
            // コピー後はD3D11へ所有権を返し、入力に対応するシーケンスとジッター情報を同時に確定する。
            FrameJitter jitter = packet.jitter();
            require(NativeBridge.unlockFrameInterop(packet.sequence(), packet.reset(),
                    jitter.pixelX(), jitter.pixelY(), jitter.phase(), jitter.phaseCount(), jitter.enabled(),
                    validationKind), "frame unlock");
        }
    }

    /**
     * ワールドの深度サンプル値を線形化せず、共有転送用R32Fへ全画面描画する。
     */
    private void convertDepth() {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "Titanium P8 raw depth conversion",
                depthFloatView, Optional.empty())) {
            // 深度の値は線形化せず、そのままR32Fへ格納する。NGXへ渡す向きはFeature側で宣言する。
            pass.setPipeline(DEPTH_PIPELINE);
            pass.bindTexture("DepthSampler", sourceDepthView, depthSampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * 読み取り・書き込みFBOを一時接続し、入力画像の上下を反転して共有テクスチャへコピーする。
     *
     * @param source 読み取り元のGLテクスチャ識別子
     * @param destination 書き込み先のGLテクスチャ識別子
     */
    private void blit(int source, int destination) {
        int previousRead = GlStateManager.getFrameBuffer(GL33C.GL_READ_FRAMEBUFFER);
        int previousDraw = GlStateManager.getFrameBuffer(GL33C.GL_DRAW_FRAMEBUFFER);
        try {
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_READ_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, source, 0);
            requireFramebuffer(GL33C.GL_READ_FRAMEBUFFER, "read");
            GlStateManager._glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL33C.GL_DRAW_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, destination, 0);
            requireFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, "draw");
            // WGL shares row indices, not screen origins: GL row 0 is the bottom,
            // D3D row 0 is the top. Flip all inputs together; MV values are already top-left.
            GlStateManager._glBlitFrameBuffer(0, height, width, 0, 0, 0, width, height,
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
            throw new IllegalStateException(operation + " failed: " + result + "; "
                    + NativeBridge.frameInteropDiagnostics());
        }
    }

    boolean registered() {
        return registered;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    long registrations() {
        return registrations;
    }

    void requestRecreate() {
        recreateRequested = true;
    }

    /**
     * 登録解除済みのカラー・深度・MVのGLテクスチャ名を削除する。
     */
    private void deleteSharedNames() {
        if (colorTexture != 0) GlStateManager._deleteTexture(colorTexture);
        if (depthTexture != 0) GlStateManager._deleteTexture(depthTexture);
        if (motionTexture != 0) GlStateManager._deleteTexture(motionTexture);
        colorTexture = 0;
        depthTexture = 0;
        motionTexture = 0;
    }

    /**
     * 所有する深度変換用テクスチャ・ビュー・サンプラーとFBOを解放する。
     */
    private void closeGlResources() {
        if (readFramebuffer != 0) GlStateManager._glDeleteFramebuffers(readFramebuffer);
        if (drawFramebuffer != 0) GlStateManager._glDeleteFramebuffers(drawFramebuffer);
        readFramebuffer = 0;
        drawFramebuffer = 0;
        if (depthSampler != null) depthSampler.close();
        if (sourceDepthView != null) sourceDepthView.close();
        if (depthFloatView != null) depthFloatView.close();
        if (depthFloat != null) depthFloat.close();
        depthSampler = null;
        sourceDepthView = null;
        depthFloatView = null;
        depthFloat = null;
        width = 0;
        height = 0;
    }

    /**
     * 所有するリソースと保留状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (registered) {
            require(NativeBridge.releaseFrameInterop(), "release");
            registered = false;
        }
        deleteSharedNames();
        closeGlResources();
    }
}
