package celeste.titanium.client.debug;

public enum DlssModel {
    AUTO("Auto"),
    SDK_DEFAULT("SDK Default"),
    PRESET_J("J"),
    PRESET_K("K"),
    PRESET_L("L"),
    PRESET_M("M"),
    UNAVAILABLE("Unavailable");

    private final String label;

    DlssModel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
