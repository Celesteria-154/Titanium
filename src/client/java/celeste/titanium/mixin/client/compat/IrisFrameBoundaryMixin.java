package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.TitaniumClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** パックの描画を中止・再実行せず、Irisの実パイプライン寿命を追跡する。 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class IrisFrameBoundaryMixin {
    /**
     * Iris開始の実パイプラインを記録し、必要な場合は履歴更新と2フレーム解析を開始する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "beginLevelRendering", at = @At("HEAD"), remap = false, require = 0)
    private void titanium$begin(CallbackInfo ci) {
        // 投影取得後のIris開始でパイプラインの同一性を確認し、必要な場合だけ前フレーム履歴を無効化する。
        TitaniumClient.pipeline().onIrisWorldBegin(this);
        celeste.titanium.client.render.enhanced.EnhancedRuntime.begin(this);
    }

    /**
     * Irisの最終出力完了を記録し、必要数の観測が揃った場合はEnhancedを検証する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "finalizeLevelRendering", at = @At("RETURN"), remap = false, require = 0)
    private void titanium$complete(CallbackInfo ci) {
        // Irisの最終出力まで完了した事実を記録し、同フレームのDLSS入力確定に使用する。
        TitaniumClient.pipeline().onIrisWorldComplete(this);
        celeste.titanium.client.render.enhanced.EnhancedRuntime.complete(this);
    }

    /**
     * Irisのパイプライン破棄を通知し、対応する旧履歴・認証・借用参照を破棄する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "destroy", at = @At("HEAD"), remap = false, require = 0)
    private void titanium$destroy(CallbackInfo ci) {
        TitaniumClient.pipeline().onIrisPipelineDestroyed(this);
        celeste.titanium.client.render.enhanced.EnhancedRuntime.destroyed(this);
    }
}
