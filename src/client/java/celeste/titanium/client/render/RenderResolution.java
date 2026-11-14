package celeste.titanium.client.render;

import celeste.titanium.client.config.TitaniumConfig;

public record RenderResolution(int width, int height) {
    /**
     * 出力幅・高さへ描画倍率を掛け、四捨五入した最小1ピクセルの入力寸法を求める。
     *
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param scale 出力に対する入力の幅・高さの倍率
     * @return 各辺を四捨五入し、最小1ピクセルにした入力寸法
     */
    public static RenderResolution scaled(int width, int height, double scale) {
        if (width < 1 || height < 1 || !Double.isFinite(scale)
                || scale < TitaniumConfig.MIN_SCALE || scale > 1.0) {
            throw new IllegalArgumentException("Invalid render dimensions or scale");
        }
        // 幅・高さをそれぞれ最寄りの整数へ丸め、縮小しても最小1ピクセルを確保する。
        return new RenderResolution(Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)));
    }
}
