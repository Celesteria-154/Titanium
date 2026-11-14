package celeste.titanium.mixin.client;

import celeste.titanium.client.render.GuiFramePacing;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class GuiPacingSubmitMixin {
    /**
     * 元のコマンド送信を維持し、設定が有効な場合だけ次回GUI待機用のフェンスを登録する。
     *
     * @param encoder 現在のコマンド列を管理するエンコーダー
     * @param original Mixin適用前の値または元の描画処理
     */
    @WrapOperation(method="renderFrame(Z)V",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/CommandEncoder;submit()V"))
    private void titanium$guiPacingSubmit(CommandEncoder encoder,Operation<Void> original) {
        GuiFramePacing.submit(encoder,()->original.call(encoder));
    }
}
