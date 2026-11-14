package celeste.titanium.client.nativebridge;

/** 同期読み戻しを明示的な診断時に限定する。 */
final class InputReadbackPolicy {
    private InputReadbackPolicy() {}

    static boolean allows(boolean qualityClient, boolean jitterCycle, boolean fixedMotion, boolean transferAudit) {
        return qualityClient && (jitterCycle || fixedMotion && transferAudit);
    }
    static boolean allowsScissorDiagnostic(boolean performanceClient,boolean testMod,String expectation) {
        return performanceClient && testMod && ("BASELINE".equals(expectation) || "FIXED".equals(expectation));
    }
}
