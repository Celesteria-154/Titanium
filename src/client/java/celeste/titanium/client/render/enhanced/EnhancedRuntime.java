package celeste.titanium.client.render.enhanced;

import celeste.titanium.client.TitaniumClient;
import celeste.titanium.client.config.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import java.util.*;

/** 2フレームの描画構造を確認し、対応するEnhancedアダプターの寿命と退避理由を管理する。 */
public final class EnhancedRuntime {
    private static EnhancedAnalysisSession session;
    private static IrisPipelineAccess access;
    private static Object owner;
    private static int width, height;
    private static long generation, sessionsCreated, invalidations, observedDraws, reloadBoundaries;
    private static boolean observing;
    private static String fallback = "IRIS_PIPELINE_NOT_OBSERVED";
    private EnhancedRuntime() {}
    public static boolean observing() { return observing; }
    private static boolean requested() { return TitaniumClient.config().dlssIntegrationMode() == DlssIntegrationMode.ENHANCED; }
    private static boolean footprint(){return requested()&&TitaniumClient.pipeline().enhancedInputFootprintActive();}
    private static String effectiveReason(){return !requested()?"STANDARD_SELECTED":footprint()&&!EnhancedShaderRuntime.active()?"INPUT_FOOTPRINT_ONLY; AA="+EnhancedShaderRuntime.reason():session==null?fallback:EnhancedShaderRuntime.reason();}

    /**
     * Iris再読み込み前に旧パイプラインの変換・観測状態を破棄する。
     */
    public static void beforeReload() {
        TitaniumClient.pipeline().invalidateEnhancedFootprintEvidence();
        if(!requested() && session==null && !EnhancedShaderRuntime.hasPrograms())return;
        reloadBoundaries++;EnhancedShaderRuntime.reset();discard();fallback="IRIS_RELOAD_PENDING";
    }

    /**
     * 設定変更に伴うEnhanced状態を破棄し、必要な場合はIrisの再読み込みを予約する。
     */
    public static void configurationChanged() {
        TitaniumClient.pipeline().invalidateEnhancedFootprintEvidence();
        // 切り替え前の変換済みプログラムを破棄し、必要な場合はIrisを再読み込みして通常ソースから作り直す。
        boolean reload = EnhancedShaderRuntime.requested() || EnhancedShaderRuntime.hasPrograms();
        EnhancedShaderRuntime.reset();
        discard();
        fallback = FabricLoader.getInstance().isModLoaded("iris") ? "IRIS_PIPELINE_NOT_OBSERVED" : "IRIS_NOT_INSTALLED";
        if (requested()) TitaniumClient.LOGGER.info("[Titanium/Enhanced] requested=ENHANCED active=STANDARD reason={}", fallback);
        if(reload && Minecraft.getInstance().level!=null && FabricLoader.getInstance().isModLoaded("iris"))
            Minecraft.getInstance().execute(()->{try{Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);TitaniumClient.pipeline().breakFrameHistory();}catch(Exception e){EnhancedShaderRuntime.reject("RELOAD_FAILED: "+e);}});
    }

    /**
     * パイプラインと寸法の同一性を確認し、新世代の場合は2フレームの読み取り専用解析を開始する。
     *
     * @param pipeline 開始・完了・破棄を観測するIrisパイプライン
     */
    public static void begin(Object pipeline) {
        if (!requested()) { if (session != null) discard(); return; }
        var config = TitaniumClient.config();
        if (!config.enabled() || config.backend() != UpscaleBackend.DLSS) { discard(); fallback = "DLSS_NOT_ENABLED"; return; }
        try {
            var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            // パイプラインの世代や解像度が変わった場合、観測グラフを捨てて2フレームの解析を再開する。
            if (owner != pipeline || width != target.width || height != target.height) {
                EnhancedShaderRuntime.invalidateTopology();
                discard(); owner = pipeline; width = target.width; height = target.height;
                session = new EnhancedAnalysisSession(++generation); sessionsCreated++;
                String irisVersion = FabricLoader.getInstance().getModContainer("iris").orElseThrow().getMetadata().getVersion().getFriendlyString();
                if (!irisVersion.equals("1.11.2+mc26.2"))
                    throw new IllegalStateException("Unsupported Iris analysis interface: " + irisVersion);
                // A lower core context may still expose the required ARB functions. Test the functions, not a nominal version.
                var caps = GL.getCapabilities();
                if (caps.glGetTextureParameteriv == 0 || caps.glGetTextureLevelParameteriv == 0 || caps.glGetProgramInterfaceiv == 0)
                    throw new IllegalStateException("Required read-only GL analysis functions are unavailable");
                access = new IrisPipelineAccess(pipeline);
                TitaniumClient.LOGGER.info("[Titanium/Enhanced] generation={} requested=ENHANCED active=STANDARD reason=READ_ONLY_ANALYSIS", generation);
            }
            if (session.state() == EnhancedAnalysisSession.State.ANALYZING) { session.beginFrame(); observing = true; }
        } catch (Exception e) { fail(e); }
    }
    public static void beginBatch(Object renderer) { if (observing) try { access.beginBatch(renderer, session.graph()); } catch (Exception e) { fail(e); } }
    public static void beforeDraw(Object renderer) { if (observing) try { access.before(renderer, session.graph()); observedDraws++; } catch (Exception e) { fail(e); } }
    public static void afterDraw(Object renderer) { if (observing) try { access.after(renderer, session.graph()); } catch (Exception e) { fail(e); } }
    /**
     * Irisフレーム完了を記録し、必要数の観測が揃った場合に実行時アダプターを検証する。
     *
     * @param pipeline 開始・完了・破棄を観測するIrisパイプライン
     */
    public static void complete(Object pipeline) {
        EnhancedShaderRuntime.frameCompleted();
        if (!observing || owner != pipeline) return;
        try {
            session.finishFrame(); observing = false;
            // 観測セッションが完了してから、実行時アダプターへ入力・出力の接続関係を照合させる。
            if (session.state() == EnhancedAnalysisSession.State.FALLBACK) {
                EnhancedShaderRuntime.validate(session.frames());
                TitaniumClient.LOGGER.info("[Titanium/Enhanced] generation={} graph complete; adapter={} frames={} draws={}", generation, EnhancedShaderRuntime.reason(), session.frames().size(), access.drawQueries());
            }
        } catch (Exception e) { fail(e); }
    }
    /**
     * 破棄されたパイプラインが現在の所有者の場合、観測・変換・適用実績を無効化する。
     *
     * @param pipeline 開始・完了・破棄を観測するIrisパイプライン
     */
    public static void destroyed(Object pipeline) { if (owner == pipeline) { TitaniumClient.pipeline().invalidateEnhancedFootprintEvidence();EnhancedShaderRuntime.reset();discard(); fallback = "IRIS_PIPELINE_DESTROYED"; } }
    /**
     * 観測セッションと借用中のIrisパイプライン参照を解放する。
     */
    private static void discard() {
        observing = false;
        if (session != null) { session.close(); invalidations++; }
        session = null; access = null; owner = null;
    }
    /**
     * 観測失敗を記録し、変換用uniformを無効化して通常描画へ退避する。
     *
     * @param e 捕捉した解析または反射処理の例外
     */
    private static void fail(Exception e) {
        observing = false; fallback = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (session != null) session.fail(fallback);
        EnhancedShaderRuntime.reject(fallback);
        TitaniumClient.LOGGER.warn("[Titanium/Enhanced] active=STANDARD; read-only analysis rejected: {}", fallback);
    }
    /**
     * 要求・有効経路、観測グラフ、所有資源、追加描画数を診断用にまとめる。
     *
     * @return 要求・実効モード、解析結果、所有資源と退避理由を持つ診断データ
     */
    public static Map<String,Object> report() {
        var r = new LinkedHashMap<String,Object>();
        r.put("requested", TitaniumClient.config().dlssIntegrationMode()); r.put("active", EnhancedShaderRuntime.active()||footprint()?DlssIntegrationMode.ENHANCED:DlssIntegrationMode.STANDARD);
        r.put("state", !requested() ? "DISABLED" : EnhancedShaderRuntime.active()?"ACTIVE":footprint()?"ACTIVE_INPUT_ONLY":session == null ? "FALLBACK" : session.state());
        r.put("reason", effectiveReason());
        r.put("generation", generation); r.put("sessionsCreated", sessionsCreated); r.put("invalidations", invalidations);
        r.put("reloadBoundaries",reloadBoundaries);
        r.put("observing", observing); r.put("observedDraws", observedDraws);
        r.put("frames", session == null ? List.of() : session.frames()); r.put("topology", access == null ? List.of() : access.topology());
        r.put("semanticSelection", EnhancedShaderRuntime.active()?EnhancedShaderRuntime.reason():footprint()?"INPUT_SCALE_FOOTPRINT_ONLY_NOT_AA_OWNERSHIP":"NONE"); r.put("completeContentProvenance", false);
        r.put("inputFootprintActive",footprint());
        r.put("additionalGpuPasses", celeste.titanium.client.render.EnhancedFinalPostProcess.extraDraws()+EnhancedCutoutTaa.extraDraws()); r.put("ownedGpuResources", celeste.titanium.client.render.EnhancedFinalPostProcess.ownedResources()+TitaniumClient.pipeline().preAaDlaaResources()+EnhancedCutoutTaa.ownedResources()); r.put("pixelReadbacks", 0);
        // The shader adapter owns no targets/buffers. Footprint samplers use the existing pipeline owner.
        var sampler=TitaniumClient.pipeline().irisResolutionSampler();
        r.put("existingPipelineTerrainSampler",Map.of("live",Math.max(0,sampler.creations()-sampler.releases()),
                "creations",sampler.creations(),"releases",sampler.releases(),"lastAppliedBias",sampler.appliedBias(),
                "managedOutsideShaderAdapter",true));
        r.put("shaderAdapter",EnhancedShaderRuntime.report());r.put("qualityPass", false);
        r.put("cutoutTaa",EnhancedCutoutTaa.report());
        r.put("preAaDlaaFrames",TitaniumClient.pipeline().preAaDlaaFrames());
        r.put("finalPostProcess",celeste.titanium.client.render.EnhancedFinalPostProcess.report());
        return r;
    }
    /**
     * コマンドの状態表示へ付ける連携モードと実効理由を生成する。
     *
     * @return 現在の要求・実効連携と理由の文字列。Standard選択時は空文字
     */
    public static String statusSuffix() {
        if (!requested()) return "";
        return "; integration requested=Enhanced, active="+(EnhancedShaderRuntime.active()||footprint()?"Enhanced":"Standard")+"; " + effectiveReason();
    }
}
