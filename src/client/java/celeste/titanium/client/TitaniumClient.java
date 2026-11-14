package celeste.titanium.client;

import net.fabricmc.api.ClientModInitializer;
import celeste.titanium.client.config.TitaniumConfig;
import celeste.titanium.client.render.UpscalePipeline;
import celeste.titanium.client.nativebridge.NativeBridge;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** 設定・キー割り当て・クライアントコマンドを初期化し、描画パイプラインを共有する。 */
public class TitaniumClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Titanium");
    private static final UpscalePipeline PIPELINE = new UpscalePipeline();
    private static TitaniumConfig config = new TitaniumConfig();
    private static Path configPath;

    /**
     * 設定を復元し、キー割り当てとクライアントコマンドを登録する。
     */
    @Override
    public void onInitializeClient() {
        // 設定の復元を先に行い、キー割り当てと描画処理が同じ設定インスタンスを参照するようにする。
        configPath = FabricLoader.getInstance().getConfigDir().resolve("titanium.json");
        config = TitaniumConfig.load(configPath, LOGGER);
        TitaniumKeyMappings.initialize();
        LOGGER.info("[Titanium] initialized: enabled={}, scale={}, requested backend={}, DLSS quality={}, integration={}",
                config.enabled(), config.renderScale(), config.backend(), config.dlssQuality(), config.dlssIntegrationMode());
        if (Boolean.getBoolean("titanium.nativeProbe") || Boolean.getBoolean("titanium.frameInteropProbe")) {
            var nativeCapabilities = NativeBridge.initialize();
            LOGGER.info("[Titanium/Native] result={}, adapter='{}', vendor=0x{}, device=0x{}, LUID={}, "
                            + "dedicated={} MiB, shared={} MiB, featureLevel=0x{}, Streamline DLL mask=0x{}; {}",
                    nativeCapabilities.result(), nativeCapabilities.adapterDescription(),
                    Long.toHexString(nativeCapabilities.vendorId()), Long.toHexString(nativeCapabilities.deviceId()),
                    nativeCapabilities.adapterLuid(), nativeCapabilities.dedicatedVideoMemory() / 1024 / 1024,
                    nativeCapabilities.sharedSystemMemory() / 1024 / 1024,
                    Integer.toHexString(nativeCapabilities.featureLevel()),
                    Integer.toHexString(nativeCapabilities.streamlineDllMask()), nativeCapabilities.diagnostics());
        }
        // 状態表示・設定画面・再読み込みの入口をクライアント側だけに登録する。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("titanium")
                        .executes(context -> {
                            var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
                            context.getSource().sendFeedback(Component.literal(
                                    PIPELINE.status(target.width, target.height)
                                            + celeste.titanium.client.render.enhanced.EnhancedRuntime.statusSuffix()));
                            return 1;
                        })
                        .then(literal("config").executes(context -> {
                            Minecraft client = Minecraft.getInstance();
                            client.gui.setScreen(TitaniumConfigScreen.create(client.gui.screen()));
                            return 1;
                        }))
                        .then(literal("reload").executes(context -> {
                            config = TitaniumConfig.load(configPath, LOGGER);
                            celeste.titanium.client.render.enhanced.EnhancedRuntime.configurationChanged();
                            PIPELINE.reloadProductSession();
                            context.getSource().sendFeedback(Component.literal("Titanium: config reloaded; "
                                    + (config.enabled() ? "Bilinear" : "Off") + ", scale=" + config.renderScale()));
                            return 1;
                        }))));
    }

    /**
     * 現在の設定をJSONへ保存し、Enhanced連携へ設定変更を通知する。
     */
    public static void saveConfig() {
        config.save(configPath, LOGGER);
        celeste.titanium.client.render.enhanced.EnhancedRuntime.configurationChanged();
    }

    public static TitaniumConfig config() {
        return config;
    }

    public static UpscalePipeline pipeline() {
        return PIPELINE;
    }
}
