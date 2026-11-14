package celeste.titanium.mixin.client;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** 互換性・明示デバッグ用Mixinを、必要な構成だけへ適用する。 */
public final class TitaniumMixinPlugin implements IMixinConfigPlugin {
    private static final String IRIS_MIXIN_SUFFIX = ".compat.IrisRenderTargetsMixin";
    private static final String TERRAIN_LOD_MIXIN_SUFFIX = ".ChunkSectionsToRenderMixin";

    /**
     * Iris・Sodiumの導入状況から、各連携Mixinを適用すべきか判定する。
     *
     * @param targetClassName Mixin適用対象のクラス名
     * @param mixinClassName 適用するMixinのクラス名
     * @return Iris・Sodiumの導入状況から、各連携Mixinを適用すべき場合はtrue
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        FabricLoader loader = FabricLoader.getInstance();
        if (mixinClassName.endsWith(".compat.SodiumTerrainDetailScopeMixin")
                || mixinClassName.endsWith(".compat.SodiumTerrainDetailBindMixin")) {
            return loader.isModLoaded("sodium");
        }
        if (mixinClassName.endsWith(IRIS_MIXIN_SUFFIX)
                || mixinClassName.endsWith(".compat.IrisFrameBoundaryMixin")
                || mixinClassName.endsWith(".compat.IrisEnhancedCompositeMixin")
                || mixinClassName.endsWith(".compat.EnhancedGlShaderMixin")
                || mixinClassName.endsWith(".compat.IrisEnhancedShaderSourceMixin")
                || mixinClassName.endsWith(".compat.IrisEnhancedHandMixin")
                || mixinClassName.endsWith(".compat.IrisEnhancedReloadMixin")
                || mixinClassName.endsWith(".compat.IrisEnhancedFinalMixin")) {
            return loader.isModLoaded("iris");
        }
        if (mixinClassName.endsWith(TERRAIN_LOD_MIXIN_SUFFIX)) {
            return !loader.isModLoaded("iris") && !loader.isModLoaded("sodium");
        }
        return true;
    }

    @Override public void onLoad(String mixinPackage) { }
    /**
     * 追加のrefmapを指定せず、Mixin設定の既定動作を使用する。
     *
     * @return 追加refmapを指定しないnull
     */
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    /**
     * 動的なMixin追加を行わず、設定ファイルに宣言された一覧を使用する。
     *
     * @return 動的な追加Mixinを指定しないnull
     */
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass,
            String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass,
            String mixinClassName, IMixinInfo mixinInfo) { }
}
