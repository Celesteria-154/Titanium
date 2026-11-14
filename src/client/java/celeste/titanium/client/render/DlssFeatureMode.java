package celeste.titanium.client.render;

import celeste.titanium.client.config.DlssQuality;

import java.util.Objects;

/** UI表示名やJNIの整数値から独立したNGX品質モード。 */
public enum DlssFeatureMode {
    PERFORMANCE(0, 0.5),
    QUALITY(1, 2.0 / 3.0),
    // 値2は旧ネイティブAPIのUltraQuality用に予約する。
    BALANCED(4, 0.58),
    ULTRA_PERFORMANCE(5, 1.0 / 3.0),
    DLAA(3, 1.0);

    private final int nativeValue;
    private final double renderScale;

    DlssFeatureMode(int nativeValue, double renderScale) {
        this.nativeValue = nativeValue;
        this.renderScale = renderScale;
    }

    int nativeValue() {
        return nativeValue;
    }

    public double renderScale() {
        return renderScale;
    }

    /**
     * 設定画面の品質モードを、固定JNI値を持つFeatureモードへ対応づける。
     *
     * @param quality DLSS品質の選択または取得済み診断値
     * @return 品質設定に対応するFeatureモード
     */
    public static DlssFeatureMode fromQuality(DlssQuality quality) {
        Objects.requireNonNull(quality, "quality");
        return switch (quality) {
            case PERFORMANCE -> PERFORMANCE;
            case QUALITY -> QUALITY;
            case BALANCED -> BALANCED;
            case ULTRA_PERFORMANCE -> ULTRA_PERFORMANCE;
            case DLAA -> DLAA;
        };
    }
}
