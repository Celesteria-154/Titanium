package celeste.titanium.client.render.enhanced;

import java.util.Optional;

/** シェーダー変換前に必要なGL関数の利用可否を確認する。 */
public final class EnhancedGlRequirements {
    public record Functions(long programUniform1i, long programUniform2f, long getTextureParameteriv,
                            long getTextureLevelParameteriv, long getProgramInterfaceiv) {}
    private EnhancedGlRequirements() {}
    /**
     * Enhancedの変換と検証で必要なGL関数を調べ、未対応の最初の関数名を返す。
     *
     * @param f 必要なGL関数のアドレスをまとめた値
     * @return 最初に取得できなかったGL関数名。すべて使用できる場合は空
     */
    public static Optional<String> missing(Functions f) {
        if (f.programUniform1i() == 0) return Optional.of("glProgramUniform1i");
        if (f.programUniform2f() == 0) return Optional.of("glProgramUniform2f");
        if (f.getTextureParameteriv() == 0) return Optional.of("glGetTextureParameteriv");
        if (f.getTextureLevelParameteriv() == 0) return Optional.of("glGetTextureLevelParameteriv");
        if (f.getProgramInterfaceiv() == 0) return Optional.of("glGetProgramInterfaceiv");
        return Optional.empty();
    }
}
