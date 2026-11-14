package celeste.titanium.client.render;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** 単一のワールド描画で使用したCPU入力を、不変のスナップショットとして保持する。 */
public record FrameInputPacket(
        long sequence,
        Matrix4f unjitteredProjection,
        Matrix4f renderProjection,
        FrameJitter jitter,
        Matrix4f modelView,
        Vec3 cameraPosition,
        float nearPlane,
        float farPlane,
        float fieldOfView,
        int width,
        int height,
        String dimension,
        boolean reset) {
    private static final float MATRIX_EPSILON = 1.0e-6F;

    /**
     * 呼び出し元の更新から入力を独立させるため、渡された可変行列を複製して保持する。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param unjitteredProjection ジッターを適用していない入力投影
     * @param renderProjection ジッター適用後に実描画で使用した投影行列
     * @param jitter 実投影と対応する入力ピクセル単位のサンプル
     * @param modelView 同じワールド描画で使用するモデルビュー行列
     * @param cameraPosition 入力取得時のワールド座標でのカメラ位置
     * @param nearPlane 入力取得時の近クリップ距離
     * @param farPlane 入力取得時の遠クリップ距離
     * @param fieldOfView この入力で実際に使用するFOV。度単位
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param dimension 現在のワールドのディメンション識別子
     * @param reset この入力で時間方向履歴を開始し直す場合はtrue
     */
    public FrameInputPacket {
        unjitteredProjection = new Matrix4f(unjitteredProjection);
        renderProjection = new Matrix4f(renderProjection);
        modelView = new Matrix4f(modelView);
    }

    @Override
    public Matrix4f unjitteredProjection() {
        return new Matrix4f(unjitteredProjection);
    }

    @Override
    public Matrix4f renderProjection() {
        return new Matrix4f(renderProjection);
    }

    @Override
    public Matrix4f modelView() {
        return new Matrix4f(modelView);
    }

    /**
     * 行列の有限性・可逆性、カメラ値、深度範囲、寸法と識別情報の基本条件を確認する。
     *
     * FOVは度単位で0より大きく180未満とし、投影・モデルビューは可逆であることを要求する。
     *
     * @return 全行列・カメラ値・寸法・識別情報が入力契約を満たす場合はtrue
     */
    public boolean valid() {
        return sequence > 0 && unjitteredProjection.isFinite() && renderProjection.isFinite()
                && Math.abs(unjitteredProjection.determinant()) > MATRIX_EPSILON
                && Math.abs(renderProjection.determinant()) > MATRIX_EPSILON && jitter != null
                && Math.abs(modelView.determinant()) > MATRIX_EPSILON
                && Double.isFinite(cameraPosition.x) && Double.isFinite(cameraPosition.y)
                && Double.isFinite(cameraPosition.z) && nearPlane > 0.0F && farPlane > nearPlane
                && Float.isFinite(fieldOfView) && fieldOfView > 0.0F && fieldOfView < 180.0F
                && width > 0 && height > 0 && dimension != null && !dimension.isBlank();
    }

    public boolean cameraChangedFrom(FrameInputPacket previous) {
        return cameraPosition.distanceToSqr(previous.cameraPosition) > 1.0e-12
                || !unjitteredProjection.equals(previous.unjitteredProjection, MATRIX_EPSILON)
                || !modelView.equals(previous.modelView, MATRIX_EPSILON);
    }

    /**
     * 履歴リセットがなく、番号・寸法・ディメンションが連続する入力か判定する。
     *
     * @param previous 参照する直前の確定済み入力パケット
     * @return 履歴リセットがなく、番号・寸法・ディメンションが連続する入力である場合はtrue
     */
    public boolean continuousWith(FrameInputPacket previous) {
        // 直前の連番で、寸法とディメンションも一致する場合だけ時間方向の参照を許可する。
        return !reset && sequence == previous.sequence + 1 && width == previous.width && height == previous.height
                && dimension.equals(previous.dimension);
    }

    /**
     * 渡された行列をコピーし、単一フレームのメタデータとジッターを持つ入力を構築する。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param projection 対象フレームの投影行列
     * @param modelView 同じワールド描画で使用するモデルビュー行列
     * @param cameraPosition 入力取得時のワールド座標でのカメラ位置
     * @param nearPlane 入力取得時の近クリップ距離
     * @param farPlane 入力取得時の遠クリップ距離
     * @param fieldOfView この入力で実際に使用するFOV。度単位
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param dimension 現在のワールドのディメンション識別子
     * @param reset この入力で時間方向履歴を開始し直す場合はtrue
     * @return 行列を複製して保持する、同一フレームの入力パケット
     */
    public static FrameInputPacket create(long sequence, Matrix4fc projection, Matrix4fc modelView,
                                          Vec3 cameraPosition, float nearPlane, float farPlane,
                                          float fieldOfView, int width, int height, String dimension,
                                          boolean reset) {
        return create(sequence, projection, projection, FrameJitter.none(), modelView, cameraPosition,
                nearPlane, farPlane, fieldOfView, width, height, dimension, reset);
    }

    /**
     * 渡された行列をコピーし、単一フレームのメタデータとジッターを持つ入力を構築する。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param unjitteredProjection ジッターを適用していない入力投影
     * @param renderProjection ジッター適用後に実描画で使用した投影行列
     * @param jitter 実投影と対応する入力ピクセル単位のサンプル
     * @param modelView 同じワールド描画で使用するモデルビュー行列
     * @param cameraPosition 入力取得時のワールド座標でのカメラ位置
     * @param nearPlane 入力取得時の近クリップ距離
     * @param farPlane 入力取得時の遠クリップ距離
     * @param fieldOfView この入力で実際に使用するFOV。度単位
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param dimension 現在のワールドのディメンション識別子
     * @param reset この入力で時間方向履歴を開始し直す場合はtrue
     * @return 行列を複製して保持する、同一フレームの入力パケット
     */
    public static FrameInputPacket create(long sequence, Matrix4fc unjitteredProjection,
                                          Matrix4fc renderProjection, FrameJitter jitter,
                                          Matrix4fc modelView, Vec3 cameraPosition, float nearPlane, float farPlane,
                                          float fieldOfView, int width, int height, String dimension, boolean reset) {
        // 可変なJOML行列は複製し、後続の描画や呼び出し元の更新から入力スナップショットを独立させる。
        return new FrameInputPacket(sequence, new Matrix4f(unjitteredProjection), new Matrix4f(renderProjection),
                jitter, new Matrix4f(modelView), cameraPosition,
                nearPlane, farPlane, fieldOfView, width, height, dimension, reset);
    }
}
