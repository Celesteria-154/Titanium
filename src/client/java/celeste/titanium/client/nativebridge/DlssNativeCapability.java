package celeste.titanium.client.nativebridge;

/** DLSSセッション開始前に確認する、ネイティブ実装とランタイムの対応状態。 */
public record DlssNativeCapability(boolean ready, String reason) {
    public DlssNativeCapability {
        reason = reason == null ? "" : reason;
        if (!ready && reason.isBlank()) reason = "DLSS native capability unavailable";
    }

    public static DlssNativeCapability available() {
        return new DlssNativeCapability(true, "");
    }

    public static DlssNativeCapability unavailable(String reason) {
        return new DlssNativeCapability(false, reason);
    }
}
