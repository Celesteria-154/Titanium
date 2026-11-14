package celeste.titanium.client.render;

import celeste.titanium.client.TitaniumClient;
import celeste.titanium.client.render.enhanced.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import java.util.*;

/** 任意の最終カラー処理を管理する。パックがリンクしたプログラムを再利用する。 */
public final class EnhancedFinalPostProcess {
    private static final String QUALIFIED_DEFAULT="OFF"; // 製品の既定経路では最終カラー処理を無効とする。
    private static int program,rawLocation=-1,noiseOnlyLocation=-1,unit,vao,indexType,noiseUnit=-1,noiseTexture,noiseSampler;
    private static int widthLocation=-1,heightLocation=-1,framebuffer,colourTexture,colourSampler;
    private static boolean certified,captured,rawThisFrame,replayed,armed;
    private static String reason="FINAL_DOMAIN_NOT_REQUESTED";
    private static TextureTarget reconstructed;
    private static long captures,draws,creations,releases,rejections;
    private EnhancedFinalPostProcess() {}
    public static boolean noiseOnly(){return System.getProperty("titanium.qualityEnhancedFinal",QUALIFIED_DEFAULT).equals("NOISE");}
    public static boolean requested(){String mode=System.getProperty("titanium.qualityEnhancedFinal",QUALIFIED_DEFAULT);
        if("OFF".equals(mode))return false; // 既定経路ではローダー参照・GL操作・リソース確保を行わない。
        return EnhancedFinalRequirements.researchRequested(mode,
            net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("titanium-test"))&&EnhancedShaderRuntime.requested()
            && TitaniumClient.config().dlssQuality()==celeste.titanium.client.config.DlssQuality.QUALITY
            && DlssPreset.configured()==DlssPreset.SDK_DEFAULT;}
    public static boolean rawEnabled(int id){return requested()&&certified&&captured&&armed&&id==program&&EnhancedShaderRuntime.active()&&TitaniumClient.pipeline().enhancedFrameActive();}
    public static boolean manages(int id){return certified&&program==id;}
    public static int ownedResources(){return reconstructed==null?0:2;}
    public static int extraDraws(){return reconstructed==null?0:1;}
    /**
     * 前フレームの完了状態をクリアし、取得した描画形状を使用できる次の履歴区間を開始する。
     */
    public static void beginFrame(){rawThisFrame=false;replayed=false;if(certified&&captured&&!armed&&EnhancedShaderRuntime.active()){TitaniumClient.pipeline().breakFrameHistory();armed=true;}}
    /**
     * 2フレームの最終passについて入力・形式・出力・副作用の範囲を確認し、再実行できる構成か検証する。
     *
     * @param rule 読み込み済みソースが従う既知のAAインターフェース規則
     * @param frames 同一世代で取得した観測フレームの一覧
     * @param candidates 変換済みとして認識されているGLプログラム識別子の集合
     */
    public static void certify(EnhancedShaderAdapter.Rule rule,List<EnhancedPipelineGraph.Frame> frames,Set<Integer> candidates){
        if(!requested())return;
        try{
            var caps=GL.getCapabilities();
            require(EnhancedFinalRequirements.available(new EnhancedFinalRequirements.Functions(caps.glTextureParameteri,caps.glTextureParameterf,
                    caps.glTextureParameteriv,caps.glTextureParameterfv,caps.glGetTextureParameterfv,caps.glProgramUniform1f)),"Optional final GL functions unavailable");
            // 任意の最終カラー経路では、2フレームとも入力が単一の色とノイズだけに限定されているか確認する。
            int selected=0;String input=rule==EnhancedShaderAdapter.Rule.SOLAS?"colortex1":"colortex3";
            for(var f:frames){var list=f.draws().stream().filter(d->d.pass().equals("FINAL")).toList();require(list.size()==1,"Ambiguous final draw");var d=list.getFirst();
                require(candidates.contains(d.program())&&d.stateUnchanged()&&d.writes().size()==1,"Final source not adapted");
                require(d.coverageGaps().isEmpty(),"Final resource side effects or unknown bindings");
                require(d.reads().stream().allMatch(r->r.sampler().equals(input)||r.sampler().equals("noisetex")),"Final has additional scene inputs");
                var colour=d.reads().stream().filter(r->r.sampler().equals(input)).toList();require(colour.size()==1,"Final primary input missing");
                require(d.reads().stream().anyMatch(r->r.sampler().equals("noisetex")),"Display-noise final domain not present; retain basic Enhanced");
                var tex=colour.getFirst().texture();require(tex.internalFormat()==GL11C.GL_RGB8||tex.internalFormat()==GL11C.GL_RGBA8,"Final input is not bounded UNORM LDR");
                require(tex.width()==d.viewport().get(2)&&tex.height()==d.viewport().get(3),"Final colour domain mismatch");
                require(selected==0||selected==d.program(),"Final program changed during certification");selected=d.program();
            }
            require(GL20C.glGetProgrami(selected,GL31C.GL_ACTIVE_UNIFORM_BLOCKS)==0,"Final UBO ownership not supported");
            program=selected;rawLocation=GL20C.glGetUniformLocation(program,EnhancedShaderAdapter.FINAL_RAW);require(rawLocation>=0,"Final raw uniform missing");
            noiseOnlyLocation=GL20C.glGetUniformLocation(program,EnhancedShaderAdapter.FINAL_NOISE);require(!noiseOnly()||noiseOnlyLocation>=0,"Noise-only interface missing");
            int location=GL20C.glGetUniformLocation(program,input);require(location>=0,"Final input uniform missing");unit=GL20C.glGetUniformi(program,location);require(unit>=0&&unit<32,"Final sampler unit bound");
            int noise=GL20C.glGetUniformLocation(program,"noisetex");noiseUnit=noise<0?-1:GL20C.glGetUniformi(program,noise);
            require(noiseUnit<32&&noiseUnit!=unit,"Final noise unit conflict");
            widthLocation=GL20C.glGetUniformLocation(program,"viewWidth");heightLocation=GL20C.glGetUniformLocation(program,"viewHeight");
            require(widthLocation>=0&&heightLocation>=0,"Final pixel-domain uniforms unavailable");
            certified=true;reason="FINAL_DOMAIN_WAITING_FOR_GEOMETRY";
        }catch(Exception e){reject(e.toString());}
    }
    /**
     * 実行された最終passの形状・サンプラー・色を借用し、出力解像度での再実行に備える。
     */
    public static void afterFinalDraw(){
        if(!requested()||!certified||!EnhancedShaderRuntime.active()||!TitaniumClient.pipeline().enhancedFrameActive())return;
        try{
            require(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)==program,"Final program mismatch");
            boolean raw=GL20C.glGetUniformi(program,rawLocation)!=0;
            int current=GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);require(current>0&&GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING)>0,"Final indexed VAO unavailable");
            if(captured)require(vao==current,"Final indexed geometry changed");
            vao=current;
            indexType=RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type()==IndexType.SHORT?GL11C.GL_UNSIGNED_SHORT:GL11C.GL_UNSIGNED_INT;
            int active=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            try{GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);colourTexture=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);colourSampler=GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING,unit);require(colourTexture>0,"Final colour missing");if(noiseUnit>=0){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+noiseUnit);noiseTexture=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);noiseSampler=GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING,noiseUnit);require(noiseTexture>0,"Final noise missing");}}
            finally{GL13C.glActiveTexture(active);}
            captured=true;rawThisFrame=raw;captures++;reason="FINAL_DOMAIN_READY";
        }catch(Exception e){
            // The original final is side-effect free in this domain. Restore its visible colour before fallback.
            if(rawLocation>=0&&GL20C.glGetUniformi(program,rawLocation)!=0){GL41C.glProgramUniform1i(program,rawLocation,0);GL11C.glDrawElements(GL11C.GL_TRIANGLES,6,indexType,0L);}
            reject(e.toString());
        }
    }
    /**
     * 任意の最終処理が有効なフレームでは中間描画先を準備し、通常時は元の描画先を返す。
     *
     * @param original 通常の出力先または差し替え前の描画先
     * @return 対応する中間出力先。最終処理を移さないフレームでは元のターゲット
     */
    static RenderTarget destination(RenderTarget original){
        if(!requested()||!certified||!captured||!rawThisFrame||replayed||!EnhancedShaderRuntime.active())return original;
        if(reconstructed==null||reconstructed.width!=original.width||reconstructed.height!=original.height){releaseTarget();reconstructed=new TextureTarget("Titanium Enhanced pre-final output",original.width,original.height,false,GpuFormat.RGBA8_UNORM);framebuffer=GlStateManager.glGenFramebuffers();creations++;
            int texture=((GlTexture)reconstructed.getColorTexture()).glId();
            for(int key:new int[]{GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_TEXTURE_WRAP_S,GL11C.GL_TEXTURE_WRAP_T,GL12C.GL_TEXTURE_WRAP_R,GL14C.GL_TEXTURE_COMPARE_MODE,GL14C.GL_TEXTURE_COMPARE_FUNC})GL45C.glTextureParameteri(texture,key,GL45C.glGetTextureParameteri(colourTexture,key));
            for(int key:new int[]{GL12C.GL_TEXTURE_MIN_LOD,GL12C.GL_TEXTURE_MAX_LOD,GL14C.GL_TEXTURE_LOD_BIAS})GL45C.glTextureParameterf(texture,key,GL45C.glGetTextureParameterf(colourTexture,key));
            GL45C.glTextureParameteri(texture,GL12C.GL_TEXTURE_BASE_LEVEL,0);GL45C.glTextureParameteri(texture,GL12C.GL_TEXTURE_MAX_LEVEL,0);
            int[] swizzle=new int[4];GL45C.glGetTextureParameteriv(colourTexture,GL33C.GL_TEXTURE_SWIZZLE_RGBA,swizzle);GL45C.glTextureParameteriv(texture,GL33C.GL_TEXTURE_SWIZZLE_RGBA,swizzle);
            float[] border=new float[4];GL45C.glGetTextureParameterfv(colourTexture,GL11C.GL_TEXTURE_BORDER_COLOR,border);GL45C.glTextureParameterfv(texture,GL11C.GL_TEXTURE_BORDER_COLOR,border);
            if(GL.getCapabilities().GL_EXT_texture_filter_anisotropic||GL.getCapabilities().GL_ARB_texture_filter_anisotropic)GL45C.glTextureParameterf(texture,0x84FE,GL45C.glGetTextureParameterf(colourTexture,0x84FE));
        }
        return reconstructed;
    }
    /**
     * 再構成済み中間出力へ借用したパックの最終passを適用し、出力解像度で結果を描画する。
     *
     * @param source 再構成結果を格納した専用中間ターゲット
     * @param destination 出力先のターゲットまたはリソース
     */
    static void finish(RenderTarget source,RenderTarget destination){
        // 通常経路は同じ描画先をそのまま返す。専用中間出力がある場合だけ、借用した最終passを再実行する。
        if(source==destination)return;
        require(source==reconstructed&&rawThisFrame&&!replayed,"Final domain completion mismatch");
        try(var state=new FinalState(unit,noiseUnit)){
            require(GL20C.glIsProgram(program)&&GL30C.glIsVertexArray(vao),"Final borrowed program or geometry expired");
            int sourceId=((GlTexture)source.getColorTexture()).glId(),destId=((GlTexture)destination.getColorTexture()).glId();require(sourceId!=destId,"Final input/output feedback");
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,framebuffer);GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,destId,0);GL11C.glDrawBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            require(GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER)==GL30C.GL_FRAMEBUFFER_COMPLETE,"Final native framebuffer incomplete");
            GL20C.glUseProgram(program);GL30C.glBindVertexArray(vao);GL11C.glViewport(0,0,destination.width,destination.height);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);GL11C.glDisable(GL11C.GL_CULL_FACE);GL11C.glDisable(GL11C.GL_SCISSOR_TEST);GL30C.glDisablei(GL11C.GL_BLEND,0);GL30C.glColorMaski(0,true,true,true,true);GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK,GL11C.GL_FILL);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,sourceId);GL33C.glBindSampler(unit,colourSampler);
            if(noiseUnit>=0){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+noiseUnit);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,noiseTexture);GL33C.glBindSampler(noiseUnit,noiseSampler);}
            float oldWidth=widthLocation<0?0:GL20C.glGetUniformf(program,widthLocation),oldHeight=heightLocation<0?0:GL20C.glGetUniformf(program,heightLocation);
            try{
                GL41C.glProgramUniform1i(program,rawLocation,0);
                if(noiseOnlyLocation>=0)GL41C.glProgramUniform1i(program,noiseOnlyLocation,1);
                if(widthLocation>=0)GL41C.glProgramUniform1f(program,widthLocation,destination.width);if(heightLocation>=0)GL41C.glProgramUniform1f(program,heightLocation,destination.height);
                GL11C.glDrawElements(GL11C.GL_TRIANGLES,6,indexType,0L);
            }finally{if(widthLocation>=0)GL41C.glProgramUniform1f(program,widthLocation,oldWidth);if(heightLocation>=0)GL41C.glProgramUniform1f(program,heightLocation,oldHeight);if(noiseOnlyLocation>=0)GL41C.glProgramUniform1i(program,noiseOnlyLocation,0);GL41C.glProgramUniform1i(program,rawLocation,1);}
            require(GL11C.glGetError()==GL11C.GL_NO_ERROR,"Final native replay GL error");draws++;replayed=true;
        }
    }
    /**
     * 最終passの認証、借用プログラム・形状と所有ターゲットを破棄する。
     */
    public static void reset(){if(program>0&&rawLocation>=0&&GL20C.glIsProgram(program)){GL41C.glProgramUniform1i(program,rawLocation,0);if(noiseOnlyLocation>=0)GL41C.glProgramUniform1i(program,noiseOnlyLocation,0);}releaseTarget();program=0;rawLocation=-1;noiseOnlyLocation=-1;certified=false;captured=false;rawThisFrame=false;replayed=false;armed=false;reason="FINAL_DOMAIN_NOT_REQUESTED";}
    /**
     * 所有する中間カラーターゲットと出力用FBOを解放する。
     */
    private static void releaseTarget(){if(reconstructed!=null){int id=((GlTexture)reconstructed.getColorTexture()).glId();reconstructed.destroyBuffers();reconstructed=null;releases++;require(!GL11C.glIsTexture(id),"Enhanced final target survived release");TitaniumClient.LOGGER.info("[Titanium/EnhancedFinal] owned target released: creations={}, releases={}, borrowed textures untouched",creations,releases);}if(framebuffer!=0){GlStateManager._glDeleteFramebuffers(framebuffer);framebuffer=0;}}
    /**
     * 最終処理の退避理由を記録し、所有資源と認証状態を破棄する。
     *
     * @param why Enhancedの認証を中断する理由
     */
    private static void reject(String why){certified=false;captured=false;rawThisFrame=false;reason="BASIC_ENHANCED: "+why;rejections++;if(program>0&&rawLocation>=0&&GL20C.glIsProgram(program))GL41C.glProgramUniform1i(program,rawLocation,0);TitaniumClient.LOGGER.warn("[Titanium/EnhancedFinal] {}",reason);}
    private static void require(boolean b,String s){if(!b)throw new IllegalStateException(s);}
    /**
     * 任意の最終処理が持つ入力境界、取得実績、追加描画数と退避状態を診断へまとめる。
     *
     * @return 任意の最終処理の有効範囲、借用状態、追加描画と退避理由
     */
    public static Map<String,Object> report(){var r=new LinkedHashMap<String,Object>();r.put("requested",requested());r.put("certified",certified);r.put("reason",reason);r.put("researchOnly",true);r.put("mode",System.getProperty("titanium.qualityEnhancedFinal",QUALIFIED_DEFAULT));r.put("captures",captures);r.put("nativeFinalDraws",draws);r.put("targetCreations",creations);r.put("targetReleases",releases);r.put("rejections",rejections);r.put("extraFullFrameCopies",0);r.put("ownedResources",ownedResources());r.put("additionalGpuPasses",extraDraws());r.put("inputColour",!requested()?"Unchanged basic path":noiseOnly()?"Original final colour before last display noise":"Iris final passthrough encoded LDR + hand; motion remains camera-only");r.put("displayProcessing",!requested()?"No deferred final processing":noiseOnly()?"Original last noise expression at output resolution":"Original pack final at output resolution; no coefficient or setting changes");return r;}

    /** Raw GL only: restoration leaves Blaze3D's cached state unchanged. No inner Mojang RenderPass. */
    private static final class FinalState implements AutoCloseable{
        final int program=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),vao=GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),fbo=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),active=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int[] viewport=new int[4],polygon=new int[2],units,textures,samplers;
        final boolean depth=GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),cull=GL11C.glIsEnabled(GL11C.GL_CULL_FACE),scissor=GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),blend=GL30C.glIsEnabledi(GL11C.GL_BLEND,0);
        final byte[] mask=new byte[4];
        /**
         * 再実行する最終passの入力ユニットと、復元に必要な実GL状態を保存する。
         *
         * @param input 最終passがカラーを参照するテクスチャユニット
         * @param noise ノイズ参照のテクスチャユニット。未使用の場合は負値
         */
        FinalState(int input,int noise){units=noise<0?new int[]{input}:new int[]{input,noise};textures=new int[units.length];samplers=new int[units.length];GL11C.glGetIntegerv(GL11C.GL_VIEWPORT,viewport);GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE,polygon);try(var stack=org.lwjgl.system.MemoryStack.stackPush()){var b=stack.malloc(4);GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK,0,b);b.get(mask);}for(int i=0;i<units.length;i++){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+units[i]);textures[i]=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);samplers[i]=GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING,units[i]);}GL13C.glActiveTexture(active);}
        /**
         * 最終passの再実行で変更したGLプログラム・入力バインド・描画状態を保存値へ戻す。
         */
        public void close(){GL20C.glUseProgram(program);GL30C.glBindVertexArray(vao);GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,fbo);GL11C.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK,polygon[0]);enable(GL11C.GL_DEPTH_TEST,depth);enable(GL11C.GL_CULL_FACE,cull);enable(GL11C.GL_SCISSOR_TEST,scissor);if(blend)GL30C.glEnablei(GL11C.GL_BLEND,0);else GL30C.glDisablei(GL11C.GL_BLEND,0);GL30C.glColorMaski(0,mask[0]!=0,mask[1]!=0,mask[2]!=0,mask[3]!=0);for(int i=0;i<units.length;i++){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+units[i]);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,textures[i]);GL33C.glBindSampler(units[i],samplers[i]);}GL13C.glActiveTexture(active);}
        static void enable(int cap,boolean enabled){if(enabled)GL11C.glEnable(cap);else GL11C.glDisable(cap);}
    }
}
