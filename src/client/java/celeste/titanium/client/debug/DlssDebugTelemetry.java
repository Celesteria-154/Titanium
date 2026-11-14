package celeste.titanium.client.debug;

import celeste.titanium.client.render.DlssInputProfile;
import java.util.Objects;

/** 既存のフレーム処理から得た実行状態を保持する。未計測値はNaNまたはN/Aで表す。 */
public record DlssDebugTelemetry(
        boolean initialized,
        boolean featureHandleValid,
        long featureGeneration,
        String evaluateResult,
        String colorFormat,
        String depthFormat,
        String motionVectorFormat,
        String exposureMode,
        double jitterX,
        double jitterY,
        int jitterIndex,
        int jitterCount,
        boolean reset,
        double motionScaleX,
        double motionScaleY,
        String motionStatus,
        String depthStatus,
        String historyStatus,
        double frameTimeMilliseconds,
        double dlssGpuMilliseconds,
        double sharpness,
        DlssQualityTelemetry quality,
        DlssInputProfile inputProfile) {
    public DlssDebugTelemetry {
        Objects.requireNonNull(evaluateResult, "evaluateResult");
        Objects.requireNonNull(colorFormat, "colorFormat");
        Objects.requireNonNull(depthFormat, "depthFormat");
        Objects.requireNonNull(motionVectorFormat, "motionVectorFormat");
        Objects.requireNonNull(exposureMode, "exposureMode");
        Objects.requireNonNull(motionStatus, "motionStatus");
        Objects.requireNonNull(depthStatus, "depthStatus");
        Objects.requireNonNull(historyStatus, "historyStatus");
        Objects.requireNonNull(quality, "quality");
        if (featureGeneration < 0 || jitterIndex < 0 || jitterCount < 0
                || jitterCount > 0 && jitterIndex >= jitterCount) {
            throw new IllegalArgumentException("Invalid DLSS debug counters");
        }
        requireFiniteOrNaN(jitterX, "jitterX");
        requireFiniteOrNaN(jitterY, "jitterY");
        requireFiniteOrNaN(motionScaleX, "motionScaleX");
        requireFiniteOrNaN(motionScaleY, "motionScaleY");
        requireNonNegativeOrNaN(frameTimeMilliseconds, "frameTimeMilliseconds");
        requireNonNegativeOrNaN(dlssGpuMilliseconds, "dlssGpuMilliseconds");
        requireFiniteOrNaN(sharpness, "sharpness");
    }

    public static DlssDebugTelemetry unavailable() {
        return new DlssDebugTelemetry(false, false, 0, "N/A", "N/A", "N/A", "N/A", "N/A",
                Double.NaN, Double.NaN, 0, 0, false, Double.NaN, Double.NaN,
                "N/A", "N/A", "N/A", Double.NaN, Double.NaN, Double.NaN,
                DlssQualityTelemetry.unavailable(), null);
    }

    private static void requireFiniteOrNaN(double value, String name) {
        if (!Double.isFinite(value) && !Double.isNaN(value)) {
            throw new IllegalArgumentException(name + " must be finite or NaN");
        }
    }

    private static void requireNonNegativeOrNaN(double value, String name) {
        requireFiniteOrNaN(value, name);
        if (!Double.isNaN(value) && value < 0.0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
