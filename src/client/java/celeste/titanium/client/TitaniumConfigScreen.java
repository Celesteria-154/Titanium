package celeste.titanium.client;

import celeste.titanium.client.config.DebugOverlayMode;
import celeste.titanium.client.config.TitaniumConfig;
import celeste.titanium.client.config.DlssQuality;
import celeste.titanium.client.config.UpscaleBackend;
import celeste.titanium.client.render.DlssPreset;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** 保存時に設定へ反映するCloth Config画面を構築する。 */
public final class TitaniumConfigScreen {
    private TitaniumConfigScreen() {
    }

    /**
     * 現在の設定値からCloth Config画面を構築し、保存時の値反映処理を接続する。
     *
     * @param parent 設定終了後に戻る親画面
     * @return 親画面と保存時処理を接続したCloth Config設定画面
     */
    public static Screen create(Screen parent) {
        TitaniumConfig config = TitaniumClient.config();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("title.titanium.config"));
        var entries = builder.entryBuilder();
        var rendering = builder.getOrCreateCategory(Component.translatable("category.titanium.rendering"));

        rendering.addEntry(entries.startBooleanToggle(
                        Component.translatable("option.titanium.enabled"), config.enabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("option.titanium.enabled.tooltip"))
                .setSaveConsumer(config::setEnabled)
                .build());
        rendering.addEntry(entries.startEnumSelector(
                        Component.translatable("option.titanium.backend"), UpscaleBackend.class, config.backend())
                .setDefaultValue(UpscaleBackend.BILINEAR)
                .setEnumNameProvider(value -> Component.translatable(
                        "option.titanium.backend." + value.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable("option.titanium.backend.tooltip"))
                .setSaveConsumer(config::setBackend)
                .build());
        // Bilinear倍率は設定の許容範囲を入力欄にも適用し、保存時に同じ検証を行う。
        rendering.addEntry(entries.startDoubleField(
                        Component.translatable("option.titanium.render_scale"), config.renderScale())
                .setDefaultValue(TitaniumConfig.DEFAULT_SCALE)
                .setMin(TitaniumConfig.MIN_SCALE)
                .setMax(1.0)
                .setTooltip(Component.translatable("option.titanium.render_scale.tooltip"))
                .setSaveConsumer(config::setRenderScale)
                .build());
        // 品質モードと連携モードを独立に選択し、保存後の描画境界で必要な再構成を行う。
        rendering.addEntry(entries.startEnumSelector(
                        Component.translatable("option.titanium.dlss_integration"),
                        celeste.titanium.client.config.DlssIntegrationMode.class, config.dlssIntegrationMode())
                .setDefaultValue(celeste.titanium.client.config.DlssIntegrationMode.STANDARD)
                .setEnumNameProvider(value -> Component.translatable(
                        "option.titanium.dlss_integration." + value.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable("option.titanium.dlss_integration.tooltip"))
                .setSaveConsumer(config::setDlssIntegrationMode)
                .build());
        rendering.addEntry(entries.startEnumSelector(
                        Component.translatable("option.titanium.dlss_quality"),
                        DlssQuality.class, config.dlssQuality())
                .setDefaultValue(DlssQuality.QUALITY)
                .setEnumNameProvider(value -> Component.translatable(
                        "option.titanium.dlss_quality." + value.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable("option.titanium.dlss_quality.tooltip"))
                .setSaveConsumer(config::setDlssQuality)
                .build());
        rendering.addEntry(entries.startEnumSelector(
                        Component.translatable("option.titanium.dlss_preset"), DlssPreset.class, config.dlssPreset())
                .setDefaultValue(DlssPreset.SDK_DEFAULT)
                .setEnumNameProvider(value -> Component.translatable(
                        "option.titanium.dlss_preset." + value.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable("option.titanium.dlss_preset.tooltip"))
                .setSaveConsumer(config::setDlssPreset)
                .build());
        rendering.addEntry(entries.startDoubleField(
                        Component.translatable("option.titanium.dlss_sharpness"), config.dlssSharpness())
                .setDefaultValue(TitaniumConfig.DEFAULT_DLSS_SHARPNESS)
                .setMin(0.0)
                .setMax(1.0)
                .setTooltip(Component.translatable("option.titanium.dlss_sharpness.tooltip"))
                .setSaveConsumer(config::setDlssSharpness)
                .build());
        rendering.addEntry(entries.startBooleanToggle(
                        Component.translatable("option.titanium.dlss_terrain_detail"), config.dlssTerrainDetail())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("option.titanium.dlss_terrain_detail.tooltip"))
                .setSaveConsumer(config::setDlssTerrainDetail)
                .build());
        rendering.addEntry(entries.startEnumSelector(
                        Component.translatable("option.titanium.debug_overlay"),
                        DebugOverlayMode.class, config.debugOverlay())
                .setDefaultValue(DebugOverlayMode.OFF)
                .setEnumNameProvider(value -> Component.translatable(
                        "option.titanium.debug_overlay." + value.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable("option.titanium.debug_overlay.tooltip"))
                .setSaveConsumer(config::setDebugOverlay)
                .build());
        rendering.addEntry(entries.startBooleanToggle(
                        Component.translatable("option.titanium.gui_frame_pacing"), config.guiFramePacing())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("option.titanium.gui_frame_pacing.tooltip"))
                .setSaveConsumer(config::setGuiFramePacing)
                .build());

        builder.setSavingRunnable(TitaniumClient::saveConfig);
        return builder.build();
    }
}
