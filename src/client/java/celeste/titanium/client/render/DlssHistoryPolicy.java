package celeste.titanium.client.render;

import java.util.Locale;

/** 履歴の影響を切り分ける明示的な診断用ポリシー。 */
public enum DlssHistoryPolicy {
    NORMAL(false),
    RESET_EVERY_FRAME(true);

    private static final String DEBUG_PROPERTY = "titanium.debugDlssHistoryPolicy";

    private final boolean resetEveryFrame;

    DlssHistoryPolicy(boolean resetEveryFrame) {
        this.resetEveryFrame = resetEveryFrame;
    }

    boolean reset(boolean packetReset) {
        return packetReset || resetEveryFrame;
    }

    public boolean resetEveryFrame() {
        return resetEveryFrame;
    }

    public static DlssHistoryPolicy configured() {
        return parse(System.getProperty(DEBUG_PROPERTY, "NORMAL"),
                Boolean.getBoolean("titanium.qualityNormalClient"));
    }

    /**
     * 指定された履歴ポリシーを解析し、診断限定値の通常環境での利用を除外する。
     *
     * @param value 変換・検証・表示の対象値
     * @param qualityClient 独立画質診断である場合はtrue
     * @return 指定値と独立診断の実行条件を検証した履歴ポリシー
     */
    static DlssHistoryPolicy parse(String value, boolean qualityClient) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        DlssHistoryPolicy policy;
        try {
            policy = valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown DLSS debug history policy: " + value, error);
        }
        if (policy != NORMAL && !qualityClient) {
            throw new IllegalStateException(
                    "DLSS history overrides are restricted to the normal-client quality harness");
        }
        return policy;
    }
}
