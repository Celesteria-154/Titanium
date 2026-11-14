package celeste.titanium.client.render;

/** 画面状態が同じ区間の描画間隔を追跡する。追加のGPU同期は行わない。 */
final class FramePacingTracker {
    record Snapshot(boolean screenOpen, long samples, double latestMilliseconds,
                    double averageMilliseconds, double minimumMilliseconds,
                    double maximumMilliseconds, double deviationMilliseconds) {
    }

    private static final int CAPACITY = 120;
    private final double[] samples = new double[CAPACITY];
    private boolean initialized;
    private boolean screenOpen;
    private long previousNanos;
    private int count;
    private int next;

    /**
     * 画面の開閉状態ごとに区間を分け、直近120個のCPU描画間隔をリングへ保存する。
     *
     * @param nowNanos 現在のCPU取得時刻。ナノ秒単位
     * @param currentScreenOpen 現在GUI画面が開かれている場合はtrue
     */
    void sample(long nowNanos, boolean currentScreenOpen) {
        if (nowNanos < 0) throw new IllegalArgumentException("Frame timestamp must not be negative");
        // GUIの開閉をまたぐ時間を同じ統計へ混ぜず、同じ画面状態の区間ごとにサンプルを採る。
        if (!initialized || screenOpen != currentScreenOpen) {
            initialized = true;
            screenOpen = currentScreenOpen;
            previousNanos = nowNanos;
            count = 0;
            next = 0;
            return;
        }
        double milliseconds = Math.max(0.001, Math.min(1000.0,
                (nowNanos - previousNanos) / 1_000_000.0));
        previousNanos = nowNanos;
        // 直近120個のリングへ保存し、長時間の実行でも統計用メモリを一定に保つ。
        samples[next] = milliseconds;
        next = (next + 1) % CAPACITY;
        if (count < CAPACITY) count++;
    }

    /**
     * 現在区間の最新値・平均・範囲・標準偏差を計算する。未計測時はNaNで返す。
     *
     * @return 最新・平均・最小・最大・標準偏差を含むCPU描画間隔の統計
     */
    Snapshot snapshot() {
        if (count == 0) {
            return new Snapshot(screenOpen, 0, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN);
        }
        double sum = 0.0;
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double value = samples[i];
            sum += value;
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        double average = sum / count;
        // 区間内の平均からの二乗差を集計し、周期的な長短のばらつきも標準偏差へ反映する。
        double variance = 0.0;
        for (int i = 0; i < count; i++) {
            double difference = samples[i] - average;
            variance += difference * difference;
        }
        int latestIndex = (next - 1 + CAPACITY) % CAPACITY;
        return new Snapshot(screenOpen, count, samples[latestIndex], average, minimum, maximum,
                Math.sqrt(variance / count));
    }
}
