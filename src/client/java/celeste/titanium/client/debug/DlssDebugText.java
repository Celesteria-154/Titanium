package celeste.titanium.client.debug;

import celeste.titanium.client.config.DebugOverlayMode;
import celeste.titanium.client.config.UpscaleBackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DlssDebugText {
    private DlssDebugText() {
    }

    public static List<String> normalLines(DlssDebugState state, DebugOverlayMode mode) {
        if (mode == DebugOverlayMode.OFF) return List.of();
        if (mode == DebugOverlayMode.COMPACT) {
            return List.of(dlssLine(state) + " | " + modeSummary(state) + " | Feature "
                    + runtimeLabel(state.runtimeState()));
        }
        return mode == DebugOverlayMode.DETAILED ? detailedLines(state) : monitoringLines(state);
    }

    public static List<String> f3Lines(DlssDebugState state) {
        return List.of(
                dlssLine(state),
                modeLine(state),
                "Preset Hint: " + state.requestedModel().label() + " / " + state.activeModel().label(),
                "Feature: " + runtimeLabel(state.runtimeState()));
    }

    /**
     * 要求・有効バックエンドと退避理由をDLSS状態の一行へまとめる。
     *
     * @param state 表示または判定に使用する取得済み状態
     * @return 要求バックエンド、有効バックエンド、必要な場合は退避理由を含む表示行
     */
    private static String dlssLine(DlssDebugState state) {
        String value;
        if (!state.titaniumEnabled() || state.requestedBackend() == UpscaleBackend.BILINEAR) {
            value = "Disabled";
        } else {
            value = switch (state.runtimeState()) {
                case ACTIVE -> "Enabled";
                case INITIALIZING -> "Initializing";
                case ERROR -> "Error";
                case INACTIVE -> "Inactive";
            };
        }
        return "DLSS: " + value;
    }

    private static String modeLine(DlssDebugState state) {
        return state.activeBackend() == UpscaleBackend.DLSS
                ? "Mode: " + state.activeMode().label() + " / " + percent(state.renderScale())
                : "Scale: " + percent(state.renderScale()) + " (Bilinear)";
    }

    private static String modeSummary(DlssDebugState state) {
        return state.activeBackend() == UpscaleBackend.DLSS
                ? state.activeMode().label() + " " + percent(state.renderScale())
                : "Bilinear " + percent(state.renderScale());
    }

    /**
     * 実行状態、入力寸法、履歴と評価結果を監視用の行一覧へまとめる。
     *
     * @param state 表示または判定に使用する取得済み状態
     * @return 基本的な実行状態・解像度・入力・評価結果の監視行
     */
    private static List<String> monitoringLines(DlssDebugState state) {
        DlssDebugTelemetry telemetry = state.telemetry();
        List<String> lines = new ArrayList<>();
        lines.add(state.runtimeState() == DlssRuntimeState.ACTIVE ? "DLSS SR" : "DLSS SR (" + runtimeLabel(state.runtimeState()) + ")");
        lines.add(state.activeMode().label() + " / Preset Hint " + preset(state));
        lines.add("");
        lines.add(dimensions(state.renderWidth(), state.renderHeight()) + " (" + percent(state.renderScale()) + ")");
        lines.add("-> " + dimensions(state.outputWidth(), state.outputHeight()));

        lines.add("");
        lines.add("Frame:     " + milliseconds(telemetry.frameTimeMilliseconds()));
        lines.add("DLSS:      " + milliseconds(telemetry.dlssGpuMilliseconds()));
        lines.add(String.format(Locale.ROOT, "Scale:     %.3f", state.renderScale()));
        lines.add("");
        lines.add("MV:        " + telemetry.motionStatus());
        lines.add("Depth:     " + telemetry.depthStatus());
        lines.add("Exposure:  " + telemetry.exposureMode());
        lines.add("Jitter:    " + jitterIndex(telemetry));
        lines.add("History:   " + telemetry.historyStatus());
        return List.copyOf(lines);
    }

    /**
     * 入力契約、サンプル位相、履歴世代、時間計測を含む詳細表示を生成する。
     *
     * @param state 表示または判定に使用する取得済み状態
     * @return 履歴、入力契約、サンプル位相と時間情報を含む詳細行
     */
    private static List<String> detailedLines(DlssDebugState state) {
        DlssDebugTelemetry telemetry = state.telemetry();
        List<String> lines = new ArrayList<>();
        lines.add("[DLSS DEBUG]");
        lines.add("");
        lines.add("State");
        lines.add(row("Enabled", Boolean.toString(state.titaniumEnabled()
                && state.requestedBackend() == UpscaleBackend.DLSS)));
        lines.add(row("Initialized", Boolean.toString(telemetry.initialized())));
        lines.add(row("Feature Handle", telemetry.featureHandleValid() ? "valid" : "invalid"));
        lines.add(row("Feature Gen", telemetry.featureGeneration() > 0
                ? Long.toString(telemetry.featureGeneration()) : "N/A"));
        lines.add(row("Evaluate Result", telemetry.evaluateResult()));
        lines.add(row("Runtime", runtimeLabel(state.runtimeState())));
        lines.add(row("Backend", state.requestedBackend() + " -> " + state.activeBackend()));
        lines.add("");
        lines.add("Resolution");
        lines.add(row("Display", dimensions(state.outputWidth(), state.outputHeight())));
        lines.add(row("Render", dimensions(state.renderWidth(), state.renderHeight())));
        lines.add(row("Scale", String.format(Locale.ROOT, "%.2f %%", state.renderScale() * 100.0)));
        lines.add("");
        lines.add("Inputs");
        lines.add(row("Color", formatAndSize(telemetry.colorFormat(), state)));
        lines.add(row("Depth", formatAndSize(telemetry.depthFormat(), state)));
        lines.add(row("MotionVectors", formatAndSize(telemetry.motionVectorFormat(), state)));
        lines.add(row("MV Coverage", telemetry.motionStatus()));
        if (telemetry.inputProfile() != null) {
            lines.add(row("Color Source", telemetry.inputProfile().colorDescription()));
            lines.add(row("Temporal Source", telemetry.inputProfile().temporalDescription()));
            lines.add(row("Hand Motion", telemetry.inputProfile().handMotionDescription()));
        }
        lines.add(row("Exposure", telemetry.exposureMode()));
        lines.add("");
        lines.add("Temporal");
        lines.add(row("Jitter", jitter(telemetry)));
        lines.add(row("Jitter Index", jitterIndex(telemetry)));
        lines.add(row("Reset", Boolean.toString(telemetry.reset())));
        lines.add(row("MV Scale", vector(telemetry.motionScaleX(), telemetry.motionScaleY())));
        lines.add(row("History", telemetry.historyStatus()));
        DlssQualityTelemetry quality = telemetry.quality();
        if (quality.available()) {
            lines.add("");
            lines.add("Quality Trace");
            lines.add(row("Screen", quality.screenOpen() ? "Open" : "World"));
            lines.add(row("Packet Seq", quality.previousPacketSequence() + " -> "
                    + quality.packetSequence()));
            lines.add(row("Evaluate Seq", Long.toString(quality.evaluateSequence())));
            lines.add(row("History Gen", Long.toString(quality.historyGeneration())));
            lines.add(row("Reset Reason", quality.resetReason()));
            lines.add(row("Frames Since Reset", Long.toString(quality.framesSinceReset())));
            lines.add(row("Projection Jitter", qualityJitter(
                    quality.projectionJitterX(), quality.projectionJitterY())));
            lines.add(row("NGX Jitter", qualityJitter(quality.ngxJitterX(), quality.ngxJitterY())));
            lines.add(row("MV Convention", quality.motionConvention()));
            lines.add(row("MV Includes Jitter", Boolean.toString(quality.motionIncludesJitter())));
            lines.add(row("Pacing Samples", Long.toString(quality.pacingSamples())));
            lines.add(row("Frame Delta", milliseconds(quality.frameDeltaMilliseconds())));
            lines.add(row("Frame Avg", milliseconds(quality.frameDeltaAverageMilliseconds())));
            lines.add(row("Frame Min / Max", milliseconds(quality.frameDeltaMinimumMilliseconds())
                    + " / " + milliseconds(quality.frameDeltaMaximumMilliseconds())));
            lines.add(row("Frame Deviation", milliseconds(quality.frameDeltaDeviationMilliseconds())));
        }
        lines.add("");
        lines.add("DLSS");
        lines.add(row("Mode", state.activeMode().label()));
        // 表示するのはSDKへ送信した指定値。ドライバー側で選択された実モデルとの同一性は仮定しない。
        lines.add(row("Preset Hint", preset(state)));
        var policy = state.qualityPolicy();
        lines.add(row("Quality Profile", policy == null ? "N/A" : policy.name()));
        lines.add(row("Quality Scope", policy == null ? "N/A" : policy.evaluationEligible()
                ? "SDK Default candidate (not passed)" : "Manual: no quality guarantee"));
        lines.add(row("Correction", policy == null ? "N/A" : policy.correctionStatus()));
        lines.add(row("Sharpness (Post)", decimalOrNa(telemetry.sharpness(), 2)));
        lines.add(row("Frame Time", milliseconds(telemetry.frameTimeMilliseconds())));
        lines.add(row("GPU Time", milliseconds(telemetry.dlssGpuMilliseconds())));
        lines.add("");
        lines.add("Frames");
        lines.add(row("Displayed", Long.toString(state.displayedFrames())));
        lines.add(row("Fallback", Long.toString(state.fallbackFrames())));
        if (!state.detail().isBlank()) lines.add(row("Detail", state.detail()));
        return List.copyOf(lines);
    }

    private static String row(String label, String value) {
        return String.format(Locale.ROOT, "  %-18s %s", label, value);
    }

    private static String preset(DlssDebugState state) {
        return state.activeModel() == DlssModel.UNAVAILABLE ? "N/A" : state.activeModel().label();
    }

    private static String dimensions(int width, int height) {
        return width + "×" + height;
    }

    private static String formatAndSize(String format, DlssDebugState state) {
        return "N/A".equals(format) ? "N/A" : format + "   " + state.renderWidth() + "x" + state.renderHeight();
    }

    private static String jitter(DlssDebugTelemetry telemetry) {
        if (telemetry.jitterCount() == 0) return "N/A";
        return String.format(Locale.ROOT, "(%+.3f, %+.3f)", telemetry.jitterX(), telemetry.jitterY());
    }

    private static String jitterIndex(DlssDebugTelemetry telemetry) {
        return telemetry.jitterCount() == 0 ? "N/A"
                : (telemetry.jitterIndex() + 1) + " / " + telemetry.jitterCount();
    }

    private static String vector(double x, double y) {
        return Double.isNaN(x) || Double.isNaN(y) ? "N/A"
                : String.format(Locale.ROOT, "%.1f, %.1f (pixels)", x, y);
    }

    private static String qualityJitter(double x, double y) {
        return Double.isNaN(x) || Double.isNaN(y) ? "N/A"
                : String.format(Locale.ROOT, "(%+.4f, %+.4f) px", x, y);
    }

    private static String milliseconds(double value) {
        return Double.isNaN(value) ? "N/A" : String.format(Locale.ROOT, "%.2f ms", value);
    }

    private static String decimalOrNa(double value, int digits) {
        return Double.isNaN(value) ? "N/A" : String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    private static String runtimeLabel(DlssRuntimeState state) {
        return title(state.name());
    }

    private static String title(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String percent(double scale) {
        return String.format(Locale.ROOT, "%.1f%%", scale * 100.0);
    }
}
