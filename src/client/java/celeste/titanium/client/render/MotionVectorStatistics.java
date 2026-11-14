package celeste.titanium.client.render;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** RG32FのGPU出力とCPU参照MVの全ピクセル誤差を集計する。 */
public record MotionVectorStatistics(
        int samples,
        int clearSamples,
        int validSamples,
        int finiteGpuSamples,
        int invalidSamples,
        int nonZeroSamples,
        int nonZeroClearSamples,
        double maximumMagnitude,
        double meanMagnitude,
        double maximumComponentError,
        double meanComponentError,
        double maximumReprojectionResidual,
        boolean reset,
        boolean cameraChanged,
        BufferedImage vectorImage) {
    public static final double MAXIMUM_COMPONENT_ERROR = 0.05;
    public static final double MAXIMUM_REPROJECTION_RESIDUAL = 0.05;
    public static final double MAXIMUM_STATIC_MAGNITUDE = 0.01;
    private static final double NON_ZERO_EPSILON = 1.0e-4;

    /**
     * 同じ深度と入力ペアで求めたCPU参照MVをGPU値と比較し、誤差・有効範囲・可視画像を集計する。
     *
     * @param motionSource ピクセルごとに2成分のMVを格納した読み戻し
     * @param depthSource MVと対になる深度floatの読み戻し
     * @param previous 参照する直前の確定済み入力パケット
     * @param current 解析対象の現在フレーム
     * @param clearEndpoint 背景として扱う深度の端点
     * @return GPUとCPUのMV誤差、有効サンプル数と可視化画像
     */
    public static MotionVectorStatistics analyze(ByteBuffer motionSource, ByteBuffer depthSource,
                                                  FrameInputPacket previous, FrameInputPacket current,
                                                  DepthStatistics.Endpoint clearEndpoint) {
        if (clearEndpoint == DepthStatistics.Endpoint.NONE) {
            throw new IllegalArgumentException("A ZERO or ONE clear endpoint is required");
        }
        int samples = Math.multiplyExact(current.width(), current.height());
        requireRemaining(motionSource, Math.multiplyExact(samples, 2 * Float.BYTES), "Motion-vector");
        requireRemaining(depthSource, Math.multiplyExact(samples, Float.BYTES), "Depth");
        ByteBuffer motion = motionSource.duplicate().order(ByteOrder.nativeOrder());
        ByteBuffer depth = depthSource.duplicate().order(ByteOrder.nativeOrder());
        CameraMotionVector.Transform reference = CameraMotionVector.between(previous, current);
        BufferedImage image = new BufferedImage(current.width(), current.height(), BufferedImage.TYPE_INT_ARGB);
        float clearDepth = clearEndpoint == DepthStatistics.Endpoint.ZERO ? 0.0F : 1.0F;
        int clear = 0;
        int valid = 0;
        int finite = 0;
        int invalid = 0;
        int nonZero = 0;
        int nonZeroClear = 0;
        double maxMagnitude = 0.0;
        double magnitudeSum = 0.0;
        double maxComponentError = 0.0;
        double componentErrorSum = 0.0;
        double maxResidual = 0.0;

        for (int y = 0; y < current.height(); y++) {
            int imageY = current.height() - 1 - y;
            for (int x = 0; x < current.width(); x++) {
                int index = y * current.width() + x;
                float gpuX = motion.getFloat(index * 2 * Float.BYTES);
                float gpuY = motion.getFloat(index * 2 * Float.BYTES + Float.BYTES);
                float rawDepth = depth.getFloat(index * Float.BYTES);
                if (!Float.isFinite(rawDepth) || rawDepth < 0.0F || rawDepth > 1.0F
                        || !Float.isFinite(gpuX) || !Float.isFinite(gpuY)) {
                    invalid++;
                    image.setRGB(x, imageY, 0xFFFF00FF);
                    continue;
                }
                finite++;
                if (rawDepth == clearDepth) {
                    clear++;
                    if (gpuX != 0.0F || gpuY != 0.0F) nonZeroClear++;
                    image.setRGB(x, imageY, gpuX == 0.0F && gpuY == 0.0F ? 0xFF000000 : 0xFFFF00FF);
                    continue;
                }
                valid++;
                try {
                    CameraMotionVector.Result cpu = reference.sample(x, y, rawDepth);
                    double magnitude = Math.hypot(gpuX, gpuY);
                    double componentError = Math.max(Math.abs(gpuX - cpu.motionX()),
                            Math.abs(gpuY - cpu.motionY()));
                    double currentTopLeftY = current.height() - 1.0 - cpu.currentLowerLeftY();
                    double previousTopLeftY = current.height() - 1.0 - cpu.previousLowerLeftY();
                    double residual = Math.hypot(cpu.currentLowerLeftX() + gpuX - cpu.previousLowerLeftX(),
                            currentTopLeftY + gpuY - previousTopLeftY);
                    if (magnitude > NON_ZERO_EPSILON) nonZero++;
                    maxMagnitude = Math.max(maxMagnitude, magnitude);
                    magnitudeSum += magnitude;
                    maxComponentError = Math.max(maxComponentError, componentError);
                    componentErrorSum += componentError;
                    maxResidual = Math.max(maxResidual, residual);
                    image.setRGB(x, imageY, vectorColor(gpuX, gpuY, magnitude));
                } catch (IllegalArgumentException error) {
                    invalid++;
                    image.setRGB(x, imageY, 0xFFFF00FF);
                }
            }
        }

        return new MotionVectorStatistics(samples, clear, valid, finite, invalid, nonZero, nonZeroClear,
                maxMagnitude, valid == 0 ? Double.NaN : magnitudeSum / valid,
                maxComponentError, valid == 0 ? Double.NaN : componentErrorSum / valid,
                maxResidual, false, current.cameraChangedFrom(previous), image);
    }

    /**
     * 履歴開始時の全MVが許容誤差内の0であるか集計する。
     *
     * @param motionSource ピクセルごとに2成分のMVを格納した読み戻し
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @return 履歴開始時のMVの0からの誤差と可視化画像
     */
    public static MotionVectorStatistics analyzeReset(ByteBuffer motionSource, int width, int height) {
        int samples = Math.multiplyExact(width, height);
        requireRemaining(motionSource, Math.multiplyExact(samples, 2 * Float.BYTES), "Motion-vector");
        ByteBuffer motion = motionSource.duplicate().order(ByteOrder.nativeOrder());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int finite = 0;
        int invalid = 0;
        int nonZero = 0;
        double maxMagnitude = 0.0;
        for (int y = 0; y < height; y++) {
            int imageY = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                float gpuX = motion.getFloat(index * 2 * Float.BYTES);
                float gpuY = motion.getFloat(index * 2 * Float.BYTES + Float.BYTES);
                if (!Float.isFinite(gpuX) || !Float.isFinite(gpuY)) {
                    invalid++;
                    image.setRGB(x, imageY, 0xFFFF00FF);
                    continue;
                }
                finite++;
                double magnitude = Math.hypot(gpuX, gpuY);
                maxMagnitude = Math.max(maxMagnitude, magnitude);
                if (gpuX != 0.0F || gpuY != 0.0F) nonZero++;
                image.setRGB(x, imageY, gpuX == 0.0F && gpuY == 0.0F ? 0xFF000000 : 0xFFFF00FF);
            }
        }
        return new MotionVectorStatistics(samples, samples, 0, finite, invalid, nonZero, nonZero,
                maxMagnitude, 0.0, 0.0, 0.0, 0.0, true, false, image);
    }

    /**
     * この診断が要求する完了数と誤差条件を満たしたか判定する。
     *
     * @return この診断が要求する完了数と誤差条件を満たす場合はtrue
     */
    public boolean passed() {
        if (samples <= 0 || invalidSamples != 0 || finiteGpuSamples != samples || nonZeroClearSamples != 0) {
            return false;
        }
        if (reset) return nonZeroSamples == 0 && maximumMagnitude == 0.0;
        if (clearSamples <= 0 || validSamples <= 0
                || maximumComponentError > MAXIMUM_COMPONENT_ERROR
                || maximumReprojectionResidual > MAXIMUM_REPROJECTION_RESIDUAL) {
            return false;
        }
        return cameraChanged ? nonZeroSamples > 0 : maximumMagnitude <= MAXIMUM_STATIC_MAGNITUDE;
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "samples=%d,clear=%d,valid=%d,finite=%d,invalid=%d,nonZero=%d,nonZeroClear=%d,"
                        + "magnitude={max=%.9g,mean=%.9g},componentError={max=%.9g,mean=%.9g},"
                        + "reprojectionMax=%.9g,reset=%s,cameraChanged=%s",
                samples, clearSamples, validSamples, finiteGpuSamples, invalidSamples, nonZeroSamples,
                nonZeroClearSamples, maximumMagnitude, meanMagnitude, maximumComponentError,
                meanComponentError, maximumReprojectionResidual, reset, cameraChanged);
    }

    /**
     * 読み戻しバッファが、要求した解析範囲を格納できる長さであるか確認する。
     *
     * @param buffer 長さを検証する読み戻しバイト列
     * @param bytes 解析に必要なバイト数
     * @param name 対象または診断区間を識別する名前
     */
    private static void requireRemaining(ByteBuffer buffer, int bytes, String name) {
        if (buffer.remaining() < bytes) throw new IllegalArgumentException(name + " buffer is too small");
    }

    /**
     * MVの方向と大きさを可視化画像の色へ変換する。
     *
     * @param x X座標またはX方向成分
     * @param y Y座標またはY方向成分
     * @param magnitude MVの大きさ。ピクセル単位
     * @return 方向とピクセル単位の大きさに対応する診断画像の色コード
     */
    private static int vectorColor(float x, float y, double magnitude) {
        if (magnitude <= NON_ZERO_EPSILON) return 0xFF000000;
        float hue = (float) ((Math.atan2(y, x) / (Math.PI * 2.0) + 1.0) % 1.0);
        float brightness = (float) Math.clamp(magnitude / 32.0, 0.15, 1.0);
        return 0xFF000000 | Color.HSBtoRGB(hue, 1.0F, brightness) & 0x00FFFFFF;
    }
}
