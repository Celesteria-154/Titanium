package celeste.titanium.client.render;

import celeste.titanium.client.debug.DlssModel;

/** NGXへ渡すプリセット指定値。ランタイムが選択した実モデルは別途取得できない。 */
public enum DlssPreset {
    SDK_DEFAULT(0, DlssModel.SDK_DEFAULT),
    J(10, DlssModel.PRESET_J),
    K(11, DlssModel.PRESET_K),
    L(12, DlssModel.PRESET_L),
    M(13, DlssModel.PRESET_M);

    static final String DEBUG_PROPERTY = "titanium.debugDlssPreset";

    private final int nativeValue;
    private final DlssModel debugModel;

    DlssPreset(int nativeValue, DlssModel debugModel) {
        this.nativeValue = nativeValue;
        this.debugModel = debugModel;
    }

    int nativeValue() {
        return nativeValue;
    }

    public DlssModel debugModel() {
        return debugModel;
    }

    public static DlssPreset configured() {
        String override = System.getProperty(DEBUG_PROPERTY, "");
        return override.isBlank() ? celeste.titanium.client.TitaniumClient.config().dlssPreset()
                : parse(override, Boolean.getBoolean("titanium.qualityNormalClient"));
    }

    /**
     * プリセット名を解析し、手動Hintを利用できる実行条件を確認する。
     *
     * @param value 変換・検証・表示の対象値
     * @param qualityClient 独立画質診断である場合はtrue
     * @return 指定したDefaultまたは手動プリセットのHint
     */
    static DlssPreset parse(String value, boolean qualityClient) {
        if (value == null || value.isBlank()) return SDK_DEFAULT;
        DlssPreset preset;
        try { preset = valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown DLSS debug preset: " + value, error);
        }
        if (!qualityClient) throw new IllegalStateException("Debug preset override requires the quality harness");
        return preset;
    }
}
