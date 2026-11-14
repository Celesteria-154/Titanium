package celeste.titanium.client.render;

import org.joml.Matrix4d;
import org.joml.Vector4d;

/** フレームの投影行列を用いて、左下原点のピクセル中心とワールド座標を相互変換する。 */
public final class DepthReconstruction {
    private static final double MINIMUM_W = 1.0e-12;

    private DepthReconstruction() {
    }

    /**
     * 確定済みフレームの投影・モデルビュー・カメラ位置から深度復元変換を構築する。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @return 同じフレームの実投影・モデルビューを使う深度復元変換
     */
    public static Transform forPacket(FrameInputPacket packet) {
        if (packet == null || !packet.valid()) {
            throw new IllegalArgumentException("A valid frame packet is required");
        }
        return new Transform(packet);
    }

    public static final class Transform {
        private final Matrix4d projection;
        private final Matrix4d modelView;
        private final Matrix4d inverseProjection;
        private final Matrix4d inverseModelView;
        private final double cameraX;
        private final double cameraY;
        private final double cameraZ;
        private final int width;
        private final int height;
        private final DepthClipRange depthClipRange;

        /**
         * 入力パケットの行列を倍精度へ複製し、実投影とモデルビューの逆行列を準備する。
         *
         * @param packet 同じ描画に使用した確定済み入力パケット
         */
        private Transform(FrameInputPacket packet) {
            depthClipRange = DepthClipRange.forPacket(packet);
            projection = new Matrix4d(packet.renderProjection());
            modelView = new Matrix4d(packet.modelView());
            inverseProjection = new Matrix4d(projection).invert();
            inverseModelView = new Matrix4d(modelView).invert();
            cameraX = packet.cameraPosition().x;
            cameraY = packet.cameraPosition().y;
            cameraZ = packet.cameraPosition().z;
            width = packet.width();
            height = packet.height();
        }

        /**
         * ピクセル中心と深度をワールド座標へ復元し、同じ行列へ戻して往復誤差を求める。
         *
         * @param x GL左下原点の現在入力ピクセルのX座標
         * @param y GL左下原点の現在入力ピクセルのY座標
         * @param depth 現在ピクセルの[0,1]深度
         * @return 復元した座標、再投影位置とピクセル・深度の往復誤差
         */
        public Result roundTrip(int x, int y, float depth) {
            if (x < 0 || x >= width || y < 0 || y >= height) {
                throw new IllegalArgumentException("Pixel is outside the frame");
            }
            if (!Float.isFinite(depth) || depth < 0.0F || depth > 1.0F) {
                throw new IllegalArgumentException("Window depth must be finite and within [0,1]");
            }

            double ndcX = 2.0 * (x + 0.5) / width - 1.0;
            double ndcY = 2.0 * (y + 0.5) / height - 1.0;
            Vector4d view = inverseProjection.transform(new Vector4d(ndcX, ndcY, depthClipRange.toNdc(depth), 1.0));
            perspectiveDivide(view, "inverse projection");

            Vector4d cameraRelative = inverseModelView.transform(new Vector4d(view.x, view.y, view.z, 1.0));
            perspectiveDivide(cameraRelative, "inverse model-view");
            // モデルビューの逆変換で得たカメラ相対位置へ、フレーム取得時のカメラ座標を加える。
            double worldX = cameraRelative.x + cameraX;
            double worldY = cameraRelative.y + cameraY;
            double worldZ = cameraRelative.z + cameraZ;

            // 復元座標を同じ行列で再投影し、ピクセル中心と深度の往復誤差を評価する。
            Vector4d roundTripView = modelView.transform(new Vector4d(
                    worldX - cameraX, worldY - cameraY, worldZ - cameraZ, 1.0));
            Vector4d roundTripClip = projection.transform(roundTripView);
            perspectiveDivide(roundTripClip, "round-trip projection");
            double pixelX = (roundTripClip.x + 1.0) * width / 2.0 - 0.5;
            double pixelY = (roundTripClip.y + 1.0) * height / 2.0 - 0.5;
            double pixelError = Math.hypot(pixelX - x, pixelY - y);
            double windowDepth = depthClipRange.toWindow(roundTripClip.z);
            double depthError = Math.abs(windowDepth - depth);

            Result result = new Result(cameraRelative.x, cameraRelative.y, cameraRelative.z,
                    worldX, worldY, worldZ, pixelX, pixelY, windowDepth, pixelError, depthError);
            if (!result.finite()) {
                throw new IllegalArgumentException("Depth reconstruction produced a non-finite result");
            }
            return result;
        }
    }

    /**
     * 同次座標の有限性とwを確認し、xyzをwで除算して正規化する。
     *
     * @param value 正規化する同次座標
     * @param stage エラー時に深度復元の段階を示す識別名
     */
    private static void perspectiveDivide(Vector4d value, String stage) {
        if (!value.isFinite() || Math.abs(value.w) <= MINIMUM_W) {
            throw new IllegalArgumentException(stage + " produced an invalid homogeneous w");
        }
        value.div(value.w);
    }

    public record Result(
            double cameraRelativeX,
            double cameraRelativeY,
            double cameraRelativeZ,
            double worldX,
            double worldY,
            double worldZ,
            double pixelX,
            double pixelY,
            double depth,
            double pixelError,
            double depthError) {
        public boolean finite() {
            return Double.isFinite(cameraRelativeX) && Double.isFinite(cameraRelativeY)
                    && Double.isFinite(cameraRelativeZ) && Double.isFinite(worldX)
                    && Double.isFinite(worldY) && Double.isFinite(worldZ)
                    && Double.isFinite(pixelX) && Double.isFinite(pixelY) && Double.isFinite(depth)
                    && Double.isFinite(pixelError) && Double.isFinite(depthError);
        }
    }
}
