package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.render.enhanced.EnhancedShaderRuntime;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GLシェーダーのコンパイル・リンク・使用を追跡する。無効時のGL問い合わせは追加しない。 */
@Mixin(GlStateManager.class)
abstract class EnhancedGlShaderMixin {
    /**
     * GLへ渡すシェーダー文字列をEnhancedの限定変換へ通す。
     *
     * @param value 変換・検証・表示の対象値
     * @param shader コンパイル・追跡対象のGLシェーダー識別子
     * @param source 変換またはコンパイルするGLSLソース
     * @return Enhancedの限定変換後、または対象外時の元ソース
     */
    @ModifyVariable(method="glShaderSource",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private static String titanium$source(String value,int shader,String source){return EnhancedShaderRuntime.source(shader,value);}
    /**
     * コンパイル結果を検査し、変換版が失敗した場合は元ソースへ復元する。
     *
     * @param shader コンパイル・追跡対象のGLシェーダー識別子
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="glCompileShader",at=@At("RETURN"))
    private static void titanium$compile(int shader,CallbackInfo ci){EnhancedShaderRuntime.compiled(shader);}
    /**
     * リンク結果を検査し、担当切り替えuniformの位置を記録する。
     *
     * @param program 対象のGLプログラム識別子
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="glLinkProgram",at=@At("RETURN"))
    private static void titanium$link(int program,CallbackInfo ci){EnhancedShaderRuntime.linked(program);}
    /**
     * 使用するプログラムへ今フレームのジッターと表示色担当を反映する。
     *
     * @param program 対象のGLプログラム識別子
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="_glUseProgram",at=@At("RETURN"))
    private static void titanium$use(int program,CallbackInfo ci){EnhancedShaderRuntime.used(program);}
    /**
     * 削除されるGLプログラムをEnhancedの追跡対象から外す。
     *
     * @param program 対象のGLプログラム識別子
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method="glDeleteProgram",at=@At("HEAD"))
    private static void titanium$delete(int program,CallbackInfo ci){EnhancedShaderRuntime.programDeleted(program);}
}
