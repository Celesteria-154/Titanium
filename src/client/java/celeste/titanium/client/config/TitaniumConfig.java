package celeste.titanium.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import celeste.titanium.client.render.DlssPreset;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** JSON設定の読み書きと値の検証を行う。不正なファイルは保持し、そのセッションでは無効化する。 */
public final class TitaniumConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final double DEFAULT_SCALE = 2.0 / 3.0;
    public static final double MIN_SCALE = 0.25;
    public static final double DEFAULT_DLSS_SHARPNESS = 0.0;

    private boolean enabled = true;
    private double renderScale = DEFAULT_SCALE;
    private UpscaleBackend backend = UpscaleBackend.BILINEAR;
    private DlssQuality dlssQuality = DlssQuality.QUALITY;
    private DlssIntegrationMode dlssIntegrationMode = DlssIntegrationMode.STANDARD;
    private DlssPreset dlssPreset = DlssPreset.SDK_DEFAULT;
    private double dlssSharpness = DEFAULT_DLSS_SHARPNESS;
    private boolean dlssTerrainDetail = true;
    private boolean guiFramePacing = false;
    private DebugOverlayMode debugOverlay = DebugOverlayMode.OFF;

    /**
     * JSON設定を復元して値を検証する。読み込み失敗時はファイルを保持し、このセッションを無効状態へ退避する。
     *
     * @param path 読み書きまたは検証対象のファイルパス
     * @param logger 設定の読み書き失敗を記録するロガー
     * @return 検証済みの設定。読み込み失敗時はこのセッションで無効化した既定設定
     */
    public static TitaniumConfig load(Path path, Logger logger) {
        // 初回起動では既定値を保存し、以降の起動とゲーム内設定で同じJSONを使用する。
        if (!Files.exists(path)) {
            TitaniumConfig config = new TitaniumConfig();
            config.save(path, logger);
            return config;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            TitaniumConfig config = GSON.fromJson(reader, TitaniumConfig.class);
            if (config == null) {
                throw new JsonParseException("Config must be an object");
            }
            // JSONから復元した値も、画面から設定する値と同じ検証処理へ通す。
            config.setRenderScale(config.renderScale);
            config.setBackend(config.backend);
            config.setDlssQuality(config.dlssQuality);
            config.setDlssIntegrationMode(config.dlssIntegrationMode);
            config.setDlssPreset(config.dlssPreset);
            config.setDlssSharpness(config.dlssSharpness);
            config.setDebugOverlay(config.debugOverlay);
            return config;
        } catch (IOException | IllegalArgumentException | JsonParseException e) {
            logger.warn("[Titanium] Invalid config at {}; using Off for this session. File preserved.", path, e);
            // 不正な設定ファイルを上書きせず、このセッションのスケーリングだけを無効にする。
            TitaniumConfig fallback = new TitaniumConfig();
            fallback.enabled = false;
            return fallback;
        }
    }

    /**
     * 現在値をUTF-8のJSONとして保存し、書き込み成否を返す。
     *
     * @param path 読み書きまたは検証対象のファイルパス
     * @param logger 設定の読み書き失敗を記録するロガー
     * @return 設定ファイルへの保存に成功した場合はtrue
     */
    public boolean save(Path path, Logger logger) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            return true;
        } catch (IOException e) {
            logger.warn("[Titanium] Could not save config at {}", path, e);
            return false;
        }
    }

    public boolean enabled() {
        return enabled;
    }
    public boolean guiFramePacing() { return guiFramePacing; }
    public void setGuiFramePacing(boolean enabled) { guiFramePacing = enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double renderScale() {
        return renderScale;
    }

    /**
     * 有限値かつ0.25〜1.0であることを検証し、Bilinear描画倍率を保存する。
     *
     * @param renderScale 出力の幅・高さに対する入力描画倍率
     */
    public void setRenderScale(double renderScale) {
        if (!Double.isFinite(renderScale) || renderScale < MIN_SCALE || renderScale > 1.0) {
            throw new IllegalArgumentException("Render scale must be finite and between 0.25 and 1.0");
        }
        this.renderScale = renderScale;
    }

    public UpscaleBackend backend() {
        return backend;
    }

    public void setBackend(UpscaleBackend backend) {
        if (backend == null) throw new IllegalArgumentException("Backend must be known");
        this.backend = backend;
    }

    public DlssQuality dlssQuality() {
        return dlssQuality;
    }

    public DlssIntegrationMode dlssIntegrationMode() { return dlssIntegrationMode; }
    public void setDlssIntegrationMode(DlssIntegrationMode mode) {
        if (mode == null) throw new IllegalArgumentException("DLSS integration mode must be known");
        dlssIntegrationMode = mode;
    }

    public void setDlssQuality(DlssQuality dlssQuality) {
        if (dlssQuality == null) throw new IllegalArgumentException("DLSS quality must be known");
        this.dlssQuality = dlssQuality;
    }

    public DebugOverlayMode debugOverlay() {
        return debugOverlay;
    }

    public DlssPreset dlssPreset() { return dlssPreset; }

    public void setDlssPreset(DlssPreset preset) {
        if (preset == null) throw new IllegalArgumentException("DLSS preset must be known");
        dlssPreset = preset;
    }

    public double dlssSharpness() {
        return dlssSharpness;
    }

    public boolean dlssTerrainDetail() { return dlssTerrainDetail; }

    public void setDlssTerrainDetail(boolean enabled) { dlssTerrainDetail = enabled; }

    /**
     * 有限値かつ0.0〜1.0であることを確認し、出力シャープ化強度を保存する。
     *
     * @param sharpness 出力へ適用する空間シャープ化の強度。0.0〜1.0
     */
    public void setDlssSharpness(double sharpness) {
        if (!Double.isFinite(sharpness) || sharpness < 0.0 || sharpness > 1.0) {
            throw new IllegalArgumentException("DLSS output sharpness must be finite and between 0.0 and 1.0");
        }
        dlssSharpness = sharpness;
    }

    public void setDebugOverlay(DebugOverlayMode debugOverlay) {
        if (debugOverlay == null) throw new IllegalArgumentException("Debug overlay mode must be known");
        this.debugOverlay = debugOverlay;
    }
}
