package celeste.titanium.client.render;

/** 入力スナップショット間の経過時間を計測する。GPU処理時間は別途計測する。 */
final class DlssFrameClock {
    private boolean hasPrevious;
    private long previousNanos;

    /**
     * 入力取得時刻の差をミリ秒へ変換する。初回または履歴開始時は未計測値を返す。
     *
     * @param captureNanos 入力を取得したSystem.nanoTime基準の時刻
     * @param reset この入力で時間方向履歴を開始し直す場合はtrue
     * @return 前回取得からの経過ミリ秒。初回または履歴開始時はNaN
     */
    double elapsedMilliseconds(long captureNanos, boolean reset) {
        double elapsed = Double.NaN;
        if (hasPrevious && !reset) {
            // nanoTimeの絶対値は負値や周回を含むため、短時間の差分だけを使用する。
            long delta = captureNanos - previousNanos;
            if (delta <= 0) throw new IllegalArgumentException("Non-increasing DLSS input capture time");
            elapsed = Math.max(0.001, Math.min(1000.0, delta / 1_000_000.0));
        }
        previousNanos = captureNanos;
        hasPrevious = true;
        return elapsed;
    }

    void clear() { hasPrevious = false; }
}
