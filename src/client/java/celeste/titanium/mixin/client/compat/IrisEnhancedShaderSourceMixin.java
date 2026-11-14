package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.render.enhanced.EnhancedShaderRuntime;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** 前処理とIris変換が完了した全画面・Sodiumシェーダーソースを取得する。 */
@Pseudo
@Mixin(targets="net.irisshaders.iris.gl.shader.ShaderWorkarounds",remap=false)
abstract class IrisEnhancedShaderSourceMixin {
    // Irisの前処理結果に対して変換を適用し、パックのディスク上のソースはそのまま利用する。
    /**
     * Irisの前処理・変換が完了したソースを、コンパイル前の限定変換へ渡す。
     *
     * @param value GLへ渡す前のシェーダー文字列
     * @param shader コンパイル・追跡対象のGLシェーダー識別子
     * @param source IrisがGLへ渡す前処理済みシェーダーソース
     * @return Iris処理後ソースにEnhancedの限定変換を加えた文字列
     */
    @ModifyVariable(method="safeShaderSource",at=@At("HEAD"),argsOnly=true,ordinal=0,require=0)
    private static CharSequence titanium$source(CharSequence value,int shader,CharSequence source){return EnhancedShaderRuntime.source(shader,value.toString());}
}
