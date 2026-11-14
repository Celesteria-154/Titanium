package celeste.titanium.client.debug;

public enum DlssMode {
    QUALITY("Quality"),
    PERFORMANCE("Performance"),
    ULTRA_PERFORMANCE("Ultra Performance"),
    BALANCED("Balanced"),
    DLAA("DLAA"),
    CUSTOM("Custom");

    private final String label;

    DlssMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
