package celeste.titanium.client.render.enhanced;

import celeste.titanium.client.TitaniumClient;
import celeste.titanium.client.config.DlssQuality;
import celeste.titanium.client.render.FrameJitter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import java.util.*;
import static org.lwjgl.opengl.GL45C.*;

/** 実際に描画されたCUTOUT面にはパックのTAAを、残りの領域にはDLAAを使用する。
 * 切り抜き形状の細部を保持するため、両者の出力を深度に基づいて合成する。 */
public final class EnhancedCutoutTaa {
    private static final Set<Integer> programs=new HashSet<>();
    private static GpuTexture coverage;
    private static Scope bound;private static boolean cutout;private static long draws,currentDraws,merges;
    private static int mergeProgram,vao,readFbo,copyFbo;
    private static String failure="";
    private EnhancedCutoutTaa(){}
    public static void linked(int id){if(glGetFragDataLocation(id,"titaniumCutoutDepth")==7)programs.add(id);}
    public static void deleted(int id){programs.remove(id);}
    /**
     * このフレームでCUTOUT深度を取得し、認証済みのDLAA合成経路を使用できるか判定する。
     *
     * @return このフレームでCUTOUT深度を取得し、認証済みのDLAA合成経路を使用できる場合はtrue
     */
    public static boolean hasCoverage(){return EnhancedShaderRuntime.cutoutTaaAvailable()&&TitaniumClient.config().dlssQuality()==DlssQuality.DLAA
            &&currentDraws>0&&failure.isEmpty();}
    /**
     * 合成経路の有効条件と寸法を確認し、必要なマスクを用意して今フレーム用にクリアする。
     */
    public static void beginFrame(){
        currentDraws=0;
        if(!EnhancedShaderRuntime.cutoutTaaAvailable()||TitaniumClient.config().dlssQuality()!=DlssQuality.DLAA){releaseTexture();releaseMergeObjects();return;}
        if(programs.isEmpty()||!failure.isEmpty())return;
        var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        // 深度マスクは現在のワールド寸法と一致させ、リサイズ時だけ再確保する。
        if(coverage==null||coverage.getWidth(0)!=target.width||coverage.getHeight(0)!=target.height){
            releaseTexture();var device=RenderSystem.getDevice();
            coverage=device.createTexture(()->"Titanium accepted cutout depth",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.R32_FLOAT,target.width,target.height,1,1);
        }
        // 毎フレーム0へクリアし、前フレームで描かれたCUTOUT面を持ち越さない。
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(coverage,new Vector4f());
    }
    public static void enterCutout(boolean main){cutout=main;}
    public static void leaveCutout(){if(bound!=null){bound.close();bound=null;}cutout=false;}
    /**
     * 実drawのプログラム・FBO・空き出力と寸法を確認し、追加深度出力を接続する。
     */
    public static void beforeDraw(){
        if(!cutout||coverage==null||TitaniumClient.config().dlssQuality()!=DlssQuality.DLAA||!failure.isEmpty()||!EnhancedShaderRuntime.cutoutTaaAvailable()||!TitaniumClient.pipeline().enhancedFrameActive())return;
        int id=glGetInteger(GL_CURRENT_PROGRAM),fbo=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        // 同じプログラム・FBOが続く間は接続を再利用し、地形領域ごとの再設定を抑える。
        if(bound!=null){if(bound.id==id&&bound.fbo==fbo){currentDraws++;draws++;return;}bound.close();bound=null;}
        // 出力位置7と対応アタッチメントの両方が空いている描画だけに追加の深度出力を接続する。
        if(!programs.contains(id)||fbo==0||glGetInteger(GL_DRAW_BUFFER0+7)!=GL_NONE)return;
        if(glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0+7,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL_NONE)return;
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        if(viewport[0]!=0||viewport[1]!=0||viewport[2]!=coverage.getWidth(0)||viewport[3]!=coverage.getHeight(0))return;
        bound=new Scope(id,fbo);
    }
    private static final class Scope implements AutoCloseable {
        final int id,fbo;final int[] buffers=new int[8];final boolean blend;final boolean[] mask=new boolean[4];boolean closed;
        /**
         * 元の描画出力・ブレンド・書き込みマスクを保存し、空き出力7へ採用深度を接続する。
         *
         * @param id 取得または追跡するGLオブジェクト識別子
         * @param fbo 描画中または保存対象のGLフレームバッファ識別子
         */
        Scope(int id,int fbo){
            // 元の出力割り当て・ブレンド・書き込みマスクを保存し、地形描画の終了時に復元する。
            this.id=id;this.fbo=fbo;for(int i=0;i<8;i++)buffers[i]=glGetInteger(GL_DRAW_BUFFER0+i);blend=glIsEnabledi(GL_BLEND,7);
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()){var b=stack.malloc(4);glGetBooleani_v(GL_COLOR_WRITEMASK,7,b);for(int i=0;i<4;i++)mask[i]=b.get(i)!=0;}
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0+7,GL_TEXTURE_2D,((GlTexture)coverage).glId(),0);
            // CUTOUTの採用深度をR32Fへそのまま書くため、追加出力だけブレンドを無効にする。
            int[] mapping=buffers.clone();mapping[7]=GL_COLOR_ATTACHMENT0+7;glDrawBuffers(mapping);glDisablei(GL_BLEND,7);glColorMaski(7,true,true,true,true);
            if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE){close();failure="Cutout MRT incomplete";return;}
            currentDraws++;draws++;
        }
        /**
         * 一時接続した採用深度を外し、保存した出力割り当てとブレンド・書き込みマスクを復元する。
         */
        public void close(){if(closed)return;closed=true;
            glNamedFramebufferTexture(fbo,GL_COLOR_ATTACHMENT0+7,0,0);glNamedFramebufferDrawBuffers(fbo,buffers);
            if(blend)glEnablei(GL_BLEND,7);else glDisablei(GL_BLEND,7);glColorMaski(7,mask[0],mask[1],mask[2],mask[3]);
        }
    }

    /**
     * メインTAAの出力を退避し、可視CUTOUT深度に対応する近傍へTAA色、それ以外へDLAA色を合成する。
     *
     * 深度差1.2e-7以内で現在の可視面に一致する採用深度を使い、細い輪郭を含む近傍3×3へパックTAAを適用する。
     *
     * @param neural 合成するDLAA出力のGLテクスチャ識別子
     * @param scratch メインTAA色を一時退避する既存ターゲットのGL識別子
     * @param depth 現在の可視深度を保持するGLテクスチャ識別子
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param jitter 実投影と対応する入力ピクセル単位のサンプル
     */
    public static void merge(int neural,int scratch,int depth,int width,int height,FrameJitter jitter){
        int original=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),attachment=glGetInteger(GL_DRAW_BUFFER0);
        // メインTAAが実際に出力した色を取得し、読み元と書き込み先の同一テクスチャ参照を防ぐ。
        int source=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,attachment,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if(source<=0||source==neural||source==scratch)throw new IllegalStateException("Cutout TAA return aliases an input");
        ensureMergeProgram();
        if(readFbo==0){readFbo=glGenFramebuffers();copyFbo=glGenFramebuffers();}
        // TAA出力を既存の一時ターゲットへ退避し、合成先を書き換えても元のTAA色を参照できるようにする。
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER,readFbo);
        GlStateManager._glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,source,0);glReadBuffer(GL_COLOR_ATTACHMENT0);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER,copyFbo);
        GlStateManager._glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,scratch,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);
        if(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE||glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Cutout TAA copy framebuffer incomplete");
        GlStateManager._disableScissorTest();
        GlStateManager._glBlitFrameBuffer(0,0,width,height,0,0,width,height,GL_COLOR_BUFFER_BIT,GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER,original);
        int[] mapping=new int[8];for(int i=0;i<8;i++)mapping[i]=glGetInteger(GL_DRAW_BUFFER0+i);
        boolean stencil=glIsEnabled(GL_STENCIL_TEST),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
        try{
            int[] primary=new int[8];primary[0]=attachment;glNamedFramebufferDrawBuffers(original,primary);
            GlStateManager._disableDepthTest();GlStateManager._depthMask(false);GlStateManager._disableCull();GlStateManager._disableBlend(0);GlStateManager._colorMask(0,15);
            glDisable(GL_STENCIL_TEST);glDisable(GL_FRAMEBUFFER_SRGB);GlStateManager._viewport(0,0,width,height);
            GlStateManager._glUseProgram(mergeProgram);GlStateManager._glBindVertexArray(vao);
            int[] inputs={neural,scratch,((GlTexture)coverage).glId(),depth};
            for(int i=0;i<inputs.length;i++){GlStateManager._activeTexture(GL_TEXTURE0+i);GlStateManager._bindTexture(inputs[i]);glBindSampler(i,0);}
            glUniform2i(glGetUniformLocation(mergeProgram,"Size"),width,height);
            // ジッター付きの採用深度を、左下原点の出力ピクセルへ対応づける。
            glUniform2f(glGetUniformLocation(mergeProgram,"SampleOffset"),(float)-jitter.pixelX(),(float)jitter.pixelY());
            glDrawArrays(GL_TRIANGLES,0,3);merges++;
        }finally{glNamedFramebufferDrawBuffers(original,mapping);if(stencil)glEnable(GL_STENCIL_TEST);if(srgb)glEnable(GL_FRAMEBUFFER_SRGB);}
        if(glGetError()!=GL_NO_ERROR)throw new IllegalStateException("Cutout TAA merge GL error");
    }
    /**
     * 合成用シェーダーをコンパイルし、失敗時は作成したシェーダーを解放して詳細を報告する。
     *
     * @param type GLシェーダー種別またはuniformのGL型
     * @param source 変換またはコンパイルするGLSLソース
     * @return コンパイルに成功した合成用シェーダーのGL識別子
     */
    private static int compile(int type,String source){int id=glCreateShader(type);glShaderSource(id,source);glCompileShader(id);
        if(glGetShaderi(id,GL_COMPILE_STATUS)!=GL_TRUE){String error=glGetShaderInfoLog(id);glDeleteShader(id);throw new IllegalStateException(error);}return id;}
    /**
     * 合成が必要になった最初の一度だけ、深度マスク参照用のシェーダーとVAOをリンクする。
     */
    private static void ensureMergeProgram(){if(mergeProgram!=0)return;
        int vertex=compile(GL_VERTEX_SHADER,"#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}");
        int fragment=0,program=0;
        try{
            // 現在の可視深度と採用深度が一致する近傍3×3をTAA領域とする。
            // 深度差の許容値は1.2e-7。細い草の端まで含め、その他の領域にはDLAA色を使用する。
            fragment=compile(GL_FRAGMENT_SHADER,"""
                    #version 330 core
                    uniform sampler2D Neural, PackTaa, Coverage, Depth;
                    uniform ivec2 Size;
                    uniform vec2 SampleOffset;
                    out vec4 Colour;
                    void main(){
                        ivec2 centre=ivec2(floor(gl_FragCoord.xy+SampleOffset));
                        float cutout=0.0;
                        for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
                            ivec2 p=centre+ivec2(x,y);
                            if(any(lessThan(p,ivec2(0)))||any(greaterThanEqual(p,Size)))continue;
                            float accepted=texelFetch(Coverage,p,0).r,visible=texelFetch(Depth,p,0).r;
                            if(accepted>0.0&&abs(accepted-visible)<=0.00000012)
                                cutout=1.0;
                        }
                        ivec2 outputPixel=ivec2(gl_FragCoord.xy);
                        Colour=vec4(mix(texelFetch(Neural,outputPixel,0).rgb,texelFetch(PackTaa,outputPixel,0).rgb,cutout),1.0);
                    }
                    """);
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)!=GL_TRUE)throw new IllegalStateException(glGetProgramInfoLog(program));
            String[] samplers={"Neural","PackTaa","Coverage","Depth"};for(int i=0;i<samplers.length;i++)glProgramUniform1i(program,glGetUniformLocation(program,samplers[i]),i);
            mergeProgram=program;program=0;vao=glGenVertexArrays();
        }finally{glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment);if(program!=0)glDeleteProgram(program);}
    }
    private static void releaseTexture(){if(coverage!=null)coverage.close();coverage=null;}
    /**
     * 開いた描画スコープ、GPU資源、対象プログラムと失敗状態を破棄し、再解析へ戻す。
     */
    public static void reset(){leaveCutout();releaseTexture();releaseMergeObjects();programs.clear();currentDraws=0;failure="";}
    // 合成用プログラム・VAO・FBOはこのクラスが所有し、モード無効化や再読み込み時にまとめて解放する。
    /**
     * 所有する合成プログラム、VAO、読み取り・退避用FBOを解放する。
     */
    private static void releaseMergeObjects(){
        if(mergeProgram!=0)glDeleteProgram(mergeProgram);if(vao!=0)glDeleteVertexArrays(vao);if(readFbo!=0)glDeleteFramebuffers(readFbo);if(copyFbo!=0)glDeleteFramebuffers(copyFbo);
        mergeProgram=vao=readFbo=copyFbo=0;
    }
    public static int ownedResources(){return (coverage==null?0:1)+(mergeProgram==0?0:1)+(vao==0?0:1)+(readFbo==0?0:1)+(copyFbo==0?0:1);}
    public static int extraDraws(){return hasCoverage()&&TitaniumClient.pipeline().enhancedCutoutTaaFrame()?1:0;}
    public static Map<String,Object> report(){return Map.of("programs",programs.size(),"draws",draws,"currentDraws",currentDraws,"merges",merges,
            "coverageTexture",coverage!=null,"failure",failure,"mode","Pack TAA on accepted CUTOUT, DLAA on remaining scene");}
}
