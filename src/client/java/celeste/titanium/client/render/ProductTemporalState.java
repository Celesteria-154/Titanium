package celeste.titanium.client.render;

/** GPU同期や読み戻しを追加せず、DLSSの履歴進行を公開する。 */
public record ProductTemporalState(
        boolean available,
        long packetSequence,
        long previousSequence,
        long evaluateSequence,
        long historyGeneration,
        long framesSinceReset,
        String resetReason,
        long featureGeneration,
        long evaluatedFrames,
        long returnedFrames) {

    public ProductTemporalState {
        if (packetSequence < 0 || previousSequence < 0 || evaluateSequence < 0
                || historyGeneration < 0 || framesSinceReset < 0 || featureGeneration < 0
                || evaluatedFrames < 0 || returnedFrames < 0) {
            throw new IllegalArgumentException("Product temporal counters must be non-negative");
        }
        if (resetReason == null || resetReason.isBlank()) {
            throw new IllegalArgumentException("resetReason is required");
        }
    }

    public static ProductTemporalState unavailable() {
        return new ProductTemporalState(false, 0, 0, 0, 0, 0,
                "N/A", 0, 0, 0);
    }

    public boolean continuousFor(long minimumFrames) {
        if (minimumFrames < 1) throw new IllegalArgumentException("minimumFrames must be positive");
        return available && framesSinceReset >= minimumFrames
                && packetSequence > 1 && previousSequence + 1 == packetSequence
                && evaluateSequence == packetSequence
                && evaluatedFrames >= packetSequence && returnedFrames >= packetSequence;
    }
}
