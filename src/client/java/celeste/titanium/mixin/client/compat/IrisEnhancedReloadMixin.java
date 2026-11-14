package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.render.enhanced.EnhancedRuntime;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** パイプライン構築失敗時も、前回のシェーダー変換状態を解放する。 */
@Pseudo
@Mixin(targets="net.irisshaders.iris.Iris",remap=false)
abstract class IrisEnhancedReloadMixin {
    /**
     * Irisの再読み込み開始前に、旧Enhancedの認証と借用状態を破棄する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="reload",at=@At("HEAD"),require=0)
    private static void titanium$reload(CallbackInfo ci){EnhancedRuntime.beforeReload();}
}
