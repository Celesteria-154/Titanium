package celeste.titanium.mixin.client;

import celeste.titanium.client.TitaniumClient;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Mixin;

/** 通常の地形描画に、所有サンプラーまたは明示的な診断用補正を適用する。 */
@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {
    /**
     * 通常地形の描画区間にだけLOD補正サンプラーを適用し、終了後に共有状態を戻す。
     *
     * @param group Minecraftが描画するチャンクレイヤーの分類
     * @param sampler 対象のサンプラー
     * @param original Mixin適用前の値または元の描画処理
     */
    @WrapMethod(method = "renderGroup")
    private void titanium$withTerrainLodBias(ChunkSectionLayerGroup group, GpuSampler sampler,
                                             Operation<Void> original) {
        var pipeline = TitaniumClient.pipeline();
        if (pipeline.beginTerrainDetail()) {
            try { original.call(group, pipeline.terrainAtlasSampler("u_BlockTex", sampler)); }
            finally { pipeline.endTerrainDetail(); }
            return;
        }
        float bias = TitaniumClient.pipeline().terrainLodBias();
        if (!(sampler instanceof GlSampler glSampler) || bias == 0.0F) {
            original.call(group, sampler);
            return;
        }

        GL33C.glSamplerParameterf(glSampler.getId(), GL33C.GL_TEXTURE_LOD_BIAS, bias);
        try {
            original.call(group, sampler);
        } finally {
            // chunkLayerSamplerはLevelRenderer専有。次の描画対象へ状態を漏らさない。
            GL33C.glSamplerParameterf(glSampler.getId(), GL33C.GL_TEXTURE_LOD_BIAS, 0.0F);
        }
    }
}
