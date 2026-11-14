package celeste.titanium.client.render;

import java.util.Locale;

/** Titaniumのサンプル方向のジッターを、NGXに渡す画面変位へ変換する。 */
public enum DlssJitterFeed {
    PROJECTION_DISPLACEMENT(-1.0),
    LEGACY_SAMPLE_DIRECTION(1.0);

    private final double multiplier;

    DlssJitterFeed(double multiplier) {
        this.multiplier = multiplier;
    }

    /**
     * サンプル方向の入力ジッターを、選択したNGX変位規約の符号へ変換する。
     *
     * @param value 変換・検証・表示の対象値
     * @return 選択した規約の符号を適用した入力ピクセル単位のジッター
     */
    double apply(double value) {
        return value * multiplier;
    }

    /**
     * 診断指定を検証し、NGXへ渡すジッターの符号規約を解決する。
     *
     * @return 通常値または検証済みの明示診断指定から解決した符号規約
     */
    public static DlssJitterFeed configured() {
        String value = System.getProperty("titanium.debugDlssJitterFeed", "PROJECTION_DISPLACEMENT")
                .trim().toUpperCase(Locale.ROOT);
        DlssJitterFeed feed;
        try {
            feed = valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown DLSS debug jitter feed: " + value, error);
        }
        if (feed != PROJECTION_DISPLACEMENT && !Boolean.getBoolean("titanium.qualityNormalClient")) {
            throw new IllegalStateException("Legacy DLSS jitter is restricted to the normal-client quality harness");
        }
        return feed;
    }
}
