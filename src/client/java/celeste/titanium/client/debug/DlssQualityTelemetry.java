package celeste.titanium.client.debug;

import java.util.Objects;

/** 既存のCPU状態から画質入力とフレーム間隔の診断スナップショットを生成する。 */
public record DlssQualityTelemetry(
        boolean available,
        boolean screenOpen,
        long packetSequence,
        long previousPacketSequence,
        long evaluateSequence,
        long historyGeneration,
        long framesSinceReset,
        String resetReason,
        double projectionJitterX,
        double projectionJitterY,
        double ngxJitterX,
        double ngxJitterY,
        String motionConvention,
        boolean motionIncludesJitter,
        long pacingSamples,
        double frameDeltaMilliseconds,
        double frameDeltaAverageMilliseconds,
        double frameDeltaMinimumMilliseconds,
        double frameDeltaMaximumMilliseconds,
        double frameDeltaDeviationMilliseconds) {
    public DlssQualityTelemetry {
        Objects.requireNonNull(resetReason, "resetReason");
        Objects.requireNonNull(motionConvention, "motionConvention");
        if (packetSequence < 0 || previousPacketSequence < 0 || evaluateSequence < 0
                || historyGeneration < 0 || framesSinceReset < 0 || pacingSamples < 0) {
            throw new IllegalArgumentException("Invalid DLSS quality telemetry counter");
        }
    }

    public static DlssQualityTelemetry unavailable() {
        return new DlssQualityTelemetry(false, false, 0, 0, 0, 0, 0, "N/A",
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, "N/A", false,
                0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }
}
