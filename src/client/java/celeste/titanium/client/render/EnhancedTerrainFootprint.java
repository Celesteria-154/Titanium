package celeste.titanium.client.render;

/** EnhancedのBalanced・Ultra Performanceで、入力と出力の解像度比から地形LOD差分を求める。
 * 呼び出し元で細部保持設定と実際のSOLID・CUTOUT描画範囲を確認する。 */
final class EnhancedTerrainFootprint {
    private EnhancedTerrainFootprint() {}
    static boolean allowed(DlssQualityPolicy policy) {
        return policy==DlssQualityPolicy.DEFAULT_BALANCED || policy==DlssQualityPolicy.DEFAULT_ULTRA_PERFORMANCE;
    }
    /**
     * モードの整数入力寸法を検証し、入力と出力の解像度比からミップ差分を求める。
     *
     * @param policy 送信済みモードとHintから決まる入力補正プロファイル
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param outputWidth 出力幅。ピクセル単位
     * @param outputHeight 出力高さ。ピクセル単位
     * @return 入力解像度比に対応するミップ差分。対象外または寸法不一致時は0
     */
    static float bias(DlssQualityPolicy policy,int width,int height,int outputWidth,int outputHeight) {
        if(!allowed(policy) || width<1 || height<1 || outputWidth<1 || outputHeight<1)return 0;
        double scale=policy==DlssQualityPolicy.DEFAULT_BALANCED ? DlssFeatureMode.BALANCED.renderScale()
                : DlssFeatureMode.ULTRA_PERFORMANCE.renderScale();
        if(width!=Math.max(1,(int)Math.round(outputWidth*scale)) || height!=Math.max(1,(int)Math.round(outputHeight*scale)))return 0;
        double ratio=Math.min((double)width/outputWidth,(double)height/outputHeight);
        return ratio>=1?0:(float)(Math.log(ratio)/Math.log(2));
    }
}
