package celeste.titanium.client.debug;

import celeste.titanium.client.config.DlssQuality;
import celeste.titanium.client.config.UpscaleBackend;
import celeste.titanium.client.render.DlssFeatureMode;
import celeste.titanium.client.render.DlssQualityPolicy;

import java.util.Objects;

/** 描画スレッドから取得する状態スナップショット。モデル欄には要求・送信済みプリセットを保持する。 */
public record DlssDebugState(
        boolean titaniumEnabled,
        UpscaleBackend requestedBackend,
        UpscaleBackend activeBackend,
        DlssQuality requestedQuality,
        DlssMode activeMode,
        double renderScale,
        int renderWidth,
        int renderHeight,
        int outputWidth,
        int outputHeight,
        DlssModel requestedModel,
        DlssModel activeModel,
        DlssRuntimeState runtimeState,
        long displayedFrames,
        long fallbackFrames,
        String detail,
        DlssDebugTelemetry telemetry) {

    /**
     * 描画側で確定した有効モードと送信済みプリセットから、表示対象の補正方針を求める。
     *
     * @return 現在の送信済み設定に対応する補正方針。DLSS未使用または分類不能時はnull
     */
    public DlssQualityPolicy qualityPolicy() {
        if (activeBackend != UpscaleBackend.DLSS || activeMode == DlssMode.CUSTOM
                || activeModel == DlssModel.UNAVAILABLE || activeModel == DlssModel.AUTO) return null;
        // 描画側で確定したモードと送信済みHintから、補正プロファイルの表示を組み立てる。
        return DlssQualityPolicy.fromSubmittedHint(DlssFeatureMode.valueOf(activeMode.name()),
                activeModel == DlssModel.SDK_DEFAULT);
    }

    public DlssDebugState {
        Objects.requireNonNull(requestedBackend, "requestedBackend");
        Objects.requireNonNull(activeBackend, "activeBackend");
        Objects.requireNonNull(requestedQuality, "requestedQuality");
        Objects.requireNonNull(activeMode, "activeMode");
        Objects.requireNonNull(requestedModel, "requestedModel");
        Objects.requireNonNull(activeModel, "activeModel");
        Objects.requireNonNull(runtimeState, "runtimeState");
        Objects.requireNonNull(telemetry, "telemetry");
        if (!Double.isFinite(renderScale) || renderScale <= 0.0) {
            throw new IllegalArgumentException("renderScale must be positive and finite");
        }
        if (renderWidth < 1 || renderHeight < 1 || outputWidth < 1 || outputHeight < 1) {
            throw new IllegalArgumentException("debug dimensions must be positive");
        }
        if (displayedFrames < 0 || fallbackFrames < 0) {
            throw new IllegalArgumentException("frame counters must not be negative");
        }
        detail = detail == null ? "" : detail;
    }
}
