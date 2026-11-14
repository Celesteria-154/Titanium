package celeste.titanium.client.render;

import net.fabricmc.loader.api.FabricLoader;

/** 旧診断経路の固定依存と対象パックを検証する。通常の製品パック選択には使用しない。 */
final class IrisSolasEnvironment {
    private static final String IRIS_VERSION = "1.11.2+mc26.2";
    private static final String SODIUM_VERSION = "0.9.1+mc26.2";
    private static final String SOLAS_PACK = "Solas Shader V3.7.zip";

    private IrisSolasEnvironment() {
    }

    static void requireInstalled(FabricLoader loader, String phase) {
        requireModVersion(loader, "iris", IRIS_VERSION, phase);
        requireModVersion(loader, "sodium", SODIUM_VERSION, phase);
    }

    /**
     * 旧表示診断のシェーダー使用・色空間・対象パック名を確認する。
     *
     * @param phase 旧診断を区別する工程識別名
     */
    static void requireActive(String phase) {
        try {
            Class<?> settings = Class.forName("net.irisshaders.iris.gui.option.IrisVideoSettings");
            Object colorSpace = settings.getField("colorSpace").get(null);
            if (!"SRGB".equals(String.valueOf(colorSpace))) {
                throw new IllegalStateException(phase + " requires Iris colorSpace=SRGB, found " + colorSpace);
            }
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            if (!Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(instance))) {
                throw new IllegalStateException(phase + " requires an active Iris shader pack");
            }
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object packName = iris.getMethod("getCurrentPackName").invoke(null);
            if (!SOLAS_PACK.equals(String.valueOf(packName))) {
                throw new IllegalStateException(phase + " requires " + SOLAS_PACK + ", found " + packName);
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(phase + " could not verify Iris display state", error);
        }
    }

    private static void requireModVersion(FabricLoader loader, String id, String expected, String phase) {
        String actual = loader.getModContainer(id)
                .orElseThrow(() -> new IllegalStateException(phase + " requires " + id))
                .getMetadata().getVersion().getFriendlyString();
        if (!expected.equals(actual)) {
            throw new IllegalStateException(phase + " requires " + id + " " + expected + ", found " + actual);
        }
    }
}
