package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.render.enhanced.EnhancedRuntime;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.FinalPassRenderer", remap=false)
abstract class IrisEnhancedFinalMixin {
    /**
     * Irisの最終rendererの観測区間を開始する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="renderFinalPass", at=@At("HEAD"), remap=false, require=0)
    private void titanium$batch(CallbackInfo ci) { if (EnhancedRuntime.observing()) EnhancedRuntime.beginBatch(this); }
    /**
     * 最終drawの実入力・出力を観測する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="renderFinalPass", at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIIII)V"), remap=false, require=0)
    private void titanium$pre(CallbackInfo ci) { if (EnhancedRuntime.observing()) EnhancedRuntime.beforeDraw(this); }
    /**
     * 最終drawを確定し、任意の後段処理で再利用する形状とバインドを取得する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="renderFinalPass", at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIIII)V",shift=At.Shift.AFTER), remap=false, require=0)
    private void titanium$post(CallbackInfo ci) { if (EnhancedRuntime.observing()) EnhancedRuntime.afterDraw(this);if(celeste.titanium.client.render.EnhancedFinalPostProcess.requested())celeste.titanium.client.render.EnhancedFinalPostProcess.afterFinalDraw(); }
}
