package celeste.titanium.client.render;

import celeste.titanium.client.TitaniumClient;
import celeste.titanium.client.config.UpscaleBackend;
import celeste.titanium.client.config.TitaniumConfig;
import celeste.titanium.client.debug.DlssDebugState;
import celeste.titanium.client.debug.DlssDebugTelemetry;
import celeste.titanium.client.debug.DlssQualityTelemetry;
import celeste.titanium.client.debug.DlssMode;
import celeste.titanium.client.debug.DlssModel;
import celeste.titanium.client.debug.DlssRuntimeState;
import celeste.titanium.client.nativebridge.DlssNativeCapability;
import celeste.titanium.client.nativebridge.NativeBridge;
import celeste.titanium.client.nativebridge.DiagnosticTimingPolicy;
import celeste.titanium.client.nativebridge.MinecraftTextureInteropProbe;
import celeste.titanium.client.upscale.BilinearBackend;
import celeste.titanium.mixin.client.MainRenderTargetAccessor;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.state.WindowRenderState;

/** ワールドの描画先を切り替え、拡大後にネイティブ解像度のGUIへ描画状態を返す。 */
public final class UpscalePipeline implements AutoCloseable {
    // ワールド描画・拡大後の描画・フレーム外を区別し、途中の描画先切り替えを管理する。
    private enum RenderStage { IDLE, LOW_RES_WORLD, NATIVE_POST_WORLD }

    // ワールド用ターゲットとDLSSの入力・出力は別々に所有し、寸法変更時に再構成する。
    private final RenderTargetManager targets = new RenderTargetManager();
    private final BilinearBackend bilinear = new BilinearBackend();
    private final DlssProductSession productSession = new DlssProductSession();
    private final DlssGpuSession productGpuSession = new DlssGpuSession(
            FabricLoader.getInstance().getGameDir().resolve("titanium/ngx-logs"));
    // 診断処理は起動プロパティで明示された場合だけ生成する。通常描画では読み戻しを追加しない。
    private final MinecraftTextureInteropProbe nativeProbe = Boolean.getBoolean("titanium.nativeProbe")
            ? new MinecraftTextureInteropProbe() : null;
    private final DepthSnapshotProbe depthProbe = Boolean.getBoolean("titanium.depthProbe")
            ? new DepthSnapshotProbe() : null;
    private final FrameInputProbe frameInputProbe = (Boolean.getBoolean("titanium.frameInputProbe")
            || Boolean.getBoolean("titanium.depthReconstructionProbe")
            || Boolean.getBoolean("titanium.motionVectorProbe")
            || Boolean.getBoolean("titanium.jitterProbe"))
            ? new FrameInputProbe() : null;
    private final boolean jitterProbe = Boolean.getBoolean("titanium.jitterProbe");
    private final NgxDisplayMode displayMode = NgxDisplayMode.configured();
    private final boolean qualityDebugEnabled = Boolean.getBoolean("titanium.debugDlssQuality");
    private final boolean terrainLodBiasEnabled;
    private final FramePacingTracker framePacing = new FramePacingTracker();
    private final int minimumJitterPhases = JitterSequence.configuredMinimumPhases();
    // 早期DLAAの実行結果は同一フレームのパック後処理が終わるまで保持する。
    private GlobalSettingsUniform worldGlobals;
    private boolean preAaDlaaAttempted;
    private FrameInputPacket preAaDlaaPacket;
    private RuntimeException preAaDlaaFailure;
    private boolean cutoutTaaFrame,cutoutTaaMerged;
    private int preAaReadFbo,preAaDrawFbo;
    private long preAaDlaaFrames;
    // 以下の参照はワールド描画中だけ借用する。GUIへ戻すため、元の寸法とuniformも保存する。
    private boolean active;
    private RenderStage renderStage = RenderStage.IDLE;
    private GameRenderer frameRenderer;
    private RenderTarget frameNativeTarget;
    private RenderTarget frameWorldTarget;
    private WindowRenderState frameWindow;
    private GpuBuffer frameNativeGlobals;
    private int frameNativeWidth;
    private int frameNativeHeight;
    private float frameNativeLineWidth;
    private boolean frameHandledBeforeHand;
    private long dlssDisplayFrames;
    private long dlssDisplayFallbacks;
    private boolean backendChecked;
    private boolean openGl;
    private boolean failed;
    private int worldWidth;
    private int worldHeight;
    private int outputWidth;
    private int outputHeight;
    private String status = "Waiting for renderer";
    private long scaledFrames;
    // 入力・出力寸法とディメンションを履歴と組にして管理し、変更時にサンプル列を再開する。
    private JitterSequence jitterSequence;
    private FrameJitter frameJitter = FrameJitter.none();
    private boolean jitterHistoryBroken = true;
    private int jitterOutputWidth;
    private int jitterOutputHeight;
    private int jitterRenderWidth;
    private int jitterRenderHeight;
    private String jitterDimension;
    private DlssProductSession.Capability productCapability;
    private DlssPreset productConfiguredPreset = DlssPreset.SDK_DEFAULT;
    private DlssFrameObserver diagnosticFrameObserver;
    private FrameInputRuntime productFrameInput;
    private DlssProductSession.Request productFrameRequest;
    private final IrisFrameBoundary irisFrameBoundary = new IrisFrameBoundary();
    private boolean terrainLodBiasLogged;
    // 通常地形、不透明地形のAF、IrisのLOD差分は、それぞれ独立したサンプラーで適用する。
    private final TerrainDetailSampler terrainDetailSampler = new TerrainDetailSampler();
    private final TerrainDetailSampler solidTerrainDetailSampler = new TerrainDetailSampler(8);
    private final TerrainDetailSampler irisResolutionSampler = TerrainDetailSampler.preservingSource();
    private boolean irisResolutionScope;
    private float irisResolutionBias;
    private boolean terrainDetailScope;
    private boolean solidTerrainDetailScope;
    private float terrainDetailBias;
    private boolean configuredTerrainDetail = true;

    public UpscalePipeline() {
        FabricLoader loader = FabricLoader.getInstance();
        terrainLodBiasEnabled = Boolean.getBoolean("titanium.debugTerrainLodBias")
                && !loader.isModLoaded("iris") && !loader.isModLoaded("sodium");
        if (displayMode == NgxDisplayMode.VANILLA && loader.isModLoaded("iris")) {
            throw new IllegalStateException("P10B Vanilla display probe cannot run with Iris loaded");
        }
        if (displayMode == NgxDisplayMode.IRIS) {
            IrisSolasEnvironment.requireInstalled(loader, "P10C");
        }
    }

    /**
     * 独立診断用の通常地形LOD補正が適用可能な場合に、解像度比の差分を返す。
     *
     * @return 診断用の通常地形LOD差分。非対象時は0
     */
    public float terrainLodBias() {
        if (!terrainLodBiasEnabled || !openGl || !active || renderStage != RenderStage.LOW_RES_WORLD) {
            return 0.0F;
        }
        DlssProductSession.Request request = currentProductRequest();
        if (request == null || request.displayPath() != DlssProductSession.DisplayPath.VANILLA) {
            return 0.0F;
        }
        float bias = TerrainLodBias.calculate(worldWidth, worldHeight, outputWidth, outputHeight);
        if (bias != 0.0F && !terrainLodBiasLogged) {
            terrainLodBiasLogged = true;
            TitaniumClient.LOGGER.info("[Titanium/P13C] Vanilla chunk terrain LOD bias={} for {}x{} -> {}x{}",
                    bias, worldWidth, worldHeight, outputWidth, outputHeight);
        }
        return bias;
    }

    /**
     * 有効な製品要求と送信プリセットに対応する通常地形LOD差分を求める。
     *
     * @return 現在の製品要求へ適用する通常地形LOD差分。非対象時は0
     */
    public float terrainDetailBias() {
        if (!terrainDetailEligible()) return 0;
        DlssProductSession.Request request = currentProductRequest();
        DlssFeatureMode mode = request.featureMode();
        // プリセットはフレーム境界で一度だけ同期し、地形描画ごとの取得を避ける。
        return DlssQualityPolicy.resolve(mode, productConfiguredPreset).terrainLodBias(
                mode, worldWidth, worldHeight, outputWidth, outputHeight);
    }

    /**
     * 有効設定、バックエンド、描画範囲と製品経路から通常地形補正の適用可否を判定する。
     *
     * @return この地形描画で通常経路の製品サンプラー補正を適用できる場合はtrue
     */
    private boolean terrainDetailEligible() {
        TitaniumConfig config = TitaniumClient.config();
        DlssProductSession.Request request = currentProductRequest();
        if (!config.enabled() || !config.dlssTerrainDetail() || config.backend() != UpscaleBackend.DLSS
                || !openGl || failed || !active || renderStage != RenderStage.LOW_RES_WORLD
                || displayMode.visible() || request == null
                || !DlssQualityPolicy.terrainSamplingOverridesAllowed(request.displayPath())) return false;
        return true;
    }

    public boolean terrainSamplingOverridesAllowed() {
        var request = currentProductRequest();
        return request != null && DlssQualityPolicy.terrainSamplingOverridesAllowed(request.displayPath());
    }

    // 地形描画の開始から終了までに補正を限定し、影描画や後続のGUIへの状態漏れを防ぐ。
    /**
     * 影描画を除く地形描画のLOD補正区間を開始し、実際に開始できたか返す。
     *
     * @return 影描画を除く地形描画のLOD補正区間を開始し、実際に開始できた場合はtrue
     */
    public boolean beginTerrainDetail() {
        float bias = terrainDetailBias();
        if (bias == 0 || IrisShadowScope.excluded() || terrainDetailScope) return false;
        terrainDetailScope = true;
        terrainDetailBias = bias;
        return true;
    }

    public void endTerrainDetail() { terrainDetailScope = false; terrainDetailBias = 0; }

    /**
     * Default対応モードの不透明地形でAF8補正区間を開始できるか判定し、状態を記録する。
     *
     * @param solid 実描画が不透明地形passに属する場合はtrue
     * @return この呼び出しで不透明地形のAF補正区間を新しく開始した場合はtrue
     */
    public boolean beginSolidTerrainDetail(boolean solid) {
        if (!solid || solidTerrainDetailScope || !terrainDetailEligible() || IrisShadowScope.excluded()
                || Boolean.getBoolean("titanium.disableSolidTerrainAnisotropy")) return false;
        var request = currentProductRequest();
        if (DlssQualityPolicy.resolve(request.featureMode(), productConfiguredPreset).solidTerrainAnisotropy() != 8)
            return false;
        solidTerrainDetailScope = true;
        return true;
    }

    public void endSolidTerrainDetail() { solidTerrainDetailScope = false; }

    /**
     * Irisの対応地形passで元のサンプラーを保持する解像度差LOD補正区間を開始する。
     *
     * @param selectedTerrainPass 対応する実地形passの範囲内である場合はtrue
     * @return この呼び出しでIris地形のLOD補正区間を新しく開始した場合はtrue
     */
    public boolean beginIrisResolutionDetail(boolean selectedTerrainPass) {
        var config = TitaniumClient.config();
        var request = currentProductRequest();
        if (!selectedTerrainPass || irisResolutionScope || IrisShadowScope.excluded()
                || !config.enabled() || !config.dlssTerrainDetail() || config.backend() != UpscaleBackend.DLSS
                || !openGl || failed || !active || renderStage != RenderStage.LOW_RES_WORLD
                || displayMode.visible() || request == null
                || request.displayPath() != DlssProductSession.DisplayPath.IRIS_SOLAS) return false;
        // Irisでは元パックのLODを基準に解像度差分を加算し、対応するEnhancedモードだけ対象を広げる。
        var policy = DlssQualityPolicy.resolve(request.featureMode(), productConfiguredPreset);
        float bias = policy.irisResolutionMipBias(worldWidth, worldHeight, outputWidth, outputHeight);
        if (bias == 0 && enhancedTerrainFootprintAllowed(policy))
            bias = EnhancedTerrainFootprint.bias(policy, worldWidth, worldHeight, outputWidth, outputHeight);
        if (bias == 0) return false;
        irisResolutionScope = true;
        irisResolutionBias = bias;
        return true;
    }

    public void endIrisResolutionDetail() { irisResolutionScope = false; irisResolutionBias = 0; }

    public TerrainDetailSampler irisResolutionSampler() { return irisResolutionSampler; }

    private boolean enhancedTerrainFootprintAllowed(DlssQualityPolicy policy) {
        return TitaniumClient.config().dlssIntegrationMode() == celeste.titanium.client.config.DlssIntegrationMode.ENHANCED
                && EnhancedTerrainFootprint.allowed(policy);
    }

    private DlssProductSession.Request enhancedFootprintAppliedRequest;

    /**
     * 最後に実バインドした補正要求と現在の設定が一致し、Enhancedの入力LOD補正が有効か確認する。
     *
     * @return 最後に実バインドした補正要求と現在の設定が一致し、Enhancedの入力LOD補正が有効な場合はtrue
     */
    public boolean enhancedInputFootprintActive(){
        var request=currentProductRequest();var config=TitaniumClient.config();
        // 'active' is the intra-world-frame scope and is false after restoreNativeState().
        // Report the actual completed-frame binding, as the AA adapter's status also does.
        return request!=null&&request.equals(enhancedFootprintAppliedRequest)&&!failed
                &&request.displayPath()==DlssProductSession.DisplayPath.IRIS_SOLAS&&config.enabled()&&config.dlssTerrainDetail()
                &&config.backend()==UpscaleBackend.DLSS&&enhancedTerrainFootprintAllowed(DlssQualityPolicy.resolve(request.featureMode(),productConfiguredPreset));
    }
    public void invalidateEnhancedFootprintEvidence(){enhancedFootprintAppliedRequest=null;}

    /**
     * 実地形のテクスチャバインド時に、現在のスコープに対応する補正サンプラーを返す。
     *
     * @param name 対象または診断区間を識別する名前
     * @param sampler 対象のサンプラー
     * @return この地形範囲へ適した所有補正サンプラー。非対象時は元のサンプラー
     */
    public GpuSampler terrainAtlasSampler(String name, GpuSampler sampler) {
        // 解像度差分の経路でも、元サンプラーのLODバイアス・AF・フィルターを保持する。
        // バインド時にも設定と描画経路を確認し、古い材質スコープの適用を防ぐ。
        var request = currentProductRequest();
        if (irisResolutionScope && "u_BlockTex".equals(name) && request != null
                && request.displayPath() == DlssProductSession.DisplayPath.IRIS_SOLAS
                && TitaniumClient.config().enabled() && TitaniumClient.config().dlssTerrainDetail()
                && TitaniumClient.config().backend() == UpscaleBackend.DLSS
                && (DlssQualityPolicy.resolve(request.featureMode(), productConfiguredPreset).irisResolutionMipAllowed()
                    || enhancedTerrainFootprintAllowed(DlssQualityPolicy.resolve(request.featureMode(), productConfiguredPreset)))){
            boolean enhanced=enhancedTerrainFootprintAllowed(DlssQualityPolicy.resolve(request.featureMode(),productConfiguredPreset));
            var returned=enhanced?irisResolutionSampler.resolveEnhancedFootprint(sampler,irisResolutionBias):irisResolutionSampler.resolve(sampler,irisResolutionBias);
            if(enhanced)enhancedFootprintAppliedRequest=returned!=sampler?request:null;
            return returned;
        }
        // LODバイアスや独自フィルターを含め、Irisの実サンプラーを基準にする。
        // 描画経路が変わった後に旧スコープが残らないよう、バインド境界でも確認する。
        if (!terrainSamplingOverridesAllowed()) return sampler;
        if (solidTerrainDetailScope && "u_BlockTex".equals(name)) {
            GpuSampler detailed = solidTerrainDetailSampler.resolve(sampler, terrainDetailBias());
            if (detailed != sampler) return detailed;
            // AF非対応でも、解像度差だけの補正は継続する。
        }
        return terrainDetailScope && "u_BlockTex".equals(name)
                ? terrainDetailSampler.resolve(sampler, terrainDetailBias) : sampler;
    }

    public TerrainDetailSampler terrainDetailSampler() { return terrainDetailSampler; }
    public TerrainDetailSampler solidTerrainDetailSampler() { return solidTerrainDetailSampler; }
    public TerrainDetailSampler activeTerrainDetailSampler() {
        return solidTerrainDetailScope ? solidTerrainDetailSampler : terrainDetailSampler;
    }

    /**
     * 実描画先の色・深度がOpenGLであるか一度だけ確認し、GPUとバックエンド情報を記録する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param target 操作対象の描画先またはリソース
     */
    public void checkBackend(RenderTarget target) {
        RenderSystem.assertOnRenderThread();
        // 描画先の実テクスチャからOpenGLを一度だけ判定し、バックエンドの取り違えを防ぐ。
        if (backendChecked) {
            return;
        }
        backendChecked = true;
        openGl = target.getColorTexture() instanceof GlTexture && target.getDepthTexture() instanceof GlTexture;
        var device = RenderSystem.getDevice().getDeviceInfo();
        status = openGl ? "OpenGL ready; Bilinear" : "Unsupported renderer; native resolution";
        TitaniumClient.LOGGER.info("[Titanium] {} | GPU={} | driver={} | backend={}",
                status, device.name(), device.driverInfo(), device.backendName());
    }

    /**
     * 元の描画状態を保存してワールドを入力解像度へ描き、終了時に出力解像度のGUI状態へ戻す。
     *
     * 描画スレッドから呼び出す。
     *
     * finallyでmainターゲット、画面寸法、線幅、共通uniformを戻す。ワールド描画そのものの例外は呼び出し元へ伝える。
     *
     * @param renderer 対象のワールドまたはIris renderer
     * @param delta 現在フレームのMinecraft時間・補間情報
     * @param drawWorld 元のワールド描画を一度だけ実行する処理
     */
    public void renderLevel(GameRenderer renderer, DeltaTracker delta, Runnable drawWorld) {
        RenderSystem.assertOnRenderThread();
        if (qualityDebugEnabled) {
            framePacing.sample(System.nanoTime(), Minecraft.getInstance().gui.screen() != null);
        }
        if (depthProbe != null) depthProbe.poll();
        if (frameInputProbe != null) frameInputProbe.poll();
        if (renderStage != RenderStage.IDLE) {
            throw new IllegalStateException("Nested Titanium world render");
        }
        RenderTarget nativeTarget = renderer.mainRenderTarget();
        checkBackend(nativeTarget);
        TitaniumConfig config = TitaniumClient.config();
        // 設定から必要なDLSS状態を決めた後、その要求と実際のGPUリソースを揃える。
        synchronizeProductSession(config, nativeTarget);
        reconcileProductResources();
        DlssProductSession.Request productRequest = currentProductRequest();
        if (!config.enabled() || (productRequest == null && config.renderScale() == 1.0) || !openGl || failed) {
            breakFrameHistory("INACTIVE");
            // 他の描画パイプラインが直前の main target をフレーム境界を越えて参照する場合がある。
            // 一時的な無効化では破棄せず、GameRenderer の終了時まで所有権を維持する。
            drawWorld.run();
            return;
        }

        WindowRenderState window = renderer.gameRenderState().windowRenderState;
        if (window.isMinimized || nativeTarget.width < 1 || nativeTarget.height < 1) {
            breakFrameHistory("MINIMIZED");
            drawWorld.run();
            return;
        }
        // 低解像度のワールド描画先を準備する。確保失敗時は元の描画先でワールドを一度だけ描く。
        RenderTarget worldTarget;
        try {
            RenderResolution size = productRequest == null
                    ? RenderResolution.scaled(nativeTarget.width, nativeTarget.height, config.renderScale())
                    : new RenderResolution(productRequest.renderWidth(), productRequest.renderHeight());
            worldTarget = targets.prepare(size);
            if (worldGlobals == null) {
                worldGlobals = new GlobalSettingsUniform();
            }
        } catch (RuntimeException e) {
            disable(e);
            targets.close();
            drawWorld.run();
            return;
        }

        // GUI描画へ正確に戻せるよう、ターゲットに加えて画面寸法・線幅・共通uniformを保存する。
        int nativeWidth = window.width;
        int nativeHeight = window.height;
        float nativeLineWidth = window.appropriateLineWidth;
        GpuBuffer nativeGlobals = RenderSystem.getGlobalSettingsUniform();
        MainRenderTargetAccessor setter = (MainRenderTargetAccessor) renderer;
        worldWidth = worldTarget.width;
        worldHeight = worldTarget.height;
        outputWidth = nativeTarget.width;
        outputHeight = nativeTarget.height;
        beginJitterFrame(nativeTarget, worldTarget);
        frameRenderer = renderer;
        frameNativeTarget = nativeTarget;
        frameWorldTarget = worldTarget;
        frameWindow = window;
        frameNativeGlobals = nativeGlobals;
        frameNativeWidth = nativeWidth;
        frameNativeHeight = nativeHeight;
        frameNativeLineWidth = nativeLineWidth;
        frameHandledBeforeHand = false;
        preAaDlaaAttempted=false;preAaDlaaPacket=null;preAaDlaaFailure=null;cutoutTaaFrame=false;cutoutTaaMerged=false;
        irisFrameBoundary.startFrame();
        active = true;
        renderStage = RenderStage.LOW_RES_WORLD;
        boolean worldCompleted = false;
        boolean handledBeforeHand;
        try {
            setter.titanium$setMainRenderTarget(worldTarget);
            // 26.2 は Window の値を extract 時点で保存するため、描画用 snapshot も合わせる。
            window.width = worldWidth;
            window.height = worldHeight;
            window.appropriateLineWidth = nativeLineWidth * worldWidth / nativeTarget.width;
            var state = renderer.gameRenderState();
            Minecraft client = Minecraft.getInstance();
            // ワールド描画中の共通uniformには入力解像度を渡し、画面寸法を参照するエフェクトと一致させる。
            worldGlobals.update(worldWidth, worldHeight, state.optionsRenderState.glintStrength,
                    client.level.getGameTime(), delta, state.optionsRenderState.menuBackgroundBlurriness,
                    state.levelRenderState.cameraRenderState.pos,
                    state.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS);
            drawWorld.run();
            worldCompleted = true;
        } finally {
            // World の例外を握り潰さず、GUI 用状態だけは必ず返却する。
            handledBeforeHand = frameHandledBeforeHand;
            restoreNativeState();
            renderStage = RenderStage.IDLE;
            clearFrameSession();
            if (!worldCompleted) breakFrameHistory("WORLD_RENDER_FAILED");
        }

        // 手の描画境界ですでにDLSS出力を返した場合、後段のBilinearコピーを重ねない。
        if (handledBeforeHand) return;
        try {
            if (nativeProbe != null) nativeProbe.capture(worldTarget);
            if (depthProbe != null) depthProbe.poll();
            bilinear.dispatch(worldTarget, nativeTarget);
            scaledFrames++;
            String nextStatus = "Bilinear " + worldWidth + "x" + worldHeight + " -> "
                    + nativeTarget.width + "x" + nativeTarget.height + "; GUI native";
            // 対応条件からの逸脱でdisable済みなら、同一frameの安全なBilinear fallbackが
            // 停止理由を古い寸法の通常statusで上書きしてはならない。
            if (!failed && !nextStatus.equals(status)) {
                status = nextStatus;
                TitaniumClient.LOGGER.info("[Titanium] {}", status);
            }
        } catch (RuntimeException e) {
            // 再描画は world の副作用を二重実行するため、次フレームから native に戻す。
            disable(e);
        }
    }

    /**
     * 旧診断表示のDLSS出力を手の描画境界で返し、失敗時は所有権が安全な範囲でBilinearへ退避する。
     *
     * @param renderer 対象のワールドまたはIris renderer
     * @return 手の描画境界で出力または退避を処理済みの場合はtrue。DLSSの成功判定とは独立
     */
    private boolean finishWorldForDisplay(GameRenderer renderer) {
        if (!displayMode.visible()) return false;
        if (renderStage == RenderStage.IDLE) return false;
        if (renderStage != RenderStage.LOW_RES_WORLD || renderer != frameRenderer
                || frameNativeTarget == null || frameWorldTarget == null) {
            disable(new IllegalStateException(displayMode.phase() + " invalid post-world render state"));
            return false;
        }
        try {
            checkDisplayEnvironment();
        } catch (RuntimeException error) {
            disable(error);
            return false;
        }
        boolean displayed = frameInputProbe != null && frameInputProbe.complete(frameWorldTarget,
                renderer.mainCamera().getFov(), frameNativeTarget.width, frameNativeTarget.height,
                frameNativeTarget);
        boolean fallbackSafe = frameInputProbe == null || frameInputProbe.frameOutputFallbackSafe();
        restoreNativeState();
        frameHandledBeforeHand = true;
        if (displayed) {
            dlssDisplayFrames++;
            scaledFrames++;
            String nextStatus = "DLSS " + worldWidth + "x" + worldHeight + " -> "
                    + frameNativeTarget.width + "x" + frameNativeTarget.height
                    + "; post-world native (" + displayMode.label() + ")";
            if (!nextStatus.equals(status)) {
                status = nextStatus;
                TitaniumClient.LOGGER.info("[Titanium/{}] {}", displayMode.phase(), status);
            }
            return true;
        }
        dlssDisplayFallbacks++;
        if (fallbackSafe) {
            try {
                bilinear.dispatch(frameWorldTarget, frameNativeTarget);
            } catch (RuntimeException error) {
                disable(error);
                return true;
            }
        }
        disable(new IllegalStateException(displayMode.phase() + " DLSS display failed; fallbackSafe=" + fallbackSafe
                + "; " + frameInteropProbeDiagnostics()));
        return true;
    }

    /**
     * 同一フレームの入力とIris完了を確認し、通常または早期DLAAの製品出力をネイティブ描画先へ返す。
     *
     * 早期DLAAのフレームではパック後処理まで終わった色を返し、通常経路ではここで入力を確定してDLSSを評価する。
     *
     * @param renderer 対象のワールドまたはIris renderer
     * @return 有効要求の出力または失敗退避をこの境界で処理した場合はtrue
     */
    private boolean finishProductWorld(GameRenderer renderer) {
        DlssProductSession.Request request = currentProductRequest();
        if (request == null) return false;
        if (renderStage != RenderStage.LOW_RES_WORLD || renderer != frameRenderer
                || frameNativeTarget == null || frameWorldTarget == null
                || productFrameInput == null || !request.equals(productFrameRequest)) {
            failProductFrame(request, new IllegalStateException("Invalid product DLSS post-world render state"), false);
            return true;
        }

        boolean fallbackSafe = true;
        try {
            if (request.displayPath() == DlssProductSession.DisplayPath.IRIS_SOLAS) {
                IrisEnvironment.requireActive("Product Iris");
                if (!irisFrameBoundary.completed())
                    throw new IllegalStateException("Iris world finalization was not observed for this frame");
            }
            if(preAaDlaaFailure!=null)throw preAaDlaaFailure;
            // 早期DLAAでは後続のパック処理まで完了した色がワールドターゲットに入っている。
            if(preAaDlaaPacket!=null){
                if(cutoutTaaFrame&&!cutoutTaaMerged)throw new IllegalStateException("Cutout TAA return did not complete");
                // DLAA keeps both RGBA8 targets at the same extent. Preserve the pack's
                // completed pixels directly instead of drawing an unnecessary filtered quad.
                org.lwjgl.opengl.GL43C.glCopyImageSubData(
                        ((GlTexture)frameWorldTarget.getColorTexture()).glId(),org.lwjgl.opengl.GL11C.GL_TEXTURE_2D,0,0,0,0,
                        ((GlTexture)frameNativeTarget.getColorTexture()).glId(),org.lwjgl.opengl.GL11C.GL_TEXTURE_2D,0,0,0,0,
                        worldWidth,worldHeight,1);
                if(diagnosticFrameObserver!=null)diagnosticFrameObserver.afterReturn(frameNativeTarget,preAaDlaaPacket);
                if(request.equals(productSession.state().pendingRequest()))productSession.markActive(request);
                restoreNativeState();frameHandledBeforeHand=true;dlssDisplayFrames++;scaledFrames++;
                status="DLAA before pack main TAA; pack post-processing retained";
                return true;
            }
            // 通常経路はワールド終了時に入力を確定し、同一シーケンスの深度とMVをDLSSへ渡す。
            FrameInputRuntime.Completion completion = productFrameInput.complete(
                    frameWorldTarget, renderer.mainCamera().getFov());
            if (completion == null) throw new IllegalStateException("Product DLSS frame inputs are incomplete");
            if (!completion.frame().valid()) {
                throw new IllegalStateException("Product DLSS frame packet is invalid: "
                        + productFrameInput.diagnostics());
            }
            if (completion.motionFailure() != null) throw completion.motionFailure();
            if (!completion.motionReady()) {
                throw new IllegalStateException("Product DLSS motion vector is unavailable");
            }
            FrameInputPacket packet = completion.frame().current();
            int motionTexture = productFrameInput.motionProducer().motionGlId(packet.sequence());
            productGpuSession.prepare(request, frameWorldTarget);
            productGpuSession.evaluate(frameWorldTarget, motionTexture, packet, completion.profile(),
                    completion.captureNanos(), frameNativeTarget,
                    TitaniumClient.config().dlssSharpness(), diagnosticFrameObserver);
            DlssGpuSession.State gpuState = productGpuSession.state();
            fallbackSafe = gpuState.fallbackSafe();
            if (!gpuState.activationReady()) {
                throw new IllegalStateException("Product DLSS output was not returned completely");
            }
            // 出力の返却が成功してから有効状態へ移し、Featureの生成だけで成功扱いにしない。
            if (request.equals(productSession.state().pendingRequest())) productSession.markActive(request);

            restoreNativeState();
            frameHandledBeforeHand = true;
            dlssDisplayFrames++;
            scaledFrames++;
            String profile = request.displayPath() == DlssProductSession.DisplayPath.IRIS_SOLAS
                    ? "Product Iris" : "Product Vanilla";
            String nextStatus = "DLSS " + request.renderWidth() + "x" + request.renderHeight() + " -> "
                    + request.outputWidth() + "x" + request.outputHeight() + "; post-world native (" + profile + ")";
            if (!nextStatus.equals(status)) {
                status = nextStatus;
                TitaniumClient.LOGGER.info("[Titanium/{}] {}", productPhase(request), status);
            }
            return true;
        } catch (RuntimeException error) {
            DlssGpuSession.State gpuState = productGpuSession.state();
            fallbackSafe = fallbackSafe && gpuState.fallbackSafe();
            failProductFrame(request, error, fallbackSafe);
            return true;
        }
    }

    /**
     * 製品要求の失敗を記録し、資源を解放して所有権の安全性に応じた表示退避を実行する。
     *
     * @param request 現在の寸法・品質・深度方向・描画経路を持つ要求
     * @param error 失敗した操作の結果または例外
     * @param fallbackSafe 共有所有権が確定しており通常コピーへ退避できる場合はtrue
     */
    private void failProductFrame(DlssProductSession.Request request, RuntimeException error,
                                  boolean fallbackSafe) {
        RuntimeException cleanupFailure = null;
        String reason = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
        try {
            productSession.markError(request, reason);
        } catch (RuntimeException stateFailure) {
            error.addSuppressed(stateFailure);
        }
        boolean gpuReleased = false;
        try {
            productGpuSession.release();
            gpuReleased = true;
        } catch (RuntimeException releaseFailure) {
            error.addSuppressed(releaseFailure);
            cleanupFailure = releaseFailure;
            fallbackSafe = false;
        }
        if (!fallbackSafe && cleanupFailure == null) {
            cleanupFailure = new IllegalStateException("Product DLSS GPU ownership is uncertain");
            error.addSuppressed(cleanupFailure);
        }
        if (gpuReleased) {
            try {
                closeProductFrameInput();
            } catch (RuntimeException closeFailure) {
                error.addSuppressed(closeFailure);
                cleanupFailure = closeFailure;
            }
        }
        restoreNativeState();
        frameHandledBeforeHand = true;
        dlssDisplayFallbacks++;
        if (fallbackSafe) {
            try {
                bilinear.dispatch(frameWorldTarget, frameNativeTarget);
                scaledFrames++;
            } catch (RuntimeException bilinearFailure) {
                error.addSuppressed(bilinearFailure);
                cleanupFailure = bilinearFailure;
            }
        }
        status = "Product DLSS failed; Bilinear fallback"
                + (fallbackSafe ? "" : " unavailable") + " (" + reason + ")";
        TitaniumClient.LOGGER.error("[Titanium/{}] {}; gpu={}", productPhase(request), status,
                productGpuSession.diagnostics(), error);
        if (cleanupFailure != null) disable(error);
    }

    private void checkDisplayEnvironment() {
        if (displayMode != NgxDisplayMode.IRIS) return;
        IrisSolasEnvironment.requireActive("P10C");
    }

    /**
     * 保存したmainターゲット、画面寸法、線幅、共通uniformを出力解像度の状態へ戻す。
     */
    private void restoreNativeState() {
        if (renderStage != RenderStage.LOW_RES_WORLD) return;
        ((MainRenderTargetAccessor) frameRenderer).titanium$setMainRenderTarget(frameNativeTarget);
        active = false;
        frameWindow.width = frameNativeWidth;
        frameWindow.height = frameNativeHeight;
        frameWindow.appropriateLineWidth = frameNativeLineWidth;
        RenderSystem.setGlobalSettingsUniform(frameNativeGlobals);
        GlStateManager._viewport(0, 0, frameNativeTarget.width, frameNativeTarget.height);
        renderStage = RenderStage.NATIVE_POST_WORLD;
    }

    /**
     * 完了したワールドフレームの借用参照と進行状態を消去する。
     */
    private void clearFrameSession() {
        active = false;
        frameRenderer = null;
        frameNativeTarget = null;
        frameWorldTarget = null;
        frameWindow = null;
        frameNativeGlobals = null;
        frameHandledBeforeHand = false;
    }

    /**
     * パイプラインの失敗を記録し、以後のワールド解像度制御を停止する。
     *
     * @param error 失敗した操作の結果または例外
     */
    private void disable(RuntimeException error) {
        breakFrameHistory("PIPELINE_DISABLED");
        failed = true;
        status = "Bilinear failed; native resolution until restart (" + error.getClass().getSimpleName() + ")";
        TitaniumClient.LOGGER.error("[Titanium] {}", status, error);
    }

    public int windowWidth(int original) {
        return active ? worldWidth : original;
    }

    public int windowHeight(int original) {
        return active ? worldHeight : original;
    }

    /**
     * 描画の有効状態と入力・出力寸法を状態表示文字列で返す。
     *
     * @return 描画経路・寸法と、必要な場合は退避理由を含む状態表示
     */
    public String status() {
        TitaniumConfig config = TitaniumClient.config();
        if (failed || !openGl) {
            return status;
        }
        if (!config.enabled() || (currentProductRequest() == null && config.renderScale() == 1.0)) {
            return "Off; native resolution";
        }
        return status + "; frames=" + scaledFrames + productSessionSuffix();
    }

    /**
     * 描画の有効状態と入力・出力寸法を状態表示文字列で返す。
     *
     * @param currentNativeWidth 現在の出力ターゲットの幅。ピクセル単位
     * @param currentNativeHeight 現在の出力ターゲットの高さ。ピクセル単位
     * @return 描画経路・寸法と、必要な場合は退避理由を含む状態表示
     */
    public String status(int currentNativeWidth, int currentNativeHeight) {
        if (currentNativeWidth < 1 || currentNativeHeight < 1) return status();
        String nativeSize = currentNativeWidth + "x" + currentNativeHeight;
        TitaniumConfig config = TitaniumClient.config();
        if (failed || !openGl) {
            return status + "; current native=" + nativeSize;
        }
        DlssProductSession.Request productRequest = currentProductRequest();
        if (!config.enabled() || (productRequest == null && config.renderScale() == 1.0)) {
            return "Off; native=" + nativeSize;
        }
        RenderResolution renderSize = productRequest == null
                ? RenderResolution.scaled(currentNativeWidth, currentNativeHeight, config.renderScale())
                : new RenderResolution(productRequest.renderWidth(), productRequest.renderHeight());
        String current = productRequest != null && productSession.state().runtimeState() == DlssRuntimeState.ACTIVE
                ? "DLSS " + renderSize.width() + "x" + renderSize.height() + " -> " + nativeSize
                        + "; post-world native (Product Vanilla)"
                : displayMode.visible()
                ? "DLSS " + renderSize.width() + "x" + renderSize.height() + " -> " + nativeSize
                        + "; post-world native (" + displayMode.label() + ")"
                : "Bilinear " + renderSize.width() + "x" + renderSize.height() + " -> " + nativeSize
                        + "; GUI native";
        return current + "; frames=" + scaledFrames + productSessionSuffix();
    }

    public long dlssDisplayFrames() {
        return dlssDisplayFrames;
    }

    public long dlssDisplayFallbacks() {
        return dlssDisplayFallbacks;
    }

    public ProductTemporalState productTemporalState() {
        if (productFrameInput == null) return ProductTemporalState.unavailable();
        DlssGpuSession.State gpu = productGpuSession.state();
        return new ProductTemporalState(true,
                productFrameInput.latestSequence(), productFrameInput.previousSequence(),
                gpu.lastEvaluateSequence(), productFrameInput.historyGeneration(),
                productFrameInput.framesSinceReset(), productFrameInput.lastResetReason(),
                gpu.generation(), gpu.evaluatedFrames(), gpu.returnedFrames());
    }

    /**
     * 要求と実描画のモード・寸法・プリセット・実行状態をまとめたスナップショットを返す。
     *
     * @param currentNativeWidth 現在の出力ターゲットの幅。ピクセル単位
     * @param currentNativeHeight 現在の出力ターゲットの高さ。ピクセル単位
     * @return 同じ描画時点の要求・有効バックエンドと診断値
     */
    public DlssDebugState debugState(int currentNativeWidth, int currentNativeHeight) {
        TitaniumConfig config = TitaniumClient.config();
        int safeOutputWidth = Math.max(1, currentNativeWidth);
        int safeOutputHeight = Math.max(1, currentNativeHeight);
        DlssProductSession.State productState = productSession.state();
        DlssProductSession.Request productRequest = productState.activeRequest() != null
                ? productState.activeRequest() : productState.pendingRequest();
        RenderResolution computed = productRequest == null
                ? RenderResolution.scaled(safeOutputWidth, safeOutputHeight,
                        config.enabled() ? config.renderScale() : 1.0)
                : new RenderResolution(productRequest.renderWidth(), productRequest.renderHeight());
        boolean lastDimensionsMatch = config.enabled() && outputWidth == safeOutputWidth
                && outputHeight == safeOutputHeight && worldWidth == computed.width() && worldHeight == computed.height();
        int actualRenderWidth = lastDimensionsMatch ? worldWidth : computed.width();
        int actualRenderHeight = lastDimensionsMatch ? worldHeight : computed.height();
        double actualScale = Math.min((double) actualRenderWidth / safeOutputWidth,
                (double) actualRenderHeight / safeOutputHeight);
        boolean probeRequested = displayMode.visible();
        UpscaleBackend requested = probeRequested ? UpscaleBackend.DLSS : productState.backend().requested();
        boolean dlssActive = probeRequested && !failed && dlssDisplayFrames > 0;
        DlssRuntimeState runtime = failed && requested == UpscaleBackend.DLSS
                ? DlssRuntimeState.ERROR
                : dlssActive ? DlssRuntimeState.ACTIVE
                : probeRequested ? DlssRuntimeState.INITIALIZING
                : productState.runtimeState();
        UpscaleBackend activeBackend = dlssActive ? UpscaleBackend.DLSS : productState.backend().active();
        DlssMode mode = productRequest != null
                ? DlssMode.valueOf(productRequest.featureMode().name())
                : Math.abs(actualScale - 0.5) < 0.01 ? DlssMode.PERFORMANCE
                : Math.abs(actualScale - TitaniumConfig.DEFAULT_SCALE) < 0.01 ? DlssMode.QUALITY
                : DlssMode.CUSTOM;
        String detail = failed ? status : probeRequested ? "" : productState.detail();
        DlssDebugTelemetry telemetry = debugTelemetry(probeRequested, productState, runtime);
        DlssModel activeModel = activeBackend != UpscaleBackend.DLSS ? DlssModel.UNAVAILABLE
                : probeRequested ? DlssModel.SDK_DEFAULT : productGpuSession.state().preset().debugModel();
        return new DlssDebugState(config.enabled(), requested, activeBackend, config.dlssQuality(), mode,
                actualScale, actualRenderWidth, actualRenderHeight, safeOutputWidth, safeOutputHeight,
                config.dlssPreset().debugModel(), activeModel, runtime,
                dlssDisplayFrames, dlssDisplayFallbacks, detail, telemetry);
    }

    /**
     * 製品または明示診断の状態から、入力形式、ジッター、評価と時間情報を組み立てる。
     *
     * @param probeRequested 旧診断プローブが明示的に要求されている場合はtrue
     * @param productState 取得済みの製品CPUセッション状態
     * @param runtime 取得済みのDLSS実行状態
     * @return 取得済み製品または明示診断の入力形式・評価・時間情報
     */
    private DlssDebugTelemetry debugTelemetry(boolean probeRequested, DlssProductSession.State productState,
                                               DlssRuntimeState runtime) {
        boolean active = runtime == DlssRuntimeState.ACTIVE;
        if (!probeRequested && !active && runtime != DlssRuntimeState.INITIALIZING
                && runtime != DlssRuntimeState.ERROR) {
            return DlssDebugTelemetry.unavailable();
        }
        DlssGpuSession.State gpuState = productGpuSession.state();
        boolean initialized = probeRequested
                ? frameInputProbe != null && frameInputProbe.dlssInitialized()
                : gpuState.initialized();
        boolean featureReady = probeRequested
                ? frameInputProbe != null && frameInputProbe.dlssFeatureReady()
                : gpuState.featureReady();
        long generation = probeRequested && frameInputProbe != null
                ? frameInputProbe.dlssFeatureGeneration() : gpuState.generation();
        String evaluate = probeRequested && frameInputProbe != null
                ? frameInputProbe.dlssEvaluateResult() : gpuState.evaluateResult();
        FrameJitter jitter = probeRequested && frameInputProbe != null
                ? frameInputProbe.latestJitter() : frameJitter;
        DlssJitterFeed jitterFeed = probeRequested ? DlssJitterFeed.configured() : gpuState.jitterFeed();
        boolean reset = probeRequested ? frameInputProbe != null && frameInputProbe.latestReset()
                : gpuState.historyPolicy().resetEveryFrame()
                        || productFrameInput != null && productFrameInput.latestReset();
        String health = active ? "OK" : "N/A";
        String history = active && !probeRequested && gpuState.historyPolicy().resetEveryFrame()
                ? "Reset Each Frame"
                : active
                ? (probeRequested && frameInputProbe != null && frameInputProbe.historyValid())
                        || (!probeRequested && productFrameInput != null && productFrameInput.historyValid())
                        ? "Valid" : "Reset"
                : "N/A";
        double frameTime = probeRequested && frameInputProbe != null
                ? frameInputProbe.dlssFrameTimeMilliseconds() : gpuState.lastFrameTimeMilliseconds();
        DlssQualityTelemetry quality = qualityTelemetry(probeRequested, gpuState, jitter, jitterFeed);
        String motionCoverage = !active ? "N/A"
                : !probeRequested && gpuState.inputProfile() != null
                ? gpuState.inputProfile().motionDescription() : "Camera only";
        return new DlssDebugTelemetry(initialized, featureReady, generation, evaluate,
                featureReady ? "RGBA8" : "N/A", featureReady ? "R32F" : "N/A",
                featureReady ? "RG32F" : "N/A", active ? "Auto" : "N/A",
                jitter.enabled() ? jitterFeed.apply(jitter.pixelX()) : Double.NaN,
                jitter.enabled() ? jitterFeed.apply(jitter.pixelY()) : Double.NaN,
                jitter.enabled() ? jitter.phase() : 0,
                jitter.enabled() ? jitter.phaseCount() : 0,
                reset,
                active ? DlssTemporalParameters.MOTION_VECTOR_SCALE_X : Double.NaN,
                active ? DlssTemporalParameters.MOTION_VECTOR_SCALE_Y : Double.NaN,
                motionCoverage, health, history, frameTime, Double.NaN,
                active && !probeRequested ? gpuState.appliedSharpness() : Double.NaN, quality,
                active && !probeRequested ? gpuState.inputProfile() : null);
    }

    /**
     * 現在の履歴と取得済みフレーム間隔から品質入力契約の診断値を組み立てる。
     *
     * @param probeRequested 旧診断プローブが明示的に要求されている場合はtrue
     * @param gpuState 製品GPUセッションの取得済み状態
     * @param jitter 実投影と対応する入力ピクセル単位のサンプル
     * @param jitterFeed 入力サンプルをNGXの変位へ換算する符号規約
     * @return 入力履歴・送信サンプル・MV規約とCPU描画間隔の診断値
     */
    private DlssQualityTelemetry qualityTelemetry(boolean probeRequested, DlssGpuSession.State gpuState,
                                                  FrameJitter jitter, DlssJitterFeed jitterFeed) {
        if (!qualityDebugEnabled || probeRequested || productFrameInput == null) {
            return DlssQualityTelemetry.unavailable();
        }
        FramePacingTracker.Snapshot pacing = framePacing.snapshot();
        double projectionJitterX = jitter.enabled() ? -jitter.pixelX() : Double.NaN;
        double projectionJitterY = jitter.enabled() ? -jitter.pixelY() : Double.NaN;
        double ngxJitterX = jitter.enabled() ? jitterFeed.apply(jitter.pixelX()) : Double.NaN;
        double ngxJitterY = jitter.enabled() ? jitterFeed.apply(jitter.pixelY()) : Double.NaN;
        return new DlssQualityTelemetry(true, pacing.screenOpen(),
                productFrameInput.latestSequence(), productFrameInput.previousSequence(),
                gpuState.lastEvaluateSequence(), productFrameInput.historyGeneration(),
                productFrameInput.framesSinceReset(), productFrameInput.lastResetReason(),
                projectionJitterX, projectionJitterY, ngxJitterX, ngxJitterY,
                "previous-current; top-left; render pixels", false,
                pacing.samples(), pacing.latestMilliseconds(), pacing.averageMilliseconds(),
                pacing.minimumMilliseconds(), pacing.maximumMilliseconds(), pacing.deviationMilliseconds());
    }

    public boolean nativeProbePassed() {
        return nativeProbe != null && nativeProbe.passed();
    }

    /**
     * 入力要求・ジッターが揃ったワールド描画中で、Enhancedが介入可能なフレームか返す。
     *
     * @return 入力要求・ジッターが揃ったワールド描画中で、Enhancedが介入可能なフレームである場合はtrue
     */
    public boolean enhancedFrameActive() {
        return active && renderStage == RenderStage.LOW_RES_WORLD && productFrameInput != null
                && productFrameRequest != null && frameJitter.enabled();
    }
    public FrameJitter enhancedJitter() { return frameJitter; }
    public int enhancedWorldWidth() { return worldWidth; }
    public int enhancedWorldHeight() { return worldHeight; }

    /**
     * 認証済みのTAA直前で等倍DLAAを一度だけ評価し、通常またはCUTOUT合成の後続処理へ渡す。
     *
     * Solas系の認証済みメインTAA直前とDLAAの等倍領域に限定する。CUTOUT合成時は元TAA入力を保持する。
     */
    public void tryEnhancedPreAaDlaa(){
        if(!enhancedFrameActive()||preAaDlaaAttempted||productFrameRequest.featureMode()!=DlssFeatureMode.DLAA
                ||Boolean.getBoolean("titanium.debugImageMotion"))return;
        int program=org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM);
        if(!celeste.titanium.client.render.enhanced.EnhancedShaderRuntime.preAaDlaaProgram(program))return;
        // 認証されたTAAパスの直前だけで早期DLAAを実行し、同一フレームでの重複評価を防ぐ。
        preAaDlaaAttempted=true;
        try(var scope=new IrisDlssGlScope()){
            // 認証済みパスが実際に参照しているサンプラーから、TAA前の色テクスチャを取得する。
            int location=org.lwjgl.opengl.GL20C.glGetUniformLocation(program,"colortex1");
            if(location<0)throw new IllegalStateException("Certified pre-AA colour binding missing");
            int unit=org.lwjgl.opengl.GL20C.glGetUniformi(program,location);
            GlStateManager._activeTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0+unit);
            int source=org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D);
            if(source<=0||source==((GlTexture)frameWorldTarget.getColorTexture()).glId())throw new IllegalStateException("Pre-AA colour aliases final destination");
            var completion=productFrameInput.complete(frameWorldTarget,frameRenderer.mainCamera().getFov());
            if(completion==null||!completion.frame().valid()||!completion.motionReady())throw new IllegalStateException("Pre-AA frame inputs incomplete");
            if(completion.motionFailure()!=null)throw completion.motionFailure();
            var packet=completion.frame().current();
            productGpuSession.prepare(productFrameRequest,frameWorldTarget);
            // CUTOUTの深度が取得できたフレームでは、パックTAAを残す合成経路を選ぶ。
            cutoutTaaFrame=celeste.titanium.client.render.enhanced.EnhancedCutoutTaa.hasCoverage();
            DlssFrameObserver deferred=diagnosticFrameObserver==null?null:new DlssFrameObserver(){
                @Override public void beforeTransfer(RenderTarget input,int motion,FrameInputPacket p){diagnosticFrameObserver.beforeTransfer(input,motion,p);}
                @Override public void beforeColourTransfer(RenderTarget input,int colour,int motion,FrameInputPacket p){diagnosticFrameObserver.beforeColourTransfer(input,colour,motion,p);}
                @Override public void afterTransfer(FrameInputPacket p){diagnosticFrameObserver.afterTransfer(p);}
                @Override public void afterEvaluate(FrameInputPacket p,double ms){diagnosticFrameObserver.afterEvaluate(p,ms);}
                @Override public void afterReturn(RenderTarget output,FrameInputPacket p){} // パックの後続描画が完了してから通知する。
            };
            productGpuSession.evaluateEnhancedPreAa(frameWorldTarget,source,productFrameInput.motionProducer().motionGlId(packet.sequence()),packet,completion.captureNanos(),TitaniumClient.config().dlssSharpness(),deferred);
            if(!productGpuSession.state().activationReady())throw new IllegalStateException("Pre-AA output incomplete");
            // 通常の早期DLAAでは結果をパックの入力へ戻す。CUTOUT合成時は元入力を保持してTAAを実行する。
            if(!cutoutTaaFrame){
            if(preAaReadFbo==0){preAaReadFbo=GlStateManager.glGenFramebuffers();preAaDrawFbo=GlStateManager.glGenFramebuffers();}
            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER,preAaReadFbo);
            GlStateManager._glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER,org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0,org.lwjgl.opengl.GL11C.GL_TEXTURE_2D,((GlTexture)frameWorldTarget.getColorTexture()).glId(),0);
            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER,preAaDrawFbo);
            GlStateManager._glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER,org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0,org.lwjgl.opengl.GL11C.GL_TEXTURE_2D,source,0);
            if(org.lwjgl.opengl.GL30C.glCheckFramebufferStatus(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER)!=org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE
                    ||org.lwjgl.opengl.GL30C.glCheckFramebufferStatus(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER)!=org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Pre-AA copy framebuffer incomplete");
            GlStateManager._disableScissorTest();
            GlStateManager._glBlitFrameBuffer(0,0,worldWidth,worldHeight,0,0,worldWidth,worldHeight,org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT,org.lwjgl.opengl.GL11C.GL_NEAREST);
            // 後続パスがミップを参照しても古い色を読まないよう、書き戻した入力のミップを更新する。
            org.lwjgl.opengl.GL45C.glGenerateTextureMipmap(source);
            }
            preAaDlaaPacket=packet;preAaDlaaFrames++;
        }catch(RuntimeException error){preAaDlaaFailure=error;}
    }
    public long preAaDlaaFrames(){return preAaDlaaFrames;}
    public int preAaDlaaResources(){return (preAaReadFbo==0?0:1)+(preAaDrawFbo==0?0:1);}
    public boolean enhancedCutoutTaaFrame(){return cutoutTaaFrame;}
    // 元のメインTAA描画後に呼び出され、借用した一時領域でTAA色とDLAA色を合成する。
    /**
     * 認証済みメインTAA描画の直後に、同一フレームのDLAA出力とCUTOUT用TAA色を合成する。
     */
    public void returnEnhancedCutoutTaa(){
        if(!cutoutTaaFrame||cutoutTaaMerged||preAaDlaaPacket==null||preAaDlaaFailure!=null)return;
        int program=org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM);
        if(!celeste.titanium.client.render.enhanced.EnhancedShaderRuntime.preAaDlaaProgram(program))return;
        try(var scope=new IrisDlssGlScope()){
            celeste.titanium.client.render.enhanced.EnhancedCutoutTaa.merge(((GlTexture)frameWorldTarget.getColorTexture()).glId(),
                    ((GlTexture)frameNativeTarget.getColorTexture()).glId(),((GlTexture)frameWorldTarget.getDepthTexture()).glId(),worldWidth,worldHeight,frameJitter);
            cutoutTaaMerged=true;
        }catch(RuntimeException error){preAaDlaaFailure=error;}
    }

    /**
     * 手の描画用クリア前に診断深度を取得し、必要なDLSS出力を返してネイティブ描画状態へ移る。
     *
     * @param target 操作対象の描画先またはリソース
     * @param renderer 対象のワールドまたはIris renderer
     * @return 出力をネイティブ解像度へ返し、手用の投影も設定し直す必要がある場合はtrue
     */
    public boolean captureDepthBeforeHand(RenderTarget target, GameRenderer renderer) {
        boolean screenOpen = Minecraft.getInstance().gui.screen() != null;
        if (active && depthProbe != null && !screenOpen) {
            depthProbe.captureBeforeClear(target);
        }
        if (active && frameInputProbe != null && !screenOpen) {
            if (displayMode.visible()) return finishWorldForDisplay(renderer);
            frameInputProbe.complete(target, renderer.mainCamera().getFov(), outputWidth, outputHeight);
        }
        // GUIはこの境界より後でnative解像度へ描画される。screen表示中もworldのDLSS、jitter、
        // historyを継続し、Bilinear切替とscreen close時のfeature再構成を発生させない。
        boolean productAlreadyActive = productSession.state().runtimeState() == DlssRuntimeState.ACTIVE;
        if (productWorldEvaluationRequired(active, productFrameInput != null,
                screenOpen, productAlreadyActive)) {
            return finishProductWorld(renderer);
        }
        return false;
    }

    /**
     * 有効なサンプルをワールド投影へ適用し、未ジッター投影と対にして入力生成器へ記録する。
     *
     * @param unjitteredProjection ジッターを適用していない入力投影
     * @return 現在のサンプルを適用した投影。非対象時は元の投影
     */
    public org.joml.Matrix4f prepareFrameProjection(org.joml.Matrix4f unjitteredProjection) {
        boolean jitterEnabled = jitterProbe || currentProductRequest() != null;
        org.joml.Matrix4f renderProjection = active && jitterEnabled
                ? ProjectionJitter.apply(unjitteredProjection, frameJitter, worldWidth, worldHeight)
                : unjitteredProjection;
        if (active && frameInputProbe != null) {
            frameInputProbe.captureProjection(unjitteredProjection, renderProjection,
                    jitterEnabled ? frameJitter : FrameJitter.none());
        }
        if (active && productFrameInput != null) {
            productFrameInput.captureProjection(unjitteredProjection, renderProjection,
                    jitterEnabled ? frameJitter : FrameJitter.none());
        }
        return renderProjection;
    }

    /**
     * 出力・入力寸法、ディメンションと履歴状態を照合し、必要なら周期を作り直して次のサンプルを選ぶ。
     *
     * @param output 再構成結果を返すターゲット
     * @param render 現在の入力解像度ターゲット
     */
    private void beginJitterFrame(RenderTarget output, RenderTarget render) {
        Minecraft client = Minecraft.getInstance();
        boolean productAlreadyActive = productSession.state().runtimeState() == DlssRuntimeState.ACTIVE;
        boolean screenOpen = client.gui.screen() != null;
        if (!jitterRequired(jitterProbe, currentProductRequest() != null,
                screenOpen, productAlreadyActive)) {
            // The validation probe has no consumer while a screen suppresses frame capture. Restart both
            // sides together instead of advancing jitter phases that never enter FrameInputHistory.
            if (jitterProbe && screenOpen && frameJitter.enabled() && frameInputProbe != null) {
                frameInputProbe.breakHistory();
                jitterHistoryBroken = true;
            }
            frameJitter = FrameJitter.none();
            return;
        }
        String dimension = client.level == null ? "" : client.level.dimension().identifier().toString();
        int phaseCount = JitterSequence.phaseCount(output.width, output.height, render.width, render.height,
                minimumJitterPhases);
        boolean reset = jitterHistoryBroken || jitterSequence == null
                || output.width != jitterOutputWidth || output.height != jitterOutputHeight
                || render.width != jitterRenderWidth || render.height != jitterRenderHeight
                || !dimension.equals(jitterDimension);
        if (jitterSequence == null || phaseCount != JitterSequence.phaseCount(
                jitterOutputWidth, jitterOutputHeight, jitterRenderWidth, jitterRenderHeight, minimumJitterPhases)) {
            jitterSequence = new JitterSequence(phaseCount);
        } else if (reset) {
            jitterSequence.reset();
        }
        frameJitter = jitterSequence.next();
        jitterHistoryBroken = false;
        jitterOutputWidth = output.width;
        jitterOutputHeight = output.height;
        jitterRenderWidth = render.width;
        jitterRenderHeight = render.height;
        jitterDimension = dimension;
    }

    /**
     * ワールドと入力の有効性、画面表示と既存の実行状態から製品DLSSを評価すべきか判定する。
     *
     * @param pipelineActive ワールドの解像度制御区間内である場合はtrue
     * @param productInputAvailable 製品の入力生成器が用意されている場合はtrue
     * @param screenOpen GUI画面が開かれている場合はtrue
     * @param productAlreadyActive 製品DLSSの初回出力返却が成功済みの場合はtrue
     * @return ワールドと入力の有効性、画面表示と既存の実行状態から製品DLSSを評価すべき場合はtrue
     */
    static boolean productWorldEvaluationRequired(boolean pipelineActive,
                                                  boolean productInputAvailable,
                                                  boolean screenOpen,
                                                  boolean productAlreadyActive) {
        return pipelineActive && productInputAvailable && (!screenOpen || productAlreadyActive);
    }

    /**
     * 診断指定または製品要求と画面状態から、今フレームのジッター適用可否を判定する。
     *
     * @param jitterProbeEnabled ジッター診断が要求されている場合はtrue
     * @param productRequestAvailable 有効または保留中の製品要求が存在する場合はtrue
     * @param screenOpen GUI画面が開かれている場合はtrue
     * @param productAlreadyActive 製品DLSSの初回出力返却が成功済みの場合はtrue
     * @return 有効な製品要求または診断指定と画面状態の組が、ジッターの適用条件を満たす場合はtrue
     */
    static boolean jitterRequired(boolean jitterProbeEnabled,
                                  boolean productRequestAvailable,
                                  boolean screenOpen,
                                  boolean productAlreadyActive) {
        return (jitterProbeEnabled && !screenOpen)
                || (productRequestAvailable && (!screenOpen || productAlreadyActive));
    }

    public void breakFrameHistory() {
        breakFrameHistory("EXTERNAL");
    }

    /**
     * CPU入力、GPU共有、製品状態とジッターを同じ理由でリセットし、次の有効フレームから再開する。
     *
     * @param reason 退避・履歴中断・解析終了の理由
     */
    private void breakFrameHistory(String reason) {
        if (frameInputProbe != null) frameInputProbe.breakHistory();
        if (productFrameInput != null) productFrameInput.breakHistory(reason);
        productGpuSession.breakHistory();
        productSession.breakHistory();
        jitterHistoryBroken = true;
        frameJitter = FrameJitter.none();
    }

    /**
     * 所有するIris補正サンプラーと能力キャッシュを破棄し、失敗状態と時間方向履歴を再試行可能にする。
     *
     * 描画スレッドから呼び出す。
     */
    public void reloadProductSession() {
        RenderSystem.assertOnRenderThread();
        irisResolutionSampler.close();
        productCapability = null;
        productSession.retry();
        breakFrameHistory("RELOAD");
    }

    /**
     * 独立したキャプチャ・負荷診断の実行条件を確認し、診断コールバックを接続または解除する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param observer 明示診断に接続された通知先。未使用の場合はnull
     */
    public void setDiagnosticFrameObserver(DlssFrameObserver observer) {
        RenderSystem.assertOnRenderThread();
        boolean testMod = FabricLoader.getInstance().isModLoaded("titanium-test");
        boolean timing = DiagnosticTimingPolicy.allows(Boolean.getBoolean("titanium.performanceBenchmark"),
                Boolean.getBoolean("titanium.performanceNormalClient"),
                Boolean.getBoolean("titanium.performanceCostBreakdown"), testMod);
        if (observer != null && !(testMod && Boolean.getBoolean("titanium.stageCapture") || timing)) {
            throw new IllegalStateException("Diagnostic observer requires an isolated capture or performance cost run");
        }
        diagnosticFrameObserver = observer;
    }

    public DlssProductSession.State productSessionState() {
        return productSession.state();
    }

    public String productGpuSessionDiagnostics() {
        return productGpuSession.diagnostics();
    }

    /**
     * 設定、実画面寸法と依存条件から製品要求を作り、プリセット変更と再試行状態を同期する。
     *
     * @param config このフレームで使用する保存済み設定
     * @param nativeTarget 出力解像度の元の描画先
     */
    private void synchronizeProductSession(TitaniumConfig config, RenderTarget nativeTarget) {
        // 入力のサンプリングが変わる設定変更では、旧設定の色が履歴へ混ざらないよう再開する。
        if (configuredTerrainDetail != config.dlssTerrainDetail()) {
            configuredTerrainDetail = config.dlssTerrainDetail();
            breakFrameHistory("TERRAIN_DETAIL_CHANGED");
        }
        DlssProductSession.Request request = null;
        if (config.enabled() && config.backend() == UpscaleBackend.DLSS
                && nativeTarget.width > 0 && nativeTarget.height > 0) {
            DlssFeatureMode featureMode = DlssFeatureMode.fromQuality(config.dlssQuality());
            double scale = featureMode.renderScale();
            RenderResolution render = RenderResolution.scaled(nativeTarget.width, nativeTarget.height, scale);
            boolean iris = FabricLoader.getInstance().isModLoaded("iris");
            // 出力寸法と品質倍率から入力寸法を固定し、VanillaとIrisで異なる深度の向きも記録する。
            request = new DlssProductSession.Request(config.dlssQuality(), featureMode,
                    render.width(), render.height(),
                    nativeTarget.width, nativeTarget.height, !iris,
                    iris ? DlssProductSession.DisplayPath.IRIS_SOLAS : DlssProductSession.DisplayPath.VANILLA);
        }
        if (!config.enabled() || config.backend() == UpscaleBackend.BILINEAR || displayMode.visible()) {
            productCapability = null;
            productSession.synchronize(config.enabled(), config.backend(), request,
                    DlssProductSession.Capability.unavailable("Product DLSS session is not requested"));
            return;
        }
        DlssPreset nextPreset = DlssPreset.configured();
        if (nextPreset != productConfiguredPreset) {
            // プリセット変更時は失敗状態も解除し、Defaultへの復帰を再読み込みなしで可能にする。
            productSession.retry();
            breakFrameHistory("PRESET_CHANGED");
            productConfiguredPreset = nextPreset;
        }
        // ランタイムとIrisの対応条件は要求時に確認し、結果を再読み込みまで再利用する。
        if (productCapability == null) {
            if (request != null && request.displayPath() == DlssProductSession.DisplayPath.IRIS_SOLAS) {
                try {
                    IrisEnvironment.requireInstalled(FabricLoader.getInstance(), "Product Iris");
                    IrisEnvironment.requireActive("Product Iris");
                } catch (RuntimeException error) {
                    productSession.synchronize(true, config.backend(), request,
                            DlssProductSession.Capability.unavailable(error.getMessage()));
                    return;
                }
            }
            DlssNativeCapability nativeCapability = NativeBridge.productDlssCapability(
                    FabricLoader.getInstance().getGameDir());
            productCapability = new DlssProductSession.Capability(
                    nativeCapability.ready(), nativeCapability.reason());
            TitaniumClient.LOGGER.info("[Titanium/{}] product DLSS capability: ready={}, reason={}",
                    productPhase(request), productCapability.ready(), productCapability.reason());
        }
        productSession.synchronize(true, config.backend(), request, productCapability);
    }

    /**
     * 現在の有効要求を優先し、未有効の場合は初期化待ちの要求を返す。
     *
     * @return 有効要求、または保留中の要求。DLSS未要求時はnull
     */
    private DlssProductSession.Request currentProductRequest() {
        DlssProductSession.State state = productSession.state();
        return state.activeRequest() != null ? state.activeRequest() : state.pendingRequest();
    }

    private static String productPhase(DlssProductSession.Request request) {
        return request != null && request.displayPath() == DlssProductSession.DisplayPath.IRIS_SOLAS
                ? "P11C" : "P11B";
    }

    // 寸法・品質・描画経路の変更時は、旧Featureを解放してから対応する深度・MV生成器を作る。
    /**
     * 要求の変更を検出し、旧GPU世代を解放して対応する深度とカメラMV生成器を用意する。
     */
    private void reconcileProductResources() {
        DlssProductSession.Request request = currentProductRequest();
        if (request != null && request.equals(productFrameRequest) && productFrameInput != null) return;
        if (productFrameInput != null || productGpuSession.state().request() != null
                || productGpuSession.state().featureReady()) {
            try {
                productGpuSession.release();
                closeProductFrameInput();
            } catch (RuntimeException error) {
                disable(error);
                return;
            }
        }
        if (request == null) return;
        DepthStatistics.Endpoint endpoint = request.depthInverted()
                ? DepthStatistics.Endpoint.ZERO : DepthStatistics.Endpoint.ONE;
        CameraMotionVectorProducer producer = new CameraMotionVectorProducer(endpoint);
        productFrameInput = new FrameInputRuntime(producer,
                DlssInputProfile.forPath(request.displayPath(), producer.motionSource()));
        productFrameRequest = request;
    }

    private void closeProductFrameInput() {
        if (productFrameInput != null) productFrameInput.close();
        productFrameInput = null;
        productFrameRequest = null;
    }

    /**
     * Iris開始を観測し、パイプライン交代時に取得済み投影を保持して旧履歴を無効化する。
     *
     * @param pipeline 開始・完了・破棄を観測するIrisパイプライン
     */
    public void onIrisWorldBegin(Object pipeline) {
        if (renderStage != RenderStage.LOW_RES_WORLD || productFrameInput == null
                || productFrameRequest.displayPath() != DlssProductSession.DisplayPath.IRIS_SOLAS) return;
        // Irisのパイプラインが置き換わった場合、取得済みの今フレーム投影を残して過去履歴を破棄する。
        if (irisFrameBoundary.begin(pipeline)) {
            irisResolutionSampler.close();
            productFrameInput.invalidatePreviousFrame("IRIS_PIPELINE_CHANGED");
            productGpuSession.breakHistory();
            productSession.breakHistory();
        }
    }

    public void onIrisWorldComplete(Object pipeline) {
        if (renderStage == RenderStage.LOW_RES_WORLD) irisFrameBoundary.complete(pipeline);
    }

    public void onIrisPipelineDestroyed(Object pipeline) {
        // 再読み込みは次の投影取得より前に行われるため、この位置でジッターを再開する。
        if (irisFrameBoundary.destroyed(pipeline)) {
            jitterHistoryBroken = true;
            irisResolutionSampler.close();
        }
    }

    /**
     * 状態表示へ追加する製品DLSSの要求・実行状態と退避理由を生成する。
     *
     * @return コマンドの状態表示へ追加する製品セッションの説明
     */
    private String productSessionSuffix() {
        if (displayMode.visible()) return "";
        DlssProductSession.State productState = productSession.state();
        if (productState.backend().requested() != UpscaleBackend.DLSS) return "";
        String reason = productState.detail().isBlank() ? "" : "; reason=" + productState.detail();
        return "; requested=DLSS, active=" + productState.backend().active() + reason;
    }

    /**
     * 実ワールドのモデルビューとカメラを、現在の投影に対応する入力生成器へ記録する。
     *
     * @param modelView 同じワールド描画で使用するモデルビュー行列
     * @param camera このワールド描画へ実際に渡すカメラ状態
     * @param target 操作対象の描画先またはリソース
     */
    public void captureFrameWorldInputs(org.joml.Matrix4fc modelView,
                                        net.minecraft.client.renderer.state.level.CameraRenderState camera,
                                        RenderTarget target) {
        if (!active || Minecraft.getInstance().level == null) return;
        String dimension = Minecraft.getInstance().level.dimension().identifier().toString();
        if (frameInputProbe != null) frameInputProbe.captureWorldInputs(modelView, camera, target, dimension);
        if (productFrameInput != null) productFrameInput.captureWorldInputs(modelView, camera, target, dimension);
    }

    public String frameInputProbeDiagnostics() {
        return frameInputProbe == null ? "disabled" : frameInputProbe.diagnostics();
    }

    public boolean frameInputProbeFinished() {
        return frameInputProbe != null && frameInputProbe.gpuFinished();
    }

    public boolean frameInputProbePassed() {
        return frameInputProbe != null && frameInputProbe.passed();
    }

    public boolean frameInputProbeHistoryPassed() {
        return frameInputProbe != null && frameInputProbe.historyPassed();
    }

    public boolean jitterProbePassed() {
        return frameInputProbe != null && frameInputProbe.jitterPassed();
    }

    public String depthReconstructionProbeDiagnostics() {
        return frameInputProbe == null ? "disabled" : frameInputProbe.depthReconstructionDiagnostics();
    }

    public boolean depthReconstructionProbeFinished() {
        return frameInputProbe != null && frameInputProbe.depthReconstructionFinished();
    }

    public boolean depthReconstructionProbePassed() {
        return frameInputProbe != null && frameInputProbe.depthReconstructionPassed();
    }

    public String motionVectorProbeDiagnostics() {
        return frameInputProbe == null ? "disabled" : frameInputProbe.motionVectorDiagnostics();
    }

    public boolean motionVectorProbeFinished() {
        return frameInputProbe != null && frameInputProbe.motionVectorFinished();
    }

    public void armMotionVectorCase(String name) {
        if (frameInputProbe == null) throw new IllegalStateException("P6B frame-input probe is disabled");
        frameInputProbe.armMotionVectorCase(name);
    }

    public boolean motionVectorCaseFinished(String name) {
        return frameInputProbe != null && frameInputProbe.motionVectorCaseFinished(name);
    }

    public boolean motionVectorProbePassed() {
        return frameInputProbe != null && frameInputProbe.motionVectorPassed();
    }

    public boolean frameInteropProbePassed() {
        return frameInputProbe != null && frameInputProbe.frameInteropPassed();
    }

    public String frameInteropProbeDiagnostics() {
        return frameInputProbe == null ? "disabled" : frameInputProbe.frameInteropDiagnostics();
    }

    public void captureDepthAfterClear(RenderTarget target) {
        if (active && depthProbe != null) depthProbe.captureAfterClear(target);
    }

    public boolean depthProbeFinished() {
        return depthProbe != null && depthProbe.finished();
    }

    public boolean depthProbePassed() {
        return depthProbe != null && depthProbe.passed();
    }

    public String depthProbeDiagnostics() {
        return depthProbe == null ? "disabled" : depthProbe.diagnostics();
    }

    /**
     * 所有するリソースと保留状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (renderStage != RenderStage.IDLE) {
            throw new IllegalStateException("Cannot release an active world target");
        }
        if(preAaReadFbo!=0){GlStateManager._glDeleteFramebuffers(preAaReadFbo);preAaReadFbo=0;}
        if(preAaDrawFbo!=0){GlStateManager._glDeleteFramebuffers(preAaDrawFbo);preAaDrawFbo=0;}
        terrainDetailSampler.close();
        solidTerrainDetailSampler.close();
        irisResolutionSampler.close();
        targets.close();
        if (nativeProbe != null) nativeProbe.close();
        if (depthProbe != null) depthProbe.close();
        if (frameInputProbe != null) frameInputProbe.close();
        productGpuSession.close();
        closeProductFrameInput();
        irisFrameBoundary.clear();
        productSession.close();
        if (worldGlobals != null) {
            worldGlobals.close();
            worldGlobals = null;
        }
    }
}
