package celeste.titanium.client.render;

import celeste.titanium.client.TitaniumClient;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Irisの影描画範囲を識別し、地形サンプリング補正の適用対象から除外する。 */
final class IrisShadowScope {
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");
    private static final MethodHandle SHADOW = findShadowQuery();
    private static boolean failed;

    /**
     * 対応するIrisの影描画判定をMethodHandleとして取得する。
     *
     * @return Irisの実影描画状態を取得するハンドル。非対応時はnull
     */
    private static MethodHandle findShadowQuery() {
        if (!IRIS) return null;
        try {
            return MethodHandles.publicLookup().findStatic(
                    Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState"),
                    "areShadowsCurrentlyBeingRendered", MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException error) {
            TitaniumClient.LOGGER.warn("[Titanium] Cannot exclude Iris shadows; terrain detail compensation unavailable", error);
            return null;
        }
    }

    /**
     * 影描画中または影判定を取得できないために地形補正を除外すべきか返す。
     *
     * @return 影描画中または影判定を取得できないために地形補正を除外すべき場合はtrue
     */
    static boolean excluded() {
        if (!IRIS) return false;
        if (SHADOW == null || failed) return true;
        try { return (boolean) SHADOW.invokeExact(); }
        catch (Throwable error) {
            failed = true;
            TitaniumClient.LOGGER.warn("[Titanium] Iris shadow query failed; terrain detail compensation unavailable", error);
            return true;
        }
    }
}
