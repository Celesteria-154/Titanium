package celeste.titanium.client.render;
/** 最終カラー処理に必要なGL関数の利用可否を確認する。 */
final class EnhancedFinalRequirements {
    record Functions(long textureParameteri,long textureParameterf,long textureParameteriv,long textureParameterfv,
                     long getTextureParameterfv,long programUniform1f){}
    /**
     * 任意の最終処理で必要となるGL関数がすべて取得できているか判定する。
     *
     * @param f 必要なGL関数のアドレスをまとめた値
     * @return 任意の最終処理で必要となるGL関数がすべて取得できている場合はtrue
     */
    static boolean available(Functions f){return f.textureParameteri()!=0&&f.textureParameterf()!=0&&f.textureParameteriv()!=0
            &&f.textureParameterfv()!=0&&f.getTextureParameterfv()!=0&&f.programUniform1f()!=0;}
    /**
     * テストMODが存在し、許可された独立診断モードが明示されているか判定する。
     *
     * @param mode 独立診断のモード指定
     * @param isolatedTestMod 独立診断用MODが存在する場合はtrue
     * @return テストMODが存在し、許可された独立診断モードが明示されている場合はtrue
     */
    static boolean researchRequested(String mode,boolean isolatedTestMod){return isolatedTestMod&&("ON".equals(mode)||"NOISE".equals(mode));}
    private EnhancedFinalRequirements(){}
}
