package celeste.titanium.client.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** 左上原点のサンプルオフセットを、OpenGLのクリップ座標平行移動へ変換する。 */
final class ProjectionJitter {
    private ProjectionJitter() {
    }

    /**
     * 左上原点の入力ピクセルサンプルをGLクリップ座標の平行移動へ換算し、投影行列へ適用する。
     *
     * 入力ピクセル単位の値を幅・高さで正規化する。Xはサンプル方向から画面変位への符号反転も含む。
     *
     * @param unjittered ジッターを適用していない入力投影
     * @param sample 投影へ適用する入力ピクセル単位のジッター
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @return サンプル位置を反映した新しい投影行列
     */
    static Matrix4f apply(Matrix4fc unjittered, FrameJitter sample, int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Jitter dimensions must be positive");
        // 入力ピクセルをNDCの幅2へ換算する。サンプル方向と画面変位の違いからXの符号を反転する。
        float clipX = (float) (-2.0 * sample.pixelX() / width);
        float clipY = (float) (2.0 * sample.pixelY() / height);
        return new Matrix4f().translation(clipX, clipY, 0.0F).mul(unjittered);
    }
}
