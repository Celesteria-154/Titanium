package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.render.enhanced.EnhancedCutoutTaa;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** IrisのuniformとFBOが確定した、キュー内の実地形描画へ接続する。 */
@Mixin(targets="com.mojang.blaze3d.opengl.GlCommandEncoder")
abstract class EnhancedCutoutDrawMixin {
    // キューの実行で描画状態が確定した位置に限定し、元の地形描画へCUTOUT深度の追加出力を接続する。
    /**
     * キュー内の実地形draw直前に、CUTOUT採用深度を追加出力へ接続する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="executeDraws",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL33C;nglMultiDrawElementsBaseVertex(IJIJIJ)V"))
    private void titanium$cutout(CallbackInfo ci){EnhancedCutoutTaa.beforeDraw();}
}
