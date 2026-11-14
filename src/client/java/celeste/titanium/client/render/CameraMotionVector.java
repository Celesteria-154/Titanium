package celeste.titanium.client.render;

import org.joml.Matrix4d;
import org.joml.Vector4d;

/** 現在の深度から復元したワールド座標を前フレームへ投影し、ピクセル単位のMVを求める。 */
public final class CameraMotionVector {
    private static final double MINIMUM_VISIBLE_W = 1.0e-12;

    private CameraMotionVector() {
    }

    /**
     * 連続する2フレームから、現在の深度を過去画面へ再投影する変換を構築する。
     *
     * @param previous 参照する直前の確定済み入力パケット
     * @param current 解析対象の現在フレーム
     * @return 連続する前後フレームから構築したピクセル再投影変換
     */
    public static Transform between(FrameInputPacket previous, FrameInputPacket current) {
        if (previous == null || current == null || !previous.valid() || !current.valid()) {
            throw new IllegalArgumentException("Valid previous and current frame packets are required");
        }
        if (!current.continuousWith(previous)) {
            throw new IllegalArgumentException("Frame packets must form a continuous non-reset pair");
        }
        return new Transform(previous, current);
    }

    public static final class Transform {
        private final DepthReconstruction.Transform currentTransform;
        private final Matrix4d currentProjection;
        private final Matrix4d currentModelView;
        private final Matrix4d currentInverseProjection;
        private final Matrix4d currentInverseModelView;
        private final DepthClipRange depthClipRange;
        private final double currentCameraX;
        private final double currentCameraY;
        private final double currentCameraZ;
        private final Matrix4d previousProjection;
        private final Matrix4d previousModelView;
        private final double previousCameraX;
        private final double previousCameraY;
        private final double previousCameraZ;
        private final int width;
        private final int height;

        /**
         * 連続入力から深度復元変換、前後の投影・モデルビューとカメラ座標を保持する。
         *
         * @param previous 参照する直前の確定済み入力パケット
         * @param current 解析対象の現在フレーム
         */
        private Transform(FrameInputPacket previous, FrameInputPacket current) {
            // 深度の復元には実描画投影を使い、MVの前後比較にはジッターを除いた投影を使用する。
            currentTransform = DepthReconstruction.forPacket(current);
            currentProjection = new Matrix4d(current.unjitteredProjection());
            currentModelView = new Matrix4d(current.modelView());
            currentInverseProjection = new Matrix4d(current.renderProjection()).invert();
            currentInverseModelView = new Matrix4d(currentModelView).invert();
            depthClipRange = DepthClipRange.forPacket(current);
            currentCameraX = current.cameraPosition().x;
            currentCameraY = current.cameraPosition().y;
            currentCameraZ = current.cameraPosition().z;
            previousProjection = new Matrix4d(previous.unjitteredProjection());
            previousModelView = new Matrix4d(previous.modelView());
            previousCameraX = previous.cameraPosition().x;
            previousCameraY = previous.cameraPosition().y;
            previousCameraZ = previous.cameraPosition().z;
            width = current.width();
            height = current.height();
        }

        /**
         * 現在ピクセルの深度からワールド位置を復元し、前後フレームの画面位置と後方MVを求める。
         *
         * @param x GL左下原点の現在入力ピクセルのX座標
         * @param y GL左下原点の現在入力ピクセルのY座標
         * @param depth 現在ピクセルの[0,1]深度
         * @return 前後の画面位置と入力ピクセル単位の後方MV
         */
        public Result sample(int x, int y, float depth) {
            DepthReconstruction.Result current = currentTransform.roundTrip(x, y, depth);
            Vector4d currentClip = project(currentProjection, currentModelView, current,
                    currentCameraX, currentCameraY, currentCameraZ, "Current");
            Vector4d previousClip = project(previousProjection, previousModelView, current,
                    previousCameraX, previousCameraY, previousCameraZ, "Previous");
            return displacement(currentClip, previousClip);
        }

        /**
         * クリア深度を無限遠方向として復元し、回転だけを反映した背景MVを求める。
         *
         * @param x GL左下原点の現在入力ピクセルのX座標
         * @param y GL左下原点の現在入力ピクセルのY座標
         * @param clearDepth 背景を表す深度のクリア値。0または1
         * @return カメラ平行移動を含めない、無限遠方向の後方MV
         */
        public Result sampleBackground(int x, int y, float clearDepth) {
            if (x < 0 || x >= width || y < 0 || y >= height || (clearDepth != 0 && clearDepth != 1))
                throw new IllegalArgumentException("Valid pixel and clear depth endpoint required");
            // 背景のクリア深度は無限遠の方向として復元する。w=0で変換し、カメラ平行移動の影響を除く。
            Vector4d ray = currentInverseProjection.transform(new Vector4d(
                    2.0 * (x + 0.5) / width - 1, 2.0 * (y + 0.5) / height - 1,
                    depthClipRange.toNdc(clearDepth), 1));
            Vector4d direction = currentInverseModelView.transform(new Vector4d(ray.x, ray.y, ray.z, 0));
            Vector4d currentClip = currentProjection.transform(currentModelView.transform(new Vector4d(direction)));
            Vector4d previousClip = previousProjection.transform(previousModelView.transform(new Vector4d(direction)));
            if (!ray.isFinite() || !direction.isFinite() || !currentClip.isFinite() || !previousClip.isFinite()
                    || currentClip.w <= MINIMUM_VISIBLE_W || previousClip.w <= MINIMUM_VISIBLE_W)
                throw new IllegalArgumentException("Background direction is not visible");
            currentClip.div(currentClip.w);
            previousClip.div(previousClip.w);
            return displacement(currentClip, previousClip);
        }

        /**
         * 前後のクリップ座標を左上原点のピクセルへ変換し、現在から過去への差分を求める。
         *
         * @param currentClip 現在フレームへ投影した同次クリップ座標
         * @param previousClip 前フレームへ投影した同次クリップ座標
         * @return 左上原点の画面位置と、現在から過去へのピクセル変位
         */
        private Result displacement(Vector4d currentClip, Vector4d previousClip) {
            double currentLowerLeftX = (currentClip.x + 1.0) * width / 2.0 - 0.5;
            double currentLowerLeftY = (currentClip.y + 1.0) * height / 2.0 - 0.5;
            double previousLowerLeftX = (previousClip.x + 1.0) * width / 2.0 - 0.5;
            double previousLowerLeftY = (previousClip.y + 1.0) * height / 2.0 - 0.5;
            // Backward displacement; NGX reprojects with current + motion = previous.
            double lowerLeftDeltaX = previousLowerLeftX - currentLowerLeftX;
            double lowerLeftDeltaY = previousLowerLeftY - currentLowerLeftY;
            double motionX = lowerLeftDeltaX;
            double motionY = -lowerLeftDeltaY;
            double reprojectionResidual = Math.hypot(
                    currentLowerLeftX + motionX - previousLowerLeftX,
                    currentLowerLeftY + lowerLeftDeltaY - previousLowerLeftY);

            Result result = new Result(currentLowerLeftX, currentLowerLeftY,
                    previousLowerLeftX, previousLowerLeftY,
                    lowerLeftDeltaX, lowerLeftDeltaY, motionX, motionY, reprojectionResidual);
            if (!result.finite()) {
                throw new IllegalArgumentException("Camera motion-vector reconstruction produced a non-finite result");
            }
            return result;
        }

        /**
         * 復元したワールド位置を指定カメラ基準へ戻し、モデルビューと投影行列でクリップ座標へ写す。
         *
         * @param projection 対象フレームの投影行列
         * @param modelView 同じワールド描画で使用するモデルビュー行列
         * @param current 現在の深度から復元したワールド位置
         * @param cameraX 投影基準となるカメラのワールドX座標
         * @param cameraY 投影基準となるカメラのワールドY座標
         * @param cameraZ 投影基準となるカメラのワールドZ座標
         * @param name 投影に失敗した前後フレームを診断上で区別する名前
         * @return 指定カメラから見た同次クリップ座標
         */
        private static Vector4d project(Matrix4d projection, Matrix4d modelView,
                                        DepthReconstruction.Result current,
                                        double cameraX, double cameraY, double cameraZ, String name) {
            Vector4d view = modelView.transform(new Vector4d(
                    current.worldX() - cameraX,
                    current.worldY() - cameraY,
                    current.worldZ() - cameraZ,
                    1.0));
            Vector4d clip = projection.transform(view);
            if (!clip.isFinite() || clip.w <= MINIMUM_VISIBLE_W) {
                throw new IllegalArgumentException(name + " projection produced a non-visible homogeneous w");
            }
            clip.div(clip.w);
            if (!clip.isFinite()) {
                throw new IllegalArgumentException(name + " projection produced a non-finite point");
            }
            return clip;
        }
    }

    public record Result(
            double currentLowerLeftX,
            double currentLowerLeftY,
            double previousLowerLeftX,
            double previousLowerLeftY,
            double lowerLeftDeltaX,
            double lowerLeftDeltaY,
            double motionX,
            double motionY,
            double reprojectionResidual) {
        public boolean finite() {
            return Double.isFinite(currentLowerLeftX) && Double.isFinite(currentLowerLeftY)
                    && Double.isFinite(previousLowerLeftX) && Double.isFinite(previousLowerLeftY)
                    && Double.isFinite(lowerLeftDeltaX) && Double.isFinite(lowerLeftDeltaY)
                    && Double.isFinite(motionX) && Double.isFinite(motionY)
                    && Double.isFinite(reprojectionResidual);
        }
    }
}
