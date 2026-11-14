package celeste.titanium.mixin.client.compat;

import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** main targetのobject切替をversion値だけでは見落とすIris 1.11.2を補正する。 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.targets.RenderTargets", remap = false)
abstract class IrisRenderTargetsMixin {
    @Shadow(remap = false)
    private GpuTexture currentDepthTexture;

    @Shadow(remap = false)
    private int cachedDepthBufferVersion;

    /**
     * 同じ版番号で深度オブジェクトが切り替わった場合、Irisが実ターゲットへ再接続するよう促す。
     *
     * @param newDepthBufferVersion Irisへ通知された新しい深度バッファの版番号
     * @param newDepthTexture 新しく参照する実深度テクスチャ
     * @param newWidth 変更後の入力幅。ピクセル単位
     * @param newHeight 変更後の入力高さ。ピクセル単位
     * @param newDepthFormat Irisへ渡される新しい深度形式
     * @param packDirectives 元のIris処理へ渡すパックの描画指示
     * @param callbackInfo 元メソッドの戻り値を扱うMixinコールバック
     */
    @Inject(method = "resizeIfNeeded", at = @At("HEAD"), remap = false, require = 0)
    private void titanium$detectDepthTargetChange(int newDepthBufferVersion, GpuTexture newDepthTexture,
            int newWidth, int newHeight, @Coerce Object newDepthFormat, @Coerce Object packDirectives,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        // ターゲットのオブジェクトが変わったのに版番号が同じ場合、版番号をずらしてIrisの再接続を促す。
        if (currentDepthTexture != newDepthTexture && cachedDepthBufferVersion == newDepthBufferVersion) {
            cachedDepthBufferVersion = newDepthBufferVersion == Integer.MIN_VALUE
                    ? Integer.MAX_VALUE
                    : newDepthBufferVersion - 1;
        }
    }
}
