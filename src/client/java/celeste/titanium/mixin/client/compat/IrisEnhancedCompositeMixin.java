package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.render.enhanced.EnhancedRuntime;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 描画構造の確認後、対応する合成パスで早期DLAAとTAA返却を実行する。 */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.CompositeRenderer", remap=false)
abstract class IrisEnhancedCompositeMixin {
    /**
     * 観測中の合成rendererを新しい描画区間として登録する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="renderAll", at=@At("HEAD"), remap=false, require=0)
    private void titanium$batch(CallbackInfo ci) { if (EnhancedRuntime.observing()) EnhancedRuntime.beginBatch(this); }
    /**
     * 合成draw直前の実状態を観測し、認証済みの境界では早期DLAAを実行する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="renderAll", at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/opengl/GlStateManager;_drawElements(IIIJ)V"), remap=false, require=0)
    private void titanium$pre(CallbackInfo ci) {
        // 実draw直前のプログラム・FBO・入力を観測し、対応するTAA境界ならDLAAを先に実行する。
        if (EnhancedRuntime.observing()) EnhancedRuntime.beforeDraw(this);
        if(celeste.titanium.client.render.enhanced.EnhancedShaderRuntime.active())
            celeste.titanium.client.TitaniumClient.pipeline().tryEnhancedPreAaDlaa();
    }
    /**
     * メインTAA直後のCUTOUT合成を行い、観測したdrawの状態を確定する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="renderAll", at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/opengl/GlStateManager;_drawElements(IIIJ)V",shift=At.Shift.AFTER), remap=false, require=0)
    private void titanium$post(CallbackInfo ci) {
        // 元のTAAが書いた色を使ってCUTOUT合成を完了し、その後で描画前後の整合性を記録する。
        celeste.titanium.client.TitaniumClient.pipeline().returnEnhancedCutoutTaa();
        if (EnhancedRuntime.observing()) EnhancedRuntime.afterDraw(this);
    }
}
