package celeste.titanium.mixin.client;

import celeste.titanium.client.TitaniumClient;
import celeste.titanium.client.nativebridge.NativeBridge;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import celeste.titanium.client.render.GuiFramePacing;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * 設定が有効な場合に前回送信の完了を確認してから、元のGUI描画を実行する。
     *
     * @param gui 元のGUI描画を実行するrenderer
     * @param original Mixin適用前の値または元の描画処理
     */
    @WrapOperation(method="render(Lnet/minecraft/client/DeltaTracker;Z)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void titanium$paceGui(GuiRenderer gui,Operation<Void> original) {
        // GUIを描く直前へ前回送信の待機を移す。設定が無効なら通常のGUI描画へ進む。
        GuiFramePacing.beforeGui();
        original.call(gui);
    }
    @Shadow @Final private Projection hudProjection;
    @Shadow @Final private ProjectionMatrixBuffer hud3dProjectionMatrixBuffer;
    private static final String CLEAR_DEPTH = "Lcom/mojang/blaze3d/systems/CommandEncoder;"
            + "clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V";
    // HEAD/RETURN と同じ区間を try/finally で囲み、例外時の target 置き去りを防ぐ。
    /**
     * ワールド描画全体をスコープ化し、解像度切り替えと状態返却をパイプラインへ委譲する。
     *
     * @param delta 現在フレームのMinecraft時間・補間情報
     * @param original Mixin適用前の値または元の描画処理
     */
    @WrapMethod(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V")
    private void titanium$renderWorld(DeltaTracker delta, Operation<Void> original) {
        celeste.titanium.client.render.enhanced.EnhancedShaderRuntime.beginFrame();
        TitaniumClient.pipeline().renderLevel((GameRenderer) (Object) this, delta, () -> original.call(delta));
    }

    /**
     * 実描画へアップロードする投影行列へ、そのフレームのサンプル位置を適用する。
     *
     * @param delta 現在フレームのMinecraft時間・補間情報
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     * @param projection 対象フレームの投影行列
     */
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;"
                    + "getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private void titanium$prepareWorldProjection(DeltaTracker delta, CallbackInfo ci,
                                                 @Local(ordinal = 0) Matrix4f projection) {
        // Mutate the call-site local before any getBuffer wrapper copies it (Sodium/Iris).
        // Replacing only a wrapper argument lets terrain and the uploaded projection disagree.
        projection.set(TitaniumClient.pipeline().prepareFrameProjection(projection));
    }

    /**
     * ワールド深度がクリアされる前にDLSSを完了し、必要な場合は手用投影を出力解像度へ戻す。
     *
     * @param delta 現在フレームのMinecraft時間・補間情報
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = CLEAR_DEPTH, shift = At.Shift.BEFORE))
    private void titanium$captureDepthBeforeHand(DeltaTracker delta, CallbackInfo ci) {
        GameRenderer renderer = (GameRenderer) (Object) this;
        if (TitaniumClient.pipeline().captureDepthBeforeHand(renderer.mainRenderTarget(), renderer)) {
            CameraRenderState camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
            var window = renderer.gameRenderState().windowRenderState;
            // ワールド拡大後の手は出力解像度で描画するため、HUD用FOVと現在の画面寸法で投影を設定し直す。
            hudProjection.setupPerspective(0.05F, 100.0F, camera.hudFov, window.width, window.height);
            RenderSystem.setProjectionMatrix(hud3dProjectionMatrixBuffer.getBuffer(hudProjection),
                    ProjectionType.PERSPECTIVE);
        }
    }

    /**
     * 深度クリア直後の同じターゲットを端点診断へ渡す。
     *
     * @param delta 現在フレームのMinecraft時間・補間情報
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = CLEAR_DEPTH, shift = At.Shift.AFTER))
    private void titanium$captureClearedDepth(DeltaTracker delta, CallbackInfo ci) {
        GameRenderer renderer = (GameRenderer) (Object) this;
        TitaniumClient.pipeline().captureDepthAfterClear(renderer.mainRenderTarget());
    }

    /**
     * 実際のmainターゲットから描画バックエンドの初期判定を行う。
     *
     * @param delta 現在フレームのMinecraft時間・補間情報
     * @param advanceGameTime Minecraft側のゲーム時間更新フラグ
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void titanium$checkRenderer(DeltaTracker delta, boolean advanceGameTime, CallbackInfo ci) {
        TitaniumClient.pipeline().checkBackend(((GameRenderer) (Object) this).mainRenderTarget());
    }

    /**
     * rendererデータの再設定前に、GUIフェンスと時間方向履歴をリセットする。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "resetData()V", at = @At("HEAD"))
    private void titanium$breakFrameHistory(CallbackInfo ci) {
        GuiFramePacing.close();
        TitaniumClient.pipeline().breakFrameHistory();
    }

    /**
     * renderer終了時にGL側の所有資源を解放し、その後ネイティブを終了する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "close()V", at = @At("HEAD"))
    private void titanium$releaseTargets(CallbackInfo ci) {
        GuiFramePacing.close();
        try {
            TitaniumClient.pipeline().close();
        } finally {
            // Java側の共有登録を先に解放し、最後にネイティブのNGX・D3D11を終了する。
            NativeBridge.shutdown();
        }
    }
}
