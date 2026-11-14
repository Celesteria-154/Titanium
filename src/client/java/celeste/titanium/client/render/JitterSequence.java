package celeste.titanium.client.render;

/** Halton(2,3)のサンプル列。周期は8×出力と入力の解像度比の二乗から選ぶ。 */
final class JitterSequence {
    private final int phaseCount;
    private int nextPhase;

    JitterSequence(int phaseCount) {
        if (phaseCount < 1) throw new IllegalArgumentException("phaseCount must be positive");
        this.phaseCount = phaseCount;
    }

    /**
     * Halton(2,3)の次の入力ピクセルサンプルを生成し、周期内の位相を一つ進める。
     *
     * @return 入力ピクセル単位の位置と、進行前の位相番号を持つサンプル
     */
    FrameJitter next() {
        int phase = nextPhase;
        // Haltonの先頭0を避け、[0,1)のサンプルを入力ピクセル中心まわりの[-0.5,0.5)へ移す。
        int haltonIndex = phase + 1;
        nextPhase = (nextPhase + 1) % phaseCount;
        return new FrameJitter(halton(haltonIndex, 2) - 0.5, halton(haltonIndex, 3) - 0.5,
                phase, phaseCount, true);
    }

    void reset() {
        nextPhase = 0;
    }

    /**
     * 最大の縦横拡大率について8×倍率の二乗を切り上げ、最低位相数も満たす周期を求める。
     *
     * 縦横の大きい方の出力/入力比を採用し、整数寸法の丸めがある場合も必要なサンプル数を確保する。
     *
     * @param outputWidth 出力幅。ピクセル単位
     * @param outputHeight 出力高さ。ピクセル単位
     * @param renderWidth ワールド入力の幅。ピクセル単位
     * @param renderHeight ワールド入力の高さ。ピクセル単位
     * @return 8×拡大率の二乗を切り上げ、指定された最低数も満たす位相数
     */
    static int phaseCount(int outputWidth, int outputHeight, int renderWidth, int renderHeight) {
        return phaseCount(outputWidth, outputHeight, renderWidth, renderHeight, 1);
    }

    /**
     * 最大の縦横拡大率について8×倍率の二乗を切り上げ、最低位相数も満たす周期を求める。
     *
     * 縦横の大きい方の出力/入力比を採用し、整数寸法の丸めがある場合も必要なサンプル数を確保する。
     *
     * @param outputWidth 出力幅。ピクセル単位
     * @param outputHeight 出力高さ。ピクセル単位
     * @param renderWidth ワールド入力の幅。ピクセル単位
     * @param renderHeight ワールド入力の高さ。ピクセル単位
     * @param minimum 許容する最低位相数
     * @return 8×拡大率の二乗を切り上げ、指定された最低数も満たす位相数
     */
    static int phaseCount(int outputWidth, int outputHeight, int renderWidth, int renderHeight, int minimum) {
        if (outputWidth < 1 || outputHeight < 1 || renderWidth < 1 || renderHeight < 1) {
            throw new IllegalArgumentException("Jitter dimensions must be positive");
        }
        if (minimum < 1 || minimum > 32) throw new IllegalArgumentException("Invalid minimum jitter phases");
        // 縦横比の丸め差を含めて大きい拡大率を採用し、出力1ピクセルあたりのサンプル数を保つ。
        double scale = Math.max((double) outputWidth / renderWidth, (double) outputHeight / renderHeight);
        return Math.max(minimum, (int) Math.ceil(8.0 * scale * scale));
    }

    /**
     * 固定条件の32位相診断の指定を検証し、通常時は追加制限のない最低数1を返す。
     *
     * @return 通常時は1。固定条件の診断指定が有効な場合は32
     */
    static int configuredMinimumPhases() {
        if (!Boolean.getBoolean("titanium.qualityJitter32")) return 1;
        if (!Boolean.getBoolean("titanium.qualityNormalClient")
                || !Boolean.getBoolean("titanium.qualityFixedMotion")
                || !Boolean.getBoolean("titanium.quality4kWindow")) {
            throw new IllegalStateException("Jitter phase experiment requires isolated 4K fixed-motion quality run");
        }
        return 32;
    }

    /**
     * 指定基数で位相番号の桁を小数側へ反転し、[0,1)の低食い違いサンプルを求める。
     *
     * @param index 対象のスロットまたは位相の番号
     * @param base Halton列で桁を分解する基数
     * @return 指定基数の位相番号から求めた[0,1)のサンプル値
     */
    private static double halton(int index, int base) {
        double result = 0.0;
        double fraction = 1.0 / base;
        // 位相番号の各桁を小数側へ反転し、基数2・3の低食い違いサンプルを生成する。
        while (index > 0) {
            result += (index % base) * fraction;
            index /= base;
            fraction /= base;
        }
        return result;
    }
}
