package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.TitaniumClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
abstract class SodiumTerrainDetailBindMixin {
    // Irisが共有地形サンプラーを置き換えた後のバインド位置で適用する。
    /**
     * 実際の地形テクスチャバインド時に、現在の描画区間へ適した所有サンプラーを選ぶ。
     *
     * @param pass 対象の実描画pass
     * @param name 対象または診断区間を識別する名前
     * @param texture 参照する物理テクスチャまたはそのビュー
     * @param sampler 対象のサンプラー
     * @param original Mixin適用前の値または元の描画処理
     */
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V"), require = 1)
    private void titanium$terrainDetail(RenderPass pass, String name, GpuTextureView texture,
            GpuSampler sampler, Operation<Void> original) {
        original.call(pass, name, texture, TitaniumClient.pipeline().terrainAtlasSampler(name, sampler));
    }
}
