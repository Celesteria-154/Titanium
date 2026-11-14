package celeste.titanium.client.render;

/** 解像度差によるLOD補正を1ミップ以内で計算する。DLAAは補正量を0とする。 */
final class TerrainLodBias {
    private static final double LOG_TWO = Math.log(2.0);
    private static final double MIN_COMPARISON_BIAS = -1.0;

    private TerrainLodBias() {
    }

    /**
     * 入力・出力解像度比のlog2から、最大1ミップの解像度補正を計算する。
     *
     * @param renderWidth ワールド入力の幅。ピクセル単位
     * @param renderHeight ワールド入力の高さ。ピクセル単位
     * @param outputWidth 出力幅。ピクセル単位
     * @param outputHeight 出力高さ。ピクセル単位
     * @return 最大1ミップの解像度補正差分。縮小がない場合は0
     */
    static float calculate(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        if (renderWidth < 1 || renderHeight < 1 || outputWidth < 1 || outputHeight < 1) {
            return 0.0F;
        }
        double scale = Math.min((double) renderWidth / outputWidth, (double) renderHeight / outputHeight);
        if (!Double.isFinite(scale) || scale <= 0.0 || scale >= 1.0) {
            return 0.0F;
        }
        return (float) Math.max(MIN_COMPARISON_BIAS, Math.log(scale) / LOG_TWO);
    }
}
