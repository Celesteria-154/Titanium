package celeste.titanium.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    // 26.2 の SkyRenderer は生成時の target を保持する。Off 切替・再入場後も現在の描画先へ追従させる。
    /**
     * 空の描画時に、生成時の固定参照を現在のmainターゲットへ差し替える。
     *
     * @param original 通常の出力先または差し替え前の描画先
     * @return 現在のGameRendererが使用するmainターゲット
     */
    @ModifyExpressionValue(method = {"renderSkyDisc", "renderDarkDisc", "renderSun", "renderMoon",
            "renderStars", "renderSunriseAndSunset", "renderEndSky", "renderEndFlash"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget titanium$currentSkyTarget(RenderTarget original) {
        // 空も現在のワールド描画先へ追従させ、元解像度の深度ターゲットとの混在を防ぐ。
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }
}
