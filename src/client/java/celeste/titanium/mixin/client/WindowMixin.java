package celeste.titanium.mixin.client;

import celeste.titanium.client.TitaniumClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Window.class)
public abstract class WindowMixin {
    /**
     * ワールド描画区間では入力幅を、それ以外では元のWindow幅を返す。
     *
     * @param original 変更前のWindow幅または高さ
     * @return ワールド描画中は入力幅、それ以外では元のWindow幅
     */
    @ModifyReturnValue(method = "getWidth()I", at = @At("RETURN"))
    private int titanium$worldWidth(int original) {
        // ワールド描画中の画面寸法問い合わせを入力解像度へ合わせる。範囲外では元の画面寸法を返す。
        return TitaniumClient.pipeline().windowWidth(original);
    }

    /**
     * ワールド描画区間では入力高さを、それ以外では元のWindow高さを返す。
     *
     * @param original 変更前のWindow幅または高さ
     * @return ワールド描画中は入力高さ、それ以外では元のWindow高さ
     */
    @ModifyReturnValue(method = "getHeight()I", at = @At("RETURN"))
    private int titanium$worldHeight(int original) {
        return TitaniumClient.pipeline().windowHeight(original);
    }
}
