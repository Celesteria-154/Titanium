package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.TitaniumClient;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
abstract class SodiumTerrainDetailScopeMixin {
    private static final Object titanium$solidPass = titanium$resolveSolidPass();
    private static final Object titanium$cutoutPass = titanium$resolveCutoutPass();

    /**
     * SodiumのCUTOUT描画を識別する実passオブジェクトを反射で取得する。
     *
     * @return 実CUTOUT passの共有オブジェクト。非対応時はnull
     */
    private static Object titanium$resolveCutoutPass() {
        try {
            return Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses")
                    .getField("CUTOUT").get(null);
        } catch (ReflectiveOperationException | LinkageError error) {
            TitaniumClient.LOGGER.warn("[Titanium] Sodium CUTOUT identity unavailable; resolution mip compensation excluded", error);
            return null;
        }
    }

    /**
     * SodiumのSOLID描画を識別する実passオブジェクトを反射で取得する。
     *
     * @return 実SOLID passの共有オブジェクト。非対応時はnull
     */
    private static Object titanium$resolveSolidPass() {
        try {
            return Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses")
                    .getField("SOLID").get(null);
        } catch (ReflectiveOperationException | LinkageError error) {
            TitaniumClient.LOGGER.warn("[Titanium] Sodium SOLID identity unavailable; anisotropy disabled", error);
            return null;
        }
    }

    /**
     * 実passに応じたAF・LOD・CUTOUT深度の区間を開始し、終了時に必ず元の状態へ戻す。
     *
     * @param matrices Sodiumの元描画へ渡す行列群
     * @param pass 対象の実描画pass
     * @param x X座標またはX方向成分
     * @param y Y座標またはY方向成分
     * @param z 元の地形描画へ引き継ぐカメラZ座標
     * @param fog 元描画へ引き継ぐ霧の設定またはuniform参照
     * @param sampler 対象のサンプラー
     * @param original Mixin適用前の値または元の描画処理
     */
    @WrapMethod(method = "renderLayer")
    private void titanium$solidScope(@Coerce Object matrices, @Coerce Object pass,
            double x, double y, double z, @Coerce Object fog, GpuSampler sampler, Operation<Void> original) {
        var pipeline = TitaniumClient.pipeline();
        // Sodiumの実passオブジェクトでSOLID・CUTOUTを識別し、それぞれに許可された補正範囲を開始する。
        boolean scoped = pipeline.beginSolidTerrainDetail(titanium$solidPass != null && pass == titanium$solidPass);
        boolean resolution = pipeline.beginIrisResolutionDetail(
                titanium$solidPass != null && pass == titanium$solidPass
                || titanium$cutoutPass != null && pass == titanium$cutoutPass);
        boolean cutout=false;
        // CUTOUT深度の取得は影マップを除くメイン描画に限定する。
        if(pass==titanium$cutoutPass&&celeste.titanium.client.render.enhanced.EnhancedShaderRuntime.active()){
            try{cutout=!(boolean)Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState").getMethod("areShadowsCurrentlyBeingRendered").invoke(null);}
            catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot resolve Iris terrain stage",e);}
        }
        celeste.titanium.client.render.enhanced.EnhancedCutoutTaa.enterCutout(cutout);
        try { original.call(matrices, pass, x, y, z, fog, sampler); }
        finally {
            // 描画が例外で中断しても、追加アタッチメントと地形補正スコープを必ず終了する。
            celeste.titanium.client.render.enhanced.EnhancedCutoutTaa.leaveCutout();
            if (resolution) pipeline.endIrisResolutionDetail();
            if (scoped) pipeline.endSolidTerrainDetail();
        }
    }

    /**
     * 地形のまとまった描画区間へLOD補正を限定し、終了時にスコープを解除する。
     *
     * @param group Minecraftが描画するチャンクレイヤーの分類
     * @param matrices Sodiumの元描画へ渡す行列群
     * @param x X座標またはX方向成分
     * @param y Y座標またはY方向成分
     * @param z 元の地形描画へ引き継ぐカメラZ座標
     * @param sampler 対象のサンプラー
     * @param original Mixin適用前の値または元の描画処理
     */
    @WrapMethod(method = "drawChunkLayer")
    private void titanium$terrainDetailScope(ChunkSectionLayerGroup group, @Coerce Object matrices,
            double x, double y, double z, GpuSampler sampler, Operation<Void> original) {
        var pipeline = TitaniumClient.pipeline();
        boolean scoped = pipeline.beginTerrainDetail();
        try { original.call(group, matrices, x, y, z, sampler); }
        finally { if (scoped) pipeline.endTerrainDetail(); }
    }
}
