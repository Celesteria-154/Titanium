package celeste.titanium.client.nativebridge;

public record NativeCapabilities(NativeError result, boolean initialized, String adapterDescription,
        long vendorId, long deviceId, long subSystemId, long revision, String adapterLuid,
        long dedicatedVideoMemory, long sharedSystemMemory, int featureLevel,
        int streamlineDllMask, String diagnostics) {
    public boolean hasAllStreamlineDlls() { return streamlineDllMask == 0xF; }
}
