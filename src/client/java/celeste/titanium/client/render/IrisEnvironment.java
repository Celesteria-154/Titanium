package celeste.titanium.client.render;

import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Iris連携の対応バージョンと実行条件を検証する。 */
final class IrisEnvironment {
    private static Access cachedAccess;
    private IrisEnvironment() {}

    static void requireInstalled(FabricLoader loader, String phase) {
        requireVersion(loader, "iris", "1.11.2+mc26.2", phase);
        requireVersion(loader, "sodium", "0.9.1+mc26.2", phase);
    }

    /**
     * Iris APIから実シェーダー使用状態と色空間を取得し、対応条件を確認する。
     *
     * @param phase 旧診断を区別する工程識別名
     */
    static void requireActive(String phase) {
        try {
            Access access = cachedAccess;
            if (access == null) cachedAccess = access = Access.load();
            requireDisplay(Boolean.TRUE.equals(access.active.invoke(access.api)),
                    String.valueOf(access.colorSpace.get(null)), phase);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(phase + " could not verify Iris display state", error);
        }
    }

    static void requireDisplay(boolean shaderActive, String colorSpace, String phase) {
        if (!shaderActive) throw new IllegalStateException(phase + " requires an active Iris shader pack");
        if (!"SRGB".equals(colorSpace))
            throw new IllegalStateException(phase + " requires Iris colorSpace=SRGB, found " + colorSpace);
    }

    private static void requireVersion(FabricLoader loader, String id, String expected, String phase) {
        String actual = loader.getModContainer(id)
                .orElseThrow(() -> new IllegalStateException(phase + " requires " + id))
                .getMetadata().getVersion().getFriendlyString();
        if (!expected.equals(actual))
            throw new IllegalStateException(phase + " requires " + id + " " + expected + ", found " + actual);
    }

    private record Access(Object api, Method active, Field colorSpace) {
        static Access load() {
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                return new Access(api.getMethod("getInstance").invoke(null),
                        api.getMethod("isShaderPackInUse"),
                        Class.forName("net.irisshaders.iris.gui.option.IrisVideoSettings").getField("colorSpace"));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Iris integration API is unavailable", error);
            }
        }
    }
}
