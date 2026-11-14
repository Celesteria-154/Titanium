package celeste.titanium.client.render;

import java.nio.ByteBuffer;

public record ColorStatistics(int samples, int differingSamples, int alphaSamples,
                              int minRed, int maxRed, int minGreen, int maxGreen, int minBlue, int maxBlue) {
    /**
     * RGBA8の読み戻しを走査し、色成分の範囲・平均と非一様なサンプル数を集計する。
     *
     * @param source ピクセル形式に対応した診断読み戻しのバイト列
     * @param samples 解析するピクセルのサンプル数
     * @return 有効な色の件数、範囲、平均と非一様性の統計
     */
    public static ColorStatistics analyze(ByteBuffer source, int samples) {
        if (samples < 1 || source.remaining() < Math.multiplyExact(samples, 4)) {
            throw new IllegalArgumentException("Color buffer is smaller than the requested sample count");
        }
        ByteBuffer values = source.duplicate();
        int first = values.getInt(0);
        int different = 0;
        int alpha = 0;
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        for (int index = 0; index < samples; index++) {
            int offset = index * 4;
            int red = Byte.toUnsignedInt(values.get(offset));
            int green = Byte.toUnsignedInt(values.get(offset + 1));
            int blue = Byte.toUnsignedInt(values.get(offset + 2));
            int a = Byte.toUnsignedInt(values.get(offset + 3));
            if (values.getInt(offset) != first) different++;
            if (a != 0) alpha++;
            minR = Math.min(minR, red); maxR = Math.max(maxR, red);
            minG = Math.min(minG, green); maxG = Math.max(maxG, green);
            minB = Math.min(minB, blue); maxB = Math.max(maxB, blue);
        }
        return new ColorStatistics(samples, different, alpha, minR, maxR, minG, maxG, minB, maxB);
    }

    public boolean isNonUniform() {
        return differingSamples > 0 && (maxRed > minRed || maxGreen > minGreen || maxBlue > minBlue);
    }

    public String summary() {
        return "samples=" + samples + ",different=" + differingSamples + ",alpha=" + alphaSamples
                + ",r=" + minRed + ".." + maxRed + ",g=" + minGreen + ".." + maxGreen
                + ",b=" + minBlue + ".." + maxBlue;
    }
}
