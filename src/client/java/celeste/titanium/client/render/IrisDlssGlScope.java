package celeste.titanium.client.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;

/** Irisの描画途中でMV・DLSS処理を実行した後、元のGL描画状態を復元する。 */
final class IrisDlssGlScope implements AutoCloseable {
    // Irisの実GL状態を取得する。Blaze3Dのキャッシュだけではパックが直接変更した状態を復元できない。
    private final int program=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
    private final int read=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),draw=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int active=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE),vao=GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),array=GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
    private final int uniform=GL11C.glGetInteger(GL31C.GL_UNIFORM_BUFFER_BINDING);
    private final int[] viewport=new int[4],scissor=new int[4],polygon=new int[2];
    private final int[] textures=new int[32],samplers=new int[32];
    private final int[] ubos=new int[GL11C.glGetInteger(GL31C.GL_MAX_UNIFORM_BUFFER_BINDINGS)];
    private final long[] offsets=new long[ubos.length],sizes=new long[ubos.length];
    private final boolean depth=GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),depthWrite=GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),cull=GL11C.glIsEnabled(GL11C.GL_CULL_FACE),scissorTest=GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),offset=GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL);
    private final int depthFunc=GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
    private final float offsetFactor=GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_FACTOR),offsetUnits=GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_UNITS);
    private final int[] masks=new int[GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS)];
    private final boolean[] blends=new boolean[masks.length];
    private final int srcRgb=GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB),dstRgb=GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB),srcAlpha=GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA),dstAlpha=GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA),eqRgb=GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB),eqAlpha=GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA);

    /**
     * 現在のIris描画状態と、復元に必要なテクスチャ・UBOの実バインドを保存する。
     */
    IrisDlssGlScope(){
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT,viewport);GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX,scissor);GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE,polygon);
        for(int i=0;i<textures.length;i++){GlStateManager._activeTexture(GL13C.GL_TEXTURE0+i);textures[i]=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);samplers[i]=GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING,i);}GlStateManager._activeTexture(active);
        for(int i=0;i<ubos.length;i++){ubos[i]=GL30C.glGetIntegeri(GL31C.GL_UNIFORM_BUFFER_BINDING,i);offsets[i]=GL32C.glGetInteger64i(GL31C.GL_UNIFORM_BUFFER_START,i);sizes[i]=GL32C.glGetInteger64i(GL31C.GL_UNIFORM_BUFFER_SIZE,i);}
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()){var mask=stack.malloc(4);for(int i=0;i<masks.length;i++){GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK,i,mask);for(int j=0;j<4;j++)if(mask.get(j)!=0)masks[i]|=1<<j;blends[i]=GL30C.glIsEnabledi(GL11C.GL_BLEND,i);}}
    }

    /**
     * 保存したGL状態を復元し、後続のRenderPassが使用するBlaze3D内部キャッシュを無効化する。
     */
    @Override public void close(){
        GlStateManager._glUseProgram(program);GlStateManager._glBindVertexArray(vao);GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER,array);
        for(int i=0;i<textures.length;i++){GlStateManager._activeTexture(GL13C.GL_TEXTURE0+i);GlStateManager._bindTexture(textures[i]);GL33C.glBindSampler(i,samplers[i]);}GlStateManager._activeTexture(active);
        // UBOは名前に加え、範囲指定のオフセットと長さも復元する。
        for(int i=0;i<ubos.length;i++){if(ubos[i]!=0&&sizes[i]>0)GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER,i,ubos[i],offsets[i],sizes[i]);else GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER,i,ubos[i]);}GlStateManager._glBindBuffer(GL31C.GL_UNIFORM_BUFFER,uniform);
        if(depth)GlStateManager._enableDepthTest();else GlStateManager._disableDepthTest();GlStateManager._depthFunc(depthFunc);GlStateManager._depthMask(depthWrite);
        if(cull)GlStateManager._enableCull();else GlStateManager._disableCull();if(scissorTest)GlStateManager._enableScissorTest();else GlStateManager._disableScissorTest();GlStateManager._scissorBox(scissor[0],scissor[1],scissor[2],scissor[3]);
        if(offset)GlStateManager._enablePolygonOffset();else GlStateManager._disablePolygonOffset();GlStateManager._polygonOffset(offsetFactor,offsetUnits);GlStateManager._polygonMode(GL11C.GL_FRONT_AND_BACK,polygon[0]);
        GlStateManager._blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);GlStateManager._blendEquationSeparate(eqRgb,eqAlpha);
        for(int i=0;i<masks.length;i++){GlStateManager._colorMask(i,masks[i]);if(blends[i])GlStateManager._enableBlend(i);else GlStateManager._disableBlend(i);}
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,read);GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,draw);GlStateManager._viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        // 後続のRenderPassが、この区間で使用したパイプラインを再利用しないようキャッシュを無効化する。
        try{Object encoder=get(get(RenderSystem.getDevice(),"backend"),"encoder");for(String name:new String[]{"lastPipeline","lastProgram","lastVertexArray"}){var f=encoder.getClass().getDeclaredField(name);f.setAccessible(true);f.set(encoder,null);}}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot invalidate Iris DLSS encoder state",e);}
        if(GL11C.glGetError()!=GL11C.GL_NO_ERROR)throw new IllegalStateException("Iris DLSS GL state restoration error");
    }
    private static Object get(Object owner,String name)throws ReflectiveOperationException{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
}
