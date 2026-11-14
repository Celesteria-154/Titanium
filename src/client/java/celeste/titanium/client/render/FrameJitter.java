package celeste.titanium.client.render;

/** 左上原点の入力ピクセル単位で表したジッターと、有限周期内の位相。 */
public record FrameJitter(double pixelX, double pixelY, int phase, int phaseCount, boolean enabled) {
    private static final FrameJitter NONE = new FrameJitter(0.0, 0.0, 0, 1, false);

    public FrameJitter {
        if (!Double.isFinite(pixelX) || !Double.isFinite(pixelY)
                || pixelX < -0.5 || pixelX >= 0.5 || pixelY < -0.5 || pixelY >= 0.5
                || phase < 0 || phase >= phaseCount || phaseCount < 1) {
            throw new IllegalArgumentException("Invalid frame jitter");
        }
        if (!enabled && (pixelX != 0.0 || pixelY != 0.0 || phase != 0 || phaseCount != 1)) {
            throw new IllegalArgumentException("Disabled jitter must use the canonical zero value");
        }
    }

    public static FrameJitter none() {
        return NONE;
    }
}
