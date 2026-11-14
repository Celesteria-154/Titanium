package celeste.titanium.client.render.enhanced;

import celeste.titanium.client.TitaniumClient;
import celeste.titanium.client.config.*;
import celeste.titanium.client.render.EnhancedFinalPostProcess;
import org.lwjgl.opengl.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** シェーダーの変換・コンパイル・実描画を追跡し、実行時のAA担当を切り替える。 */
public final class EnhancedShaderRuntime {
    private record Shader(String original, boolean jitter, boolean colour, String hash, boolean pureMainTaa) {}
    private record Program(int jitter, int clip, int colour, int post, boolean pureMainTaa) {}
    private static boolean cutoutTaaCertified;
    // シェーダーには復元用の元ソース、リンク後のプログラムには追加uniformの位置を保持する。
    private static final Map<Integer,Shader> shaders=new HashMap<>();
    private static final Map<Integer,Program> programs=new HashMap<>();
    private static final Map<String,Integer> sourceHashes=new TreeMap<>();
    private static Object pack;
    private static EnhancedShaderAdapter.Rule rule;
    private static String reason="NO_RUNTIME_ADAPTER", packHash="";
    private static boolean validated, pendingActivation, pendingHistoryReset, rejected;
    private static boolean requiredFunctionsReady;
    private static long transformed, compileRestores, jitterUploads, colourUploads, activatedFrames, useUniformWrites, functionChecks;
    private EnhancedShaderRuntime() {}
    public static boolean requested() { var c=TitaniumClient.config();return c.dlssIntegrationMode()==DlssIntegrationMode.ENHANCED && c.enabled() && c.backend()==UpscaleBackend.DLSS; }
    public static boolean active() { return requested() && validated && !rejected; }
    public static String reason() { return active()?(rule==EnhancedShaderAdapter.Rule.SOLAS&&TitaniumClient.config().dlssQuality()==DlssQuality.DLAA?(TitaniumClient.pipeline().enhancedCutoutTaaFrame()?"RUNTIME_CUTOUT_PACK_TAA_AND_DLAA":"RUNTIME_AA_OWNERSHIP_PRE_AA_DLAA"):"RUNTIME_AA_OWNERSHIP_LATE_SR"):reason; }
    public static boolean hasPrograms() { return !programs.isEmpty(); }
    /**
     * Solas系の認証済みメインTAA境界でCUTOUT合成を使用可能か判定する。
     *
     * @return Solas系の認証済みメインTAA境界でCUTOUT合成を使用可能な場合はtrue
     */
    public static boolean cutoutTaaAvailable(){return active()&&rule==EnhancedShaderAdapter.Rule.SOLAS&&cutoutTaaCertified;}
    /**
     * 指定GLプログラムが有効なTAA前DLAA境界に対応するか判定する。
     *
     * @param id 取得または追跡するGLオブジェクト識別子
     * @return 指定GLプログラムが有効なTAA前DLAA境界に対応する場合はtrue
     */
    public static boolean preAaDlaaProgram(int id) {
        Program p=programs.get(id);
        return active()&&rule==EnhancedShaderAdapter.Rule.SOLAS&&p!=null&&p.colour>=0;
    }

    /**
     * 予約されたAA担当変更と履歴リセットを投影取得前に適用し、今フレームのCUTOUT深度取得を開始する。
     */
    public static void beginFrame() {
        if(EnhancedFinalPostProcess.requested())EnhancedFinalPostProcess.beginFrame();
        // 有効化・退避に伴う履歴変更はフレーム開始へ延期し、投影取得途中でAA担当を変えない。
        if(pendingHistoryReset){pendingHistoryReset=false;TitaniumClient.pipeline().breakFrameHistory();}
        if(pendingActivation && requested() && !rejected){pendingActivation=false;TitaniumClient.pipeline().breakFrameHistory();validated=true;}
        EnhancedCutoutTaa.beginFrame();
    }
    /**
     * 旧描画構造の認証を破棄し、追加uniformを通常動作の値へ戻す。
     */
    public static void invalidateTopology(){cutoutTaaCertified=false;EnhancedFinalPostProcess.reset();validated=false;pendingActivation=false;if(!programs.isEmpty())neutralize();}

    /**
     * 読み込み中パックの世代と利用条件を確認し、ZIPの実インターフェースから変換規則を選ぶ。
     */
    private static void select() throws Exception {
        Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
        Object current=((Optional<?>)iris.getMethod("getCurrentPack").invoke(null)).orElse(null);
        // 同じパックオブジェクトでは再解析せず、Irisの再読み込みによる世代交代時に解析し直す。
        if(current==pack)return;
        reset();pack=current;if(current==null)return;
        if(!"1.11.2+mc26.2".equals(iris.getMethod("getVersion").invoke(null))) {reason="UNSUPPORTED_IRIS_RUNTIME";return;}
        // 名目上のGLバージョンだけに頼らず、変換後の描画で使用する関数のアドレスを確認する。
        var caps=GL.getCapabilities();
        functionChecks++;
        var missing=EnhancedGlRequirements.missing(new EnhancedGlRequirements.Functions(caps.glProgramUniform1i,
                caps.glProgramUniform2f,caps.glGetTextureParameteriv,caps.glGetTextureLevelParameteriv,caps.glGetProgramInterfaceiv));
        if(missing.isPresent()){reason="REQUIRED_GL_FUNCTION_UNAVAILABLE: "+missing.get();return;}
        requiredFunctionsReady=true;
        // パックの読み取り範囲をIrisの管理ディレクトリ内に限定し、ZIPの実インターフェースを調べる。
        Path directory=((Path)iris.getMethod("getShaderpacksDirectory").invoke(null)).toAbsolutePath().normalize();
        String name=(String)iris.getMethod("getCurrentPackName").invoke(null);
        Path file=directory.resolve(name).normalize();
        if(!file.startsWith(directory)||!Files.isRegularFile(file)||Files.size(file)>64*1024*1024){reason="PACK_ARCHIVE_UNAVAILABLE_OR_EXCESSIVE";return;}
        try(var input=Files.newInputStream(file)) {var digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)digest.update(b,0,n);packHash=HexFormat.of().withUpperCase().formatHex(digest.digest());}
        // ZIPの識別情報は出典記録に使用する。アダプターは実際のインターフェースから選択する。
        rule=EnhancedPackCompatibility.inspect(file).orElse(null);
        reason=rule==null?"AA_INTERFACE_UNSUPPORTED":"WAITING_FOR_COMPILED_AA_AND_DRAW_GRAPH";
        TitaniumClient.LOGGER.info("[Titanium/Enhanced] runtime rule={} archive={} reason={}",rule,packHash,reason);
    }
    /**
     * シェーダーソースへ限定変換を適用し、失敗時の復元用に元ソースと解析結果を保持する。
     *
     * @param shader コンパイル・追跡対象のGLシェーダー識別子
     * @param original 変換前のシェーダーソース
     * @return 変換後ソース。対象外または解析失敗時は元ソース
     */
    public static String source(int shader,String original) {
        shaders.remove(shader); // 変更のないシェーダーも含め、GL識別子の再利用に備えて旧記録を除去する。
        if(!requested() || rejected)return original;
        try {
            select();if(rule==null)return original;
            int type=GL20C.glGetShaderi(shader,GL20C.GL_SHADER_TYPE);
            if(type!=GL20C.GL_VERTEX_SHADER&&type!=GL20C.GL_FRAGMENT_SHADER)return original;
            // ジッター・表示色の担当分離を適用し、対象の断片シェーダーにはCUTOUT深度出力も追加する。
            var result=EnhancedShaderAdapter.adapt(original,type==GL20C.GL_VERTEX_SHADER,rule);
            var coverage=rule==EnhancedShaderAdapter.Rule.SOLAS&&type==GL20C.GL_FRAGMENT_SHADER
                    ?EnhancedShaderAdapter.adaptCutoutCoverage(result.source()):Optional.<String>empty();
            var post=type==GL20C.GL_FRAGMENT_SHADER&&EnhancedFinalPostProcess.requested()?(EnhancedFinalPostProcess.noiseOnly()?EnhancedShaderAdapter.adaptNoiseFinal(original,rule):EnhancedShaderAdapter.adaptFinal(original,rule)):Optional.<String>empty();
            if(!result.jitter()&&!result.primaryColour()&&post.isEmpty()&&coverage.isEmpty())return original;
            if(shaders.size()>=256 && !shaders.containsKey(shader))throw new IllegalStateException("Runtime shader bound");
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            // コンパイル失敗時に元の処理へ復帰できるよう、変換前ソースと対応する解析結果を記録する。
            shaders.put(shader,new Shader(original,result.jitter(),result.primaryColour(),sha,result.primaryColour()&&EnhancedShaderAdapter.pureMainTaaBoundary(original)));sourceHashes.merge(sha,1,Integer::sum);transformed++;
            return post.orElse(coverage.orElse(result.source()));
        }catch(Exception e){reject("SOURCE_ADAPTER_REJECTED: "+e);return original;}
    }

    /**
     * 変換版のコンパイル結果を確認し、失敗時は元ソースへ戻して再コンパイルする。
     *
     * @param shader コンパイル・追跡対象のGLシェーダー識別子
     */
    public static void compiled(int shader) {
        Shader s=shaders.get(shader);if(s==null)return;
        if(GL20C.glGetShaderi(shader,GL20C.GL_COMPILE_STATUS)==GL11C.GL_TRUE)return;
        reject("TRANSFORM_COMPILE_FAILED: "+GL20C.glGetShaderInfoLog(shader));
        GL20C.glShaderSource(shader,s.original());GL20C.glCompileShader(shader);shaders.remove(shader);compileRestores++;
    }
    /**
     * リンク結果を確認し、追加uniformの位置と対応するプログラムを登録する。失敗時は元ソースへ復元する。
     *
     * @param id 取得または追跡するGLオブジェクト識別子
     */
    public static void linked(int id) {
        if(shaders.isEmpty())return;
        int[] count={0},attached=new int[6];GL20C.glGetAttachedShaders(id,count,attached);
        boolean selected=false;for(int i=0;i<Math.min(count[0],attached.length);i++)selected|=shaders.containsKey(attached[i]);
        if(!selected)return;
        // リンク失敗時は関連シェーダーを元ソースで再コンパイルし、追加uniformを使う経路を停止する。
        if(GL20C.glGetProgrami(id,GL20C.GL_LINK_STATUS)!=GL11C.GL_TRUE){
            reject("TRANSFORM_LINK_FAILED");
            for(int i=0;i<Math.min(count[0],attached.length);i++){var s=shaders.remove(attached[i]);if(s!=null){GL20C.glShaderSource(attached[i],s.original());GL20C.glCompileShader(attached[i]);compileRestores++;}}
            GL20C.glLinkProgram(id);return;
        }
        if(programs.size()>=256&&!programs.containsKey(id)){reject("Runtime program bound");return;}
        boolean pureMainTaa=false;for(int i=0;i<Math.min(count[0],attached.length);i++){var s=shaders.get(attached[i]);pureMainTaa|=s!=null&&s.pureMainTaa;}
        programs.put(id,new Program(GL20C.glGetUniformLocation(id,EnhancedShaderAdapter.OWN),GL20C.glGetUniformLocation(id,EnhancedShaderAdapter.CLIP),GL20C.glGetUniformLocation(id,EnhancedShaderAdapter.COLOUR),GL20C.glGetUniformLocation(id,EnhancedShaderAdapter.FINAL_RAW),pureMainTaa));
        EnhancedCutoutTaa.linked(id);
    }
    /**
     * 現在の実フレームに合わせて、ジッター・表示色・最終処理の担当uniformを更新する。
     *
     * @param program 対象のGLプログラム識別子
     */
    public static void used(int program) {
        if(!requested()||programs.isEmpty()||rejected)return;
        Program p=programs.get(program);if(p==null)return;
        // プログラム使用時の実フレーム状態からuniformを更新し、影描画や無効期間では元処理へ戻す。
        boolean enabled=active()&&TitaniumClient.pipeline().enhancedFrameActive();
        if(p.jitter>=0) {GL41C.glProgramUniform1i(program,p.jitter,enabled?1:0);useUniformWrites++;if(enabled)jitterUploads++;}
        if(p.clip>=0){var j=TitaniumClient.pipeline().enhancedJitter();int w=TitaniumClient.pipeline().enhancedWorldWidth(),h=TitaniumClient.pipeline().enhancedWorldHeight();GL41C.glProgramUniform2f(program,p.clip,enabled&&w>0?(float)(-2*j.pixelX()/w):0,enabled&&h>0?(float)(2*j.pixelY()/h):0);useUniformWrites++;}
        // CUTOUT合成を行うフレームでは元のTAA表示色を残し、直後の合成で必要な領域を取り出す。
        if(p.colour>=0){GL41C.glProgramUniform1i(program,p.colour,enabled&&!TitaniumClient.pipeline().enhancedCutoutTaaFrame()?1:0);useUniformWrites++;if(enabled)colourUploads++;}
        if(p.post>=0&&EnhancedFinalPostProcess.manages(program)){GL41C.glProgramUniform1i(program,p.post,enabled&&EnhancedFinalPostProcess.rawEnabled(program)?1:0);useUniformWrites++;}
    }
    /**
     * 2フレームの実drawから、TAAの生成元・寸法・出力構成を照合し、次フレームの有効化を予約する。
     *
     * 有効化は次フレームの投影取得前へ予約し、フレーム途中でジッターやTAAの担当を変更しない。
     *
     * @param frames 同一世代で取得した観測フレームの一覧
     */
    public static void validate(List<EnhancedPipelineGraph.Frame> frames) {
        if(rejected||rule==null||frames.size()!=2)return;
        boolean cutoutCompatible=true;
        String consumer=rule==EnhancedShaderAdapter.Rule.SOLAS?"composite15":rule==EnhancedShaderAdapter.Rule.BSL?"composite7":"composite6";
        String producer=rule==EnhancedShaderAdapter.Rule.SOLAS?"composite14":rule==EnhancedShaderAdapter.Rule.BSL?"composite6":"composite5";
        String sampler=rule==EnhancedShaderAdapter.Rule.COMPLEMENTARY?"colortex3":"colortex1";
        // 2フレームともメインTAAの実入力・出力と先行生成パスが一致することを確認する。
        for(var f:frames){int found=0;for(var d:f.draws()) {
            var p=programs.get(d.program());if(p==null||p.colour<0)continue;
            cutoutCompatible&=p.pureMainTaa;
            if(!d.pass().equals(consumer)||d.writes().size()!=2||!d.stateUnchanged()) {reject("AA_OUTPUT_GRAPH_MISMATCH");return;}
            var edge=d.edges().stream().filter(e->e.sampler().equals(sampler)&&e.priorObservedDraw()>=0).findFirst();
            if(edge.isEmpty()||!f.draws().get(edge.get().priorObservedDraw()).pass().equals(producer)){reject("AA_SOURCE_PRODUCER_NOT_PROVEN");return;}
            var input=d.reads().stream().filter(r->r.sampler().equals(sampler)).findFirst().orElseThrow().texture();
            if(d.viewport().get(0)!=0||d.viewport().get(1)!=0||d.viewport().get(2)!=input.width()||d.viewport().get(3)!=input.height()||d.writes().stream().anyMatch(w->w.width()!=input.width()||w.height()!=input.height())){reject("AA_RESOLUTION_DOMAIN_MISMATCH");return;}
            found++;
        }if(found!=1){reason="AA_NOT_ENABLED_OR_NOT_COMPILED";return;}}
        if(programs.values().stream().noneMatch(p->p.jitter>=0)){reason="SHARED_JITTER_NOT_COMPILED";return;}
        cutoutTaaCertified=cutoutCompatible;
        // 検証完了を予約し、次フレームの投影取得前にAA担当と履歴を同時に切り替える。
        pendingActivation=true;
        EnhancedFinalPostProcess.certify(rule,frames,programs.entrySet().stream().filter(e->e.getValue().post>=0).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        TitaniumClient.LOGGER.info("[Titanium/Enhanced] adapter={} certified; activation queued for next world frame; quality not yet certified",rule);
    }
    public static void frameCompleted(){if(active())activatedFrames++;}
    public static void shaderDeleted(int id){shaders.remove(id);}
    public static void programDeleted(int id){programs.remove(id);EnhancedCutoutTaa.deleted(id);}
    /**
     * 退避理由を記録し、CUTOUT資源・認証・追加uniformを通常描画へ戻す。
     *
     * @param why Enhancedの認証を中断する理由
     */
    public static void reject(String why) {
        cutoutTaaCertified=false;
        EnhancedCutoutTaa.reset();
        // すでに有効だった経路の退避では履歴を破棄し、変換用uniformを中立値へ戻す。
        pendingHistoryReset |= validated;
        rejected=true;validated=false;pendingActivation=false;reason=why;
        EnhancedFinalPostProcess.reset();neutralize();TitaniumClient.LOGGER.warn("[Titanium/Enhanced] AA ownership inactive; independent input footprint may remain; {}",reason);
    }
    /**
     * 追跡中の有効GLプログラムの担当切り替えuniformを0へ戻す。
     */
    private static void neutralize(){for(var e:programs.entrySet()){int id=e.getKey();if(!GL20C.glIsProgram(id))continue;var p=e.getValue();if(p.jitter>=0)GL41C.glProgramUniform1i(id,p.jitter,0);if(p.colour>=0)GL41C.glProgramUniform1i(id,p.colour,0);if(p.post>=0)GL41C.glProgramUniform1i(id,p.post,0);}}
    /**
     * 変換・認証・選択パックと所有GPU資源をすべて破棄し、次のパック解析へ備える。
     */
    public static void reset(){cutoutTaaCertified=false;EnhancedCutoutTaa.reset();EnhancedFinalPostProcess.reset();if(!programs.isEmpty())neutralize();programs.clear();shaders.clear();sourceHashes.clear();pack=null;rule=null;validated=false;pendingActivation=false;pendingHistoryReset=false;rejected=false;requiredFunctionsReady=false;reason="NO_RUNTIME_ADAPTER";packHash="";}
    /**
     * 選択規則、変換・復元回数、入力契約と退避状態を診断データへまとめる。
     *
     * @return 変換規則、ソース識別情報、認証と復元実績の診断データ
     */
    public static Map<String,Object> report(){var r=new LinkedHashMap<String,Object>();r.put("active",active());r.put("reason",reason());r.put("rule",String.valueOf(rule));r.put("versionLocked",false);r.put("selectionBasis","AA_INTERFACE_AND_ACTUAL_DRAW_GRAPH");r.put("fragmentJitterOwnership",rule==EnhancedShaderAdapter.Rule.COMPLEMENTARY?"IRIS_JITTERED_INVERSE":rule==EnhancedShaderAdapter.Rule.BSL?"EXPLICIT_CPU_SAMPLE_REMOVAL_DIAGONAL_INVERSE":"NO_CERTIFIED_FRAGMENT_CONSUMER");r.put("packSha256",packHash);r.put("transformedShaders",transformed);r.put("programs",programs.size());r.put("sourceHashes",Map.copyOf(sourceHashes));r.put("compileRestores",compileRestores);r.put("jitterUploads",jitterUploads);r.put("colourUploads",colourUploads);r.put("useUniformWrites",useUniformWrites);r.put("functionChecks",functionChecks);r.put("requiredFunctionsReady",requiredFunctionsReady);r.put("activatedFrames",activatedFrames);r.put("packFilesWritten",false);r.put("additionalGpuPasses",0);r.put("ownedGpuResources",0);r.put("motionCoverage","Camera only; deformation and hand motion not certified");return r;}
}
