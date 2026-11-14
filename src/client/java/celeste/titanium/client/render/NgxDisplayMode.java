package celeste.titanium.client.render;

enum NgxDisplayMode {
    HIDDEN("P10A", "hidden"),
    VANILLA("P10B", "Vanilla"),
    IRIS("P10C", "Iris/Solas");

    private final String phase;
    private final String label;

    NgxDisplayMode(String phase, String label) {
        this.phase = phase;
        this.label = label;
    }

    /**
     * 表示診断の起動指定を解析し、非表示・Vanilla表示・Iris表示の経路を選ぶ。
     *
     * @return 検証済み起動指定に対応する非表示・Vanilla・Irisの診断表示モード
     */
    static NgxDisplayMode configured() {
        boolean vanilla = Boolean.getBoolean("titanium.ngxVanillaDisplayProbe");
        boolean iris = Boolean.getBoolean("titanium.ngxIrisDisplayProbe");
        if (vanilla && iris) throw new IllegalStateException("Multiple NGX display probes are enabled");
        if (vanilla) return VANILLA;
        if (iris) return IRIS;
        return HIDDEN;
    }

    boolean visible() {
        return this != HIDDEN;
    }

    String phase() {
        return phase;
    }

    String label() {
        return label;
    }
}
