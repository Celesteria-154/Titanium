package celeste.titanium.client.render;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** 深度復元の全ピクセル往復誤差と診断画像を集計する。 */
public record DepthReconstructionStatistics(
        int samples,
        int clearSamples,
        int validSamples,
        int finiteResults,
        int invalidSamples,
        double maximumPixelError,
        double meanPixelError,
        double maximumDepthError,
        double meanDepthError,
        double minimumWorldX,
        double maximumWorldX,
        double minimumWorldY,
        double maximumWorldY,
        double minimumWorldZ,
        double maximumWorldZ,
        BufferedImage depthImage,
        BufferedImage errorImage) {
    public static final double MAXIMUM_PIXEL_ERROR = 0.05;
    public static final double MAXIMUM_DEPTH_ERROR = 1.0e-5;

    /**
     * クリア領域を区別しながら深度復元と再投影を行い、誤差の最大値・平均と画像を集計する。
     *
     * @param source ピクセル形式に対応した診断読み戻しのバイト列
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param clearEndpoint 背景として扱う深度の端点
     * @return 復元と再投影の誤差統計、および深度・誤差の可視化画像
     */
    public static DepthReconstructionStatistics analyze(ByteBuffer source, FrameInputPacket packet,
                                                        DepthStatistics.Endpoint clearEndpoint) {
        if (clearEndpoint == DepthStatistics.Endpoint.NONE) {
            throw new IllegalArgumentException("A ZERO or ONE clear endpoint is required");
        }
        int samples = Math.multiplyExact(packet.width(), packet.height());
        if (source.remaining() < Math.multiplyExact(samples, Float.BYTES)) {
            throw new IllegalArgumentException("Depth buffer does not match the frame packet");
        }

        ByteBuffer values = source.duplicate().order(ByteOrder.nativeOrder());
        DepthReconstruction.Transform transform = DepthReconstruction.forPacket(packet);
        BufferedImage depthImage = new BufferedImage(packet.width(), packet.height(), BufferedImage.TYPE_INT_ARGB);
        BufferedImage errorImage = new BufferedImage(packet.width(), packet.height(), BufferedImage.TYPE_INT_ARGB);
        float clearDepth = clearEndpoint == DepthStatistics.Endpoint.ZERO ? 0.0F : 1.0F;
        int clear = 0;
        int valid = 0;
        int finite = 0;
        int invalid = 0;
        double maximumPixelError = 0.0;
        double pixelErrorSum = 0.0;
        double maximumDepthError = 0.0;
        double depthErrorSum = 0.0;
        double minimumWorldX = Double.POSITIVE_INFINITY;
        double maximumWorldX = Double.NEGATIVE_INFINITY;
        double minimumWorldY = Double.POSITIVE_INFINITY;
        double maximumWorldY = Double.NEGATIVE_INFINITY;
        double minimumWorldZ = Double.POSITIVE_INFINITY;
        double maximumWorldZ = Double.NEGATIVE_INFINITY;

        for (int y = 0; y < packet.height(); y++) {
            int imageY = packet.height() - 1 - y;
            for (int x = 0; x < packet.width(); x++) {
                int index = y * packet.width() + x;
                float depth = values.getFloat(index * Float.BYTES);
                depthImage.setRGB(x, imageY, depthColor(depth));
                if (!Float.isFinite(depth) || depth < 0.0F || depth > 1.0F) {
                    invalid++;
                    errorImage.setRGB(x, imageY, 0xFFFF00FF);
                    continue;
                }
                if (depth == clearDepth) {
                    clear++;
                    errorImage.setRGB(x, imageY, 0xFF000000);
                    continue;
                }
                valid++;
                try {
                    DepthReconstruction.Result result = transform.roundTrip(x, y, depth);
                    finite++;
                    maximumPixelError = Math.max(maximumPixelError, result.pixelError());
                    pixelErrorSum += result.pixelError();
                    maximumDepthError = Math.max(maximumDepthError, result.depthError());
                    depthErrorSum += result.depthError();
                    minimumWorldX = Math.min(minimumWorldX, result.worldX());
                    maximumWorldX = Math.max(maximumWorldX, result.worldX());
                    minimumWorldY = Math.min(minimumWorldY, result.worldY());
                    maximumWorldY = Math.max(maximumWorldY, result.worldY());
                    minimumWorldZ = Math.min(minimumWorldZ, result.worldZ());
                    maximumWorldZ = Math.max(maximumWorldZ, result.worldZ());
                    errorImage.setRGB(x, imageY, errorColor(result.pixelError()));
                } catch (IllegalArgumentException error) {
                    invalid++;
                    errorImage.setRGB(x, imageY, 0xFFFF00FF);
                }
            }
        }

        return new DepthReconstructionStatistics(samples, clear, valid, finite, invalid,
                maximumPixelError, finite == 0 ? Double.NaN : pixelErrorSum / finite,
                maximumDepthError, finite == 0 ? Double.NaN : depthErrorSum / finite,
                minimumWorldX, maximumWorldX, minimumWorldY, maximumWorldY, minimumWorldZ, maximumWorldZ,
                depthImage, errorImage);
    }

    public boolean passed() {
        return samples > 0 && clearSamples > 0 && validSamples > 0 && finiteResults == validSamples
                && invalidSamples == 0 && maximumPixelError <= MAXIMUM_PIXEL_ERROR
                && maximumDepthError <= MAXIMUM_DEPTH_ERROR;
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "samples=%d,clear=%d,valid=%d,finite=%d,invalid=%d,pixelError={max=%.9g,mean=%.9g},"
                        + "depthError={max=%.9g,mean=%.9g},world={x=[%.6f,%.6f],y=[%.6f,%.6f],z=[%.6f,%.6f]}",
                samples, clearSamples, validSamples, finiteResults, invalidSamples,
                maximumPixelError, meanPixelError, maximumDepthError, meanDepthError,
                minimumWorldX, maximumWorldX, minimumWorldY, maximumWorldY, minimumWorldZ, maximumWorldZ);
    }

    private static int depthColor(float depth) {
        if (!Float.isFinite(depth) || depth < 0.0F || depth > 1.0F) return 0xFFFF00FF;
        int gray = Math.clamp(Math.round(depth * 255.0F), 0, 255);
        return 0xFF000000 | gray << 16 | gray << 8 | gray;
    }

    /**
     * 再投影ピクセル誤差の大きさを診断画像の色へ変換する。
     *
     * @param pixelError 再投影による入力ピクセル位置の誤差
     * @return ピクセル誤差の大きさに対応する診断画像の色コード
     */
    private static int errorColor(double pixelError) {
        double normalized = Math.clamp(pixelError / MAXIMUM_PIXEL_ERROR, 0.0, 1.0);
        int red = (int) Math.round(normalized * 255.0);
        int blue = (int) Math.round((1.0 - normalized) * 96.0);
        return 0xFF000000 | red << 16 | blue;
    }
}
