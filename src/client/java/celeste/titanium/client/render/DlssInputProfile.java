package celeste.titanium.client.render;

import java.util.Objects;

/** 入力の生成位置と取得範囲を宣言する。MVが対応する動的形状の範囲も区別する。 */
public enum DlssInputProfile {
    VANILLA_CAMERA(DlssProductSession.DisplayPath.VANILLA, MotionSource.CAMERA),
    IRIS_FINAL_CAMERA(DlssProductSession.DisplayPath.IRIS_SOLAS, MotionSource.CAMERA),
    IRIS_ENHANCED_CUTOUT_TAA_CAMERA(DlssProductSession.DisplayPath.IRIS_SOLAS, MotionSource.CAMERA),
    IRIS_ENHANCED_PRE_AA_CAMERA(DlssProductSession.DisplayPath.IRIS_SOLAS, MotionSource.CAMERA),
    VANILLA_IMAGE_TEST(DlssProductSession.DisplayPath.VANILLA, MotionSource.IMAGE_ESTIMATED_TEST),
    IRIS_FINAL_IMAGE_TEST(DlssProductSession.DisplayPath.IRIS_SOLAS, MotionSource.IMAGE_ESTIMATED_TEST),
    IRIS_DECLARED_STATIC_TEST(DlssProductSession.DisplayPath.IRIS_SOLAS, MotionSource.CAMERA),
    IRIS_COOPERATIVE_LDR_TEST(DlssProductSession.DisplayPath.IRIS_SOLAS, MotionSource.CAMERA);

    public enum MotionSource { CAMERA, IMAGE_ESTIMATED_TEST }
    public enum ColorBoundary { WORLD_BEFORE_HAND, IRIS_FINAL_WITH_HAND, IRIS_PRE_MAIN_TAA_WITH_HAND, DECLARED_STATIC_LDR_TEST, COOPERATIVE_PRE_MAIN_TAA_TEST }
    public enum TemporalSource { WORLD_CAPTURE, PACK_FINAL_UNVERIFIED, PACK_MAIN_TAA_BOUNDARY, DECLARED_PRE_TEMPORAL_TEST, COOPERATIVE_MAIN_TAA_BOUNDARY_TEST }

    private final DlssProductSession.DisplayPath path;
    private final MotionSource motion;

    DlssInputProfile(DlssProductSession.DisplayPath path, MotionSource motion) {
        this.path = path;
        this.motion = motion;
    }

    /**
     * 実行経路とMVの取得方法を組み合わせて入力プロファイルを選ぶ。
     *
     * @param path VanillaまたはIrisの描画経路
     * @param motion MVの生成方法または入力リソース
     * @return 描画経路とMVの生成方法に対応する入力プロファイル
     */
    public static DlssInputProfile forPath(DlssProductSession.DisplayPath path, MotionSource motion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(motion, "motion");
        return path == DlssProductSession.DisplayPath.VANILLA
                ? motion == MotionSource.CAMERA ? VANILLA_CAMERA : VANILLA_IMAGE_TEST
                : motion == MotionSource.CAMERA ? IRIS_FINAL_CAMERA : IRIS_FINAL_IMAGE_TEST;
    }

    /**
     * 色を取得した描画段階を返す。
     *
     * @return 色を取得した描画パイプライン上の境界
     */
    public ColorBoundary colorBoundary() {
        if(this==IRIS_ENHANCED_PRE_AA_CAMERA||this==IRIS_ENHANCED_CUTOUT_TAA_CAMERA)return ColorBoundary.IRIS_PRE_MAIN_TAA_WITH_HAND;
        if(this==IRIS_COOPERATIVE_LDR_TEST)return ColorBoundary.COOPERATIVE_PRE_MAIN_TAA_TEST;
        if(this==IRIS_DECLARED_STATIC_TEST)return ColorBoundary.DECLARED_STATIC_LDR_TEST;
        return path == DlssProductSession.DisplayPath.VANILLA
                ? ColorBoundary.WORLD_BEFORE_HAND : ColorBoundary.IRIS_FINAL_WITH_HAND;
    }

    public MotionSource motionSource() { return motion; }
    /**
     * 入力色とパックTAAの時間方向処理の境界を返す。
     *
     * @return パックTAAと入力取得の時間方向処理の境界
     */
    public TemporalSource temporalSource() {
        if(this==IRIS_ENHANCED_PRE_AA_CAMERA||this==IRIS_ENHANCED_CUTOUT_TAA_CAMERA)return TemporalSource.PACK_MAIN_TAA_BOUNDARY;
        if(this==IRIS_COOPERATIVE_LDR_TEST)return TemporalSource.COOPERATIVE_MAIN_TAA_BOUNDARY_TEST;
        if(this==IRIS_DECLARED_STATIC_TEST)return TemporalSource.DECLARED_PRE_TEMPORAL_TEST;
        return colorBoundary() == ColorBoundary.WORLD_BEFORE_HAND
                ? TemporalSource.WORLD_CAPTURE : TemporalSource.PACK_FINAL_UNVERIFIED;
    }
    /**
     * 履歴処理の取得境界と対応範囲をデバッグ表示用に説明する。
     *
     * @return 履歴の取得境界と検証範囲を示す診断文
     */
    public String temporalDescription() {
        if(this==IRIS_ENHANCED_CUTOUT_TAA_CAMERA)return "Pack TAA for accepted cutout; DLAA for remaining scene";
        if(this==IRIS_ENHANCED_PRE_AA_CAMERA)return "Before pack main TAA; other histories unchanged";
        if(this==IRIS_COOPERATIVE_LDR_TEST)return "Cooperative pre-main-TAA (test; other history unverified)";
        if(this==IRIS_DECLARED_STATIC_TEST)return "Declared static fixture (test)";
        return temporalSource() == TemporalSource.WORLD_CAPTURE
                ? "World capture (no pack)" : "Pack TAA / jitter unverified";
    }
    /**
     * 入力色の取得段階と手の包含範囲をデバッグ表示用に説明する。
     *
     * @return 色の取得段階と一人称の手の包含範囲を示す診断文
     */
    public String colorDescription() {
        if(this==IRIS_ENHANCED_CUTOUT_TAA_CAMERA)return "Pre-main-TAA LDR; cutout retains pack TAA";
        if(this==IRIS_ENHANCED_PRE_AA_CAMERA)return "Iris pre-main-TAA LDR + hand";
        if(this==IRIS_COOPERATIVE_LDR_TEST)return "Cooperative encoded LDR (test)";
        if(this==IRIS_DECLARED_STATIC_TEST)return "Declared static LDR (test)";
        return colorBoundary() == ColorBoundary.WORLD_BEFORE_HAND ? "World before hand" : "Iris final (LDR + hand)";
    }
    public String motionDescription() {
        return motion == MotionSource.CAMERA ? "Camera only" : "Image estimated (test)";
    }
    public String handMotionDescription() {
        if(this==IRIS_DECLARED_STATIC_TEST)return "Excluded by fixed test fixture";
        return path == DlssProductSession.DisplayPath.VANILLA ? "Outside DLSS input" : "Unsupported";
    }

    /**
     * 入力プロファイルの描画経路と深度方向が、DLSS要求に一致するか判定する。
     *
     * @param request 現在の寸法・品質・深度方向・描画経路を持つ要求
     * @return 入力プロファイルの描画経路と深度方向が、DLSS要求に一致する場合はtrue
     */
    public boolean matches(DlssProductSession.Request request) {
        return request != null && request.displayPath() == path
                && request.depthInverted() == (path == DlssProductSession.DisplayPath.VANILLA);
    }

    /**
     * プロファイルがDLSS要求に対応することを確認し、混在時は例外を送出する。
     *
     * @param request 現在の寸法・品質・深度方向・描画経路を持つ要求
     */
    public void requireMatching(DlssProductSession.Request request) {
        if (!matches(request)) throw new IllegalStateException("DLSS producer profile does not match the request: " + this);
    }
}
