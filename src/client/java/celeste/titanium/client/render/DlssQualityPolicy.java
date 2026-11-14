package celeste.titanium.client.render;

import java.util.Objects;

/** DLSSモードと送信済みプリセット指定に応じて入力補正を選択する。
 * SDKによる実モデル選択は取得できないため、手動指定にはモード別の補正を継承させない。 */
public enum DlssQualityPolicy {
    DEFAULT_ULTRA_PERFORMANCE(DlssFeatureMode.ULTRA_PERFORMANCE, true),
    DEFAULT_PERFORMANCE(DlssFeatureMode.PERFORMANCE, true),
    DEFAULT_QUALITY(DlssFeatureMode.QUALITY, true),
    DEFAULT_BALANCED(DlssFeatureMode.BALANCED, true),
    DEFAULT_DLAA(DlssFeatureMode.DLAA, true),
    MANUAL_UNVERIFIED(null, false);

    private final DlssFeatureMode mode;
    private final boolean evaluationEligible;

    DlssQualityPolicy(DlssFeatureMode mode, boolean evaluationEligible) {
        this.mode = mode;
        this.evaluationEligible = evaluationEligible;
    }

    /**
     * Featureモードと送信プリセットから、モード別補正または手動指定の共通契約を選ぶ。
     *
     * @param mode 対象のDLSS品質モード
     * @param preset SDKへ送るモデルプリセットのHint
     * @return 送信する品質モードとHintに対応する入力補正プロファイル
     */
    public static DlssQualityPolicy resolve(DlssFeatureMode mode, DlssPreset preset) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(preset, "preset");
        return fromSubmittedHint(mode, preset == DlssPreset.SDK_DEFAULT);
    }

    /**
     * 送信済みHintがDefaultかに応じて、実行モードに属する補正プロファイルを選ぶ。
     *
     * @param mode 対象のDLSS品質モード
     * @param sdkDefault SDKのDefault Hintを送信した場合はtrue
     * @return 実際の品質モードとDefault/手動指定に対応する入力補正プロファイル
     */
    public static DlssQualityPolicy fromSubmittedHint(DlssFeatureMode mode, boolean sdkDefault) {
        Objects.requireNonNull(mode, "mode");
        if (!sdkDefault) return MANUAL_UNVERIFIED;
        return switch (mode) {
            case ULTRA_PERFORMANCE -> DEFAULT_ULTRA_PERFORMANCE;
            case PERFORMANCE -> DEFAULT_PERFORMANCE;
            case QUALITY -> DEFAULT_QUALITY;
            case BALANCED -> DEFAULT_BALANCED;
            case DLAA -> DEFAULT_DLAA;
        };
    }

    public boolean evaluationEligible() { return evaluationEligible; }

    /**
     * 通常地形の専用サンプラー補正を許可する描画経路か判定する。
     *
     * @param path VanillaまたはIrisの描画経路
     * @return 通常地形の専用サンプラー補正を許可する描画経路である場合はtrue
     */
    public static boolean terrainSamplingOverridesAllowed(DlssProductSession.DisplayPath path) {
        return Objects.requireNonNull(path, "path") == DlssProductSession.DisplayPath.VANILLA;
    }

    public String correctionStatus() {
        return irisResolutionMipAllowed() ? "INPUT_MIP_ONLY_OVERALL_UNVERIFIED" : "BASELINE_ONLY_NOT_VALIDATED";
    }

    public boolean irisResolutionMipAllowed() {
        return this == DEFAULT_QUALITY || this == DEFAULT_PERFORMANCE;
    }

    /**
     * Irisの対応モードだけに、入力・出力解像度比から算出したLOD差分を返す。
     *
     * @param renderWidth ワールド入力の幅。ピクセル単位
     * @param renderHeight ワールド入力の高さ。ピクセル単位
     * @param outputWidth 出力幅。ピクセル単位
     * @param outputHeight 出力高さ。ピクセル単位
     * @return 元のLODへ加算するミップ差分。非対象モードでは0
     */
    public float irisResolutionMipBias(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        return switch (this) {
            case DEFAULT_QUALITY -> TerrainLodBias.calculate(renderWidth, renderHeight, outputWidth, outputHeight);
            case DEFAULT_PERFORMANCE -> TerrainLodBias.calculate(renderWidth, renderHeight, outputWidth, outputHeight);
            default -> 0;
        };
    }

    /**
     * 対象のDefaultモードでは不透明地形の最小AFを8、その他では1として返す。
     *
     * @return 対象モードでは8、それ以外では1の最小AF
     */
    public int solidTerrainAnisotropy() {
        return this == DEFAULT_DLAA || this == DEFAULT_QUALITY ? 8 : 1;
    }

    /**
     * 補正プロファイルと要求モードを照合し、DLAAでは0、縮小時は解像度差のLOD補正を求める。
     *
     * @param requestedMode 補正を適用する実際のFeature品質モード
     * @param renderWidth ワールド入力の幅。ピクセル単位
     * @param renderHeight ワールド入力の高さ。ピクセル単位
     * @param outputWidth 出力幅。ピクセル単位
     * @param outputHeight 出力高さ。ピクセル単位
     * @return 解像度差を補うミップ差分。DLAAでは0
     */
    public float terrainLodBias(DlssFeatureMode requestedMode, int renderWidth, int renderHeight,
                                int outputWidth, int outputHeight) {
        Objects.requireNonNull(requestedMode, "requestedMode");
        if (mode != null && mode != requestedMode)
            throw new IllegalArgumentException("Correction profile belongs to another DLSS mode");
        // Existing bounded resolution compensation is a common sampling contract, not an
        // empirical preset remedy. SOLID anisotropy is routed independently above;
        // no sharpening or temporal reset is added here.
        return requestedMode == DlssFeatureMode.DLAA ? 0
                : TerrainLodBias.calculate(renderWidth, renderHeight, outputWidth, outputHeight);
    }
}
