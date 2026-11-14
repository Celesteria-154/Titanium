package celeste.titanium.mixin.client.compat;

import celeste.titanium.client.TitaniumClient;
import celeste.titanium.client.render.enhanced.EnhancedShaderRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** 一人称の手にも同一サンプル位置を適用する。前フレームの手の変形MVは生成しない。 */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pathways.HandRenderer",remap=false)
abstract class IrisEnhancedHandMixin {
    /**
     * 対応する一人称の手の投影へ同じジッターを適用してから、投影バッファへ渡す。
     *
     * @param buffer 手の描画に使用する投影行列バッファ
     * @param matrix 一人称の手の描画へ渡す投影行列
     * @param original Mixin適用前の値または元の描画処理
     * @return 同じジッターを適用した手用投影のGPUバッファスライス
     */
    @WrapOperation(method="setupGlState",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),require=0)
    private GpuBufferSlice titanium$sample(ProjectionMatrixBuffer buffer,Matrix4f matrix,Operation<GpuBufferSlice> original){
        if(!EnhancedShaderRuntime.active())return original.call(buffer,matrix);
        var p=TitaniumClient.pipeline();if(!p.enhancedFrameActive())return original.call(buffer,matrix);
        var j=p.enhancedJitter();return original.call(buffer,new Matrix4f().translation((float)(-2*j.pixelX()/p.enhancedWorldWidth()),(float)(2*j.pixelY()/p.enhancedWorldHeight()),0).mul(matrix));
    }
}
