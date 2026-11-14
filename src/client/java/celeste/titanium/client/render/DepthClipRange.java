package celeste.titanium.client.render;

import org.joml.Matrix4fc;

/** Projection NDC range, distinct from the [0,1] window-depth texture sent to NGX. */
public enum DepthClipRange {
    ZERO_TO_ONE, MINUS_ONE_TO_ONE;

    /**
     * 投影行列の近遠端を調べ、NDC深度が[-1,1]か[0,1]かを選択する。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @return 実投影に対応するNDC深度範囲
     */
    public static DepthClipRange forPacket(FrameInputPacket packet) {
        Matrix4fc projection = packet.unjitteredProjection();
        // Supported perspective projections place either the near or far endpoint at -1.
        // Identity/synthetic zero-to-one fixtures retain their existing convention.
        double near = endpoint(projection, packet.nearPlane());
        double far = endpoint(projection, packet.farPlane());
        return Math.abs(near + 1.0) < 0.001 || Math.abs(far + 1.0) < 0.001
                ? MINUS_ONE_TO_ONE : ZERO_TO_ONE;
    }

    /**
     * 視点から指定距離の点を投影し、そのNDC深度を求める。
     *
     * @param projection 対象フレームの投影行列
     * @param distance 視点から投影する点までの距離
     * @return 指定距離の点を投影したNDC深度
     */
    private static double endpoint(Matrix4fc projection, float distance) {
        return (projection.m32() - projection.m22() * (double) distance)
                / (projection.m33() - projection.m23() * (double) distance);
    }

    /**
     * テクスチャに格納された[0,1]の深度を、この投影方式のNDC深度へ換算する。
     *
     * @param windowDepth 深度テクスチャに格納された[0,1]の値
     * @return この投影方式のNDC範囲へ換算した深度
     */
    public double toNdc(double windowDepth) {
        return this == MINUS_ONE_TO_ONE ? 2.0 * windowDepth - 1.0 : windowDepth;
    }

    /**
     * NDC深度をテクスチャ形式の[0,1]深度へ換算する。
     *
     * @param ndcDepth 投影方式に対応する正規化デバイス深度
     * @return [0,1]のテクスチャ範囲へ換算した深度
     */
    public double toWindow(double ndcDepth) {
        return this == MINUS_ONE_TO_ONE ? (ndcDepth + 1.0) * 0.5 : ndcDepth;
    }
}
