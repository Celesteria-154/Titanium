package celeste.titanium.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

public record DepthStatistics(
        int samples,
        int finiteSamples,
        int zeroSamples,
        int oneSamples,
        int interiorSamples,
        float minimum,
        float maximum,
        double mean) {
    private static final float ENDPOINT_EPSILON = 1.0e-5F;

    /**
     * 深度floatを走査して有限値・0/1端点・内部値の件数と範囲を求める。
     *
     * @param source ピクセル形式に対応した診断読み戻しのバイト列
     * @param samples 解析するピクセルのサンプル数
     * @return 端点・内部深度・有限値の件数と範囲、平均
     */
    public static DepthStatistics analyze(ByteBuffer source, int samples) {
        if (samples < 1 || source.remaining() < Math.multiplyExact(samples, Float.BYTES)) {
            throw new IllegalArgumentException("Depth buffer is smaller than the requested sample count");
        }
        // 呼び出し元のバッファ位置を変更せず、ネイティブ順のfloatとして深度サンプルを読む。
        ByteBuffer values = source.duplicate().order(ByteOrder.nativeOrder());
        int finite = 0;
        int zero = 0;
        int one = 0;
        int interior = 0;
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        double sum = 0.0;
        for (int index = 0; index < samples; index++) {
            float value = values.getFloat(index * Float.BYTES);
            if (!Float.isFinite(value)) continue;
            finite++;
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
            sum += value;
            // 丸め誤差を許容して0・1の端点と内部深度を分け、クリア値と実地形の有無を調べる。
            if (value <= ENDPOINT_EPSILON) {
                zero++;
            } else if (value >= 1.0F - ENDPOINT_EPSILON) {
                one++;
            } else {
                interior++;
            }
        }
        return new DepthStatistics(samples, finite, zero, one, interior, minimum, maximum,
                finite == 0 ? Double.NaN : sum / finite);
    }

    public boolean isFiniteUnitRange() {
        return finiteSamples == samples && minimum >= -ENDPOINT_EPSILON
                && maximum <= 1.0F + ENDPOINT_EPSILON;
    }

    public boolean isNonUniformWorldDepth() {
        return isFiniteUnitRange() && interiorSamples > 0 && maximum - minimum > ENDPOINT_EPSILON;
    }

    /**
     * 全サンプルが0または1の端点に一致する場合に、その端点を返す。
     *
     * @return 全値が一致する0または1の端点。どちらでもない場合はNONE
     */
    public Endpoint uniformEndpoint() {
        if (!isFiniteUnitRange()) return Endpoint.NONE;
        if (zeroSamples == samples) return Endpoint.ZERO;
        if (oneSamples == samples) return Endpoint.ONE;
        return Endpoint.NONE;
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "samples=%d,finite=%d,zero=%d,one=%d,interior=%d,min=%.8f,max=%.8f,mean=%.8f",
                samples, finiteSamples, zeroSamples, oneSamples, interiorSamples, minimum, maximum, mean);
    }

    public enum Endpoint {
        ZERO,
        ONE,
        NONE
    }
}
