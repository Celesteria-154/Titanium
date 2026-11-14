package celeste.titanium.client.nativebridge;

/** 明示的に有効化した独立診断にのみ、ネイティブの時間計測を許可する。 */
public final class DiagnosticTimingPolicy {
    private DiagnosticTimingPolicy() {}

    public static boolean allows(boolean benchmark, boolean normalClient, boolean costBreakdown, boolean testMod) {
        return benchmark && normalClient && costBreakdown && testMod;
    }
}
