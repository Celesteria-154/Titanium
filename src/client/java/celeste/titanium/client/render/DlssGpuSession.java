package celeste.titanium.client.render;

import celeste.titanium.client.nativebridge.NativeBridge;
import celeste.titanium.client.nativebridge.NativeError;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;

import java.nio.file.Path;
import java.util.Objects;

/** 単一世代のDLSS用GPUリソースを所有し、再生成と解放を管理する。 */
final class DlssGpuSession implements AutoCloseable {
    record State(boolean initialized, boolean featureReady, DlssProductSession.Request request,
                 DlssPreset preset, DlssJitterFeed jitterFeed, DlssHistoryPolicy historyPolicy,
                 long generation, long evaluatedFrames, long returnedFrames,
                 long lastEvaluateSequence,
                 double lastFrameTimeMilliseconds, String evaluateResult,
                 double appliedSharpness, DlssInputProfile inputProfile,
                 boolean fallbackSafe, boolean activationReady, String failure) {}

    private static final double DEFAULT_FRAME_TIME_MILLISECONDS = 16.6667;

    private final Path logDirectory;
    private final DlssJitterFeed jitterFeed;
    private final DlssHistoryPolicy historyPolicy;
    private final FrameInteropInput input = new FrameInteropInput(false);
    private final NgxOutputInterop output = new NgxOutputInterop();
    private DlssProductSession.Request request;
    private DlssPreset preset = DlssPreset.SDK_DEFAULT;
    private boolean initialized;
    private boolean featureReady;
    private boolean outputReady;
    private boolean closed;
    private long generation;
    private long evaluatedFrames;
    private long returnedFrames;
    private long generationReturnedFrames;
    private long lastEvaluateSequence;
    private final DlssFrameClock frameClock = new DlssFrameClock();
    private double lastFrameTimeMilliseconds = Double.NaN;
    private String evaluateResult = "N/A";
    private double appliedSharpness = Double.NaN;
    private DlssInputProfile inputProfile;
    private String failure;

    /**
     * ログ出力先を正規化し、このセッションで用いるジッター規約と履歴ポリシーを確定する。
     *
     * @param logDirectory NGXがログを保存するディレクトリ
     */
    DlssGpuSession(Path logDirectory) {
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory")
                .toAbsolutePath().normalize();
        jitterFeed = DlssJitterFeed.configured();
        historyPolicy = DlssHistoryPolicy.configured();
    }

    /**
     * 要求・入力オブジェクト・プリセットを照合し、必要な場合だけ入力共有・NGX Feature・出力共有を作り直す。
     *
     * 描画スレッドから呼び出す。
     *
     * 同じ要求・プリセット・入力オブジェクトなら再利用する。変更時は旧世代を解放してから再生成する。
     *
     * @param next 次に準備するDLSSの寸法・品質・描画経路を持つ要求
     * @param worldTarget 同一フレームの入力色と深度を持つワールドターゲット
     */
    void prepare(DlssProductSession.Request next, RenderTarget worldTarget) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        Objects.requireNonNull(worldTarget, "worldTarget");
        validateRequest(next);
        DlssPreset nextPreset = DlssPreset.configured();
        if (!(worldTarget.getDepthTexture() instanceof GlTexture sourceDepth)) {
            throw new IllegalStateException("Product DLSS requires an OpenGL depth texture");
        }
        if (worldTarget.width != next.renderWidth() || worldTarget.height != next.renderHeight()) {
            throw new IllegalStateException("Product DLSS render target does not match the request");
        }
        // 同じ寸法・プリセット・入力オブジェクトならFeatureと共有テクスチャをそのまま再利用する。
        if (failure == null && featureReady && outputReady && next.equals(request) && nextPreset == preset
                && input.matches(worldTarget, sourceDepth)) return;

        // 旧世代の共有所有権を返してから新しい入力とFeatureを生成する。
        releaseGeneration();
        failure = null;
        try {
            initializeNgx();
            input.prepare(worldTarget, sourceDepth);
            requireNgx(NativeBridge.createNgxFeature(next.renderWidth(), next.renderHeight(),
                    next.outputWidth(), next.outputHeight(), qualityMode(next), next.depthInverted(), false,
                    nextPreset.nativeValue()),
                    "feature create");
            featureReady = true;
            output.prepare(next.outputWidth(), next.outputHeight());
            outputReady = true;
            request = next;
            preset = nextPreset;
            // 新世代ではフレーム時刻と返却成功数をリセットし、旧Featureの実績を引き継がない。
            generation++;
            generationReturnedFrames = 0;
            frameClock.clear();
            lastFrameTimeMilliseconds = Double.NaN;
            evaluateResult = "N/A";
            appliedSharpness = Double.NaN;
        } catch (RuntimeException error) {
            failure = message(error);
            try {
                releaseGeneration();
            } catch (RuntimeException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    /**
     * ワールドの色・深度・MVを通常経路の入力として、NGX評価と表示先への返却を実行する。
     *
     * @param worldTarget 同一フレームの入力色と深度を持つワールドターゲット
     * @param motionTexture 同じ入力番号のMVを格納するGLテクスチャ識別子
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param profile 色・履歴・MVの取得境界を示す入力プロファイル
     * @param captureNanos 入力を取得したSystem.nanoTime基準の時刻
     * @param destinationTarget 再構成出力を返すネイティブ解像度のターゲット
     * @param sharpness 出力へ適用する空間シャープ化の強度。0.0〜1.0
     * @param observer 明示診断に接続された通知先。未使用の場合はnull
     */
    void evaluate(RenderTarget worldTarget, int motionTexture, FrameInputPacket packet, DlssInputProfile profile,
                  long captureNanos,
                  RenderTarget destinationTarget, double sharpness, DlssFrameObserver observer) {
        evaluateColour(worldTarget,0,motionTexture,packet,profile,captureNanos,destinationTarget,sharpness,observer);
    }

    /**
     * 独立診断の実行条件と明示された中間色の形式を確認し、固定境界のDLSS評価を行う。
     *
     * @param geometry 同一フレームの深度と寸法を持つワールドターゲット
     * @param sourceColour 中間境界で明示された同一フレームのカラーGL識別子
     * @param motion 同一フレームのMVテクスチャ識別子
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param captureNanos 入力を取得したSystem.nanoTime基準の時刻
     * @param output 再構成結果を返すターゲット
     * @param observer 明示診断に接続された通知先。未使用の場合はnull
     */
    void evaluateDeclaredColour(RenderTarget geometry,int sourceColour,int motion,FrameInputPacket packet,
            long captureNanos,RenderTarget output,DlssFrameObserver observer){
        String route=System.getProperty("titanium.qualityStaticRoute","NONE");
        boolean cooperative=Boolean.getBoolean("titanium.qualityCooperativeCopy")&&"EARLY".equals(System.getProperty("titanium.qualityCooperativeDlaa"));
        if(!Boolean.getBoolean("titanium.qualityNormalClient") || (!cooperative&&(!Boolean.getBoolean("titanium.qualityDeclaredLdr")
                || !(route.equals("LATE") || route.equals("EARLY"))))
                || !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("titanium-test")
                || request==null || request.outputWidth()!=3840 || request.outputHeight()!=2054
                || request.featureMode()!=(!cooperative&&Boolean.getBoolean("titanium.qualityStaticSr")?DlssFeatureMode.QUALITY:DlssFeatureMode.DLAA))
            throw new IllegalStateException("Declared colour requires isolated static DLAA/explicit Quality test");
        if(sourceColour<=0 || !org.lwjgl.opengl.GL11C.glIsTexture(sourceColour)
                || org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(sourceColour,0,org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH)!=geometry.width
                || org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(sourceColour,0,org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT)!=geometry.height
                || org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(sourceColour,0,org.lwjgl.opengl.GL11C.GL_TEXTURE_INTERNAL_FORMAT)!=org.lwjgl.opengl.GL11C.GL_RGBA8)
            throw new IllegalStateException("Declared source storage mismatch");
        evaluateColour(geometry,sourceColour,motion,packet,cooperative?DlssInputProfile.IRIS_COOPERATIVE_LDR_TEST:DlssInputProfile.IRIS_DECLARED_STATIC_TEST,captureNanos,output,0,observer);
    }

    /**
     * 認証されたTAA前のLDR色を等倍DLAAへ渡し、後続のパック処理に使用する結果を生成する。
     *
     * @param geometry 同一フレームの深度と寸法を持つワールドターゲット
     * @param sourceColour 中間境界で明示された同一フレームのカラーGL識別子
     * @param motion 同一フレームのMVテクスチャ識別子
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param captureNanos 入力を取得したSystem.nanoTime基準の時刻
     * @param sharpness 出力へ適用する空間シャープ化の強度。0.0〜1.0
     * @param observer 明示診断に接続された通知先。未使用の場合はnull
     */
    void evaluateEnhancedPreAa(RenderTarget geometry,int sourceColour,int motion,FrameInputPacket packet,
            long captureNanos,double sharpness,DlssFrameObserver observer){
        // 早期DLAAは等倍のLDR入力に限定し、色形式と実テクスチャ寸法を確認する。
        int format=org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(sourceColour,0,org.lwjgl.opengl.GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        if(request==null||request.featureMode()!=DlssFeatureMode.DLAA||sourceColour<=0
                ||(format!=org.lwjgl.opengl.GL11C.GL_RGB8&&format!=org.lwjgl.opengl.GL11C.GL_RGBA8)
                ||org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(sourceColour,0,org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH)!=geometry.width
                ||org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(sourceColour,0,org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT)!=geometry.height)
            throw new IllegalStateException("Enhanced pre-AA DLAA source differs in extent/format");
        evaluateColour(geometry,sourceColour,motion,packet,celeste.titanium.client.TitaniumClient.pipeline().enhancedCutoutTaaFrame()
                ?DlssInputProfile.IRIS_ENHANCED_CUTOUT_TAA_CAMERA:DlssInputProfile.IRIS_ENHANCED_PRE_AA_CAMERA,captureNanos,geometry,sharpness,observer);
    }

    /**
     * 指定された色と同一フレームの深度・MVを転送し、時間情報を付けて評価後に出力を返す。
     *
     * 描画スレッドから呼び出す。
     *
     * @param worldTarget 同一フレームの入力色と深度を持つワールドターゲット
     * @param sourceColour 中間境界で明示された同一フレームのカラーGL識別子
     * @param motionTexture 同じ入力番号のMVを格納するGLテクスチャ識別子
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param profile 色・履歴・MVの取得境界を示す入力プロファイル
     * @param captureNanos 入力を取得したSystem.nanoTime基準の時刻
     * @param destinationTarget 再構成出力を返すネイティブ解像度のターゲット
     * @param sharpness 出力へ適用する空間シャープ化の強度。0.0〜1.0
     * @param observer 明示診断に接続された通知先。未使用の場合はnull
     */
    private void evaluateColour(RenderTarget worldTarget,int sourceColour,int motionTexture,FrameInputPacket packet,DlssInputProfile profile,
            long captureNanos,RenderTarget destinationTarget,double sharpness,DlssFrameObserver observer){
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (failure != null || !featureReady || !outputReady || request == null) {
            throw new IllegalStateException("Product DLSS GPU session is unavailable");
        }
        Objects.requireNonNull(worldTarget, "worldTarget");
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(destinationTarget, "destinationTarget");
        Objects.requireNonNull(profile, "profile").requireMatching(request);
        if (!packet.valid() || packet.width() != request.renderWidth()
                || packet.height() != request.renderHeight()) {
            throw new IllegalStateException("Product DLSS frame packet does not match the request");
        }
        if (worldTarget.width != request.renderWidth() || worldTarget.height != request.renderHeight()
                || !(worldTarget.getColorTexture() instanceof GlTexture color)
                || !(worldTarget.getDepthTexture() instanceof GlTexture sourceDepth)
                || !input.matches(worldTarget, sourceDepth)) {
            throw new IllegalStateException("Product DLSS world target does not match the request");
        }
        if (motionTexture == 0 || destinationTarget.width != request.outputWidth()
                || destinationTarget.height != request.outputHeight()
                || !(destinationTarget.getColorTexture() instanceof GlTexture destination)) {
            throw new IllegalStateException("Product DLSS output target does not match the request");
        }
        boolean evaluated = false;
        try {
            // 通常はワールド色、認証済みの早期経路では明示された中間色を転送する。深度・MVの世代は共通。
            int actualColour=sourceColour==0?color.glId():sourceColour;
            if (observer != null) observer.beforeColourTransfer(worldTarget,actualColour,motionTexture,packet);
            input.transfer(actualColour, motionTexture, packet, 0);
            if (observer != null) observer.afterTransfer(packet);
            // ジッター、リセット要求、入力取得間隔を同じフレームからまとめてNGXへ送る。
            DlssTemporalParameters temporal = DlssTemporalParameters.from(
                    packet, frameTimeMilliseconds(packet, captureNanos), jitterFeed, historyPolicy);
            requireNgx(NativeBridge.evaluateNgxFrame(temporal.sequence(), temporal.reset(),
                    temporal.jitterX(), temporal.jitterY(), temporal.frameTimeMilliseconds(), 0), "evaluate");
            if (observer != null) observer.afterEvaluate(packet, temporal.frameTimeMilliseconds());
            evaluated = true;
            evaluatedFrames++;
            evaluateResult = "SUCCESS";
            // NGX出力をGLへ返した後、選択されていればパックの最終カラー処理を実行する。
            RenderTarget reconstructed=EnhancedFinalPostProcess.destination(destinationTarget);
            output.returnOutput(packet.sequence(), ((GlTexture)reconstructed.getColorTexture()).glId(),
                    destinationTarget.width, destinationTarget.height, sharpness);
            EnhancedFinalPostProcess.finish(reconstructed,destinationTarget);
            appliedSharpness = sharpness;
            returnedFrames++;
            generationReturnedFrames++;
            lastEvaluateSequence = packet.sequence();
            inputProfile = profile;
            // 返却成功のカウンターと入力プロファイルを更新してから、任意の診断処理へ結果を通知する。
            if (observer != null) observer.afterReturn(destinationTarget, packet);
        } catch (RuntimeException error) {
            if (!evaluated) evaluateResult = "ERROR";
            failure = message(error);
            throw error;
        }
    }

    /**
     * 次の入力を時間方向履歴の開始として扱い、旧フレームとの連続参照を中断する。
     */
    void breakHistory() {
        requireOpen();
        input.requestRecreate();
        frameClock.clear();
        lastFrameTimeMilliseconds = Double.NaN;
    }

    /**
     * セッションを再利用できる状態で、現在世代のFeatureと入出力共有を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    void release() {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        releaseGeneration();
        failure = null;
    }

    /**
     * 現在世代、評価・返却実績、入力プロファイル、退避の安全性をまとめた状態を返す。
     *
     * @return Feature世代、評価・返却実績、入力プロファイルと共有出力の安全性
     */
    State state() {
        return new State(initialized, featureReady, request, preset, jitterFeed, historyPolicy,
                generation, evaluatedFrames, returnedFrames,
                 lastEvaluateSequence, lastFrameTimeMilliseconds, evaluateResult, appliedSharpness, inputProfile, output.fallbackSafe(),
                featureReady && outputReady && generationReturnedFrames > 0,
                failure == null ? "" : failure);
    }

    static int qualityMode(DlssProductSession.Request request) {
        validateRequest(request);
        return request.featureMode().nativeValue();
    }

    /**
     * 品質倍率から求めた整数入力寸法と、要求が持つ実寸法が一致することを確認する。
     *
     * @param request 現在の寸法・品質・深度方向・描画経路を持つ要求
     */
    static void validateRequest(DlssProductSession.Request request) {
        Objects.requireNonNull(request, "request");
        double scale = request.featureMode().renderScale();
        RenderResolution expected = RenderResolution.scaled(
                request.outputWidth(), request.outputHeight(), scale);
        if (request.renderWidth() != expected.width() || request.renderHeight() != expected.height()) {
            throw new IllegalArgumentException("DLSS quality dimensions do not match the request: expected="
                    + expected.width() + "x" + expected.height() + ", actual="
                    + request.renderWidth() + "x" + request.renderHeight());
        }
    }

    /**
     * NGX対応ビルドを確認し、このGPUセッションで最初の一度だけNGXを初期化する。
     */
    private void initializeNgx() {
        if (initialized) return;
        if (!NativeBridge.ngxCompiled()) {
            throw new IllegalStateException("Product DLSS requires an NGX-enabled native build");
        }
        requireNgx(NativeBridge.initializeNgx(logDirectory.toString()), "initialize");
        initialized = true;
    }

    private double frameTimeMilliseconds(FrameInputPacket packet, long captureNanos) {
        // 入力取得時刻の差をNGXのフレーム時間へ渡す。履歴開始直後の未確定値だけ既定値で補う。
        double milliseconds = frameClock.elapsedMilliseconds(captureNanos, packet.reset());
        lastFrameTimeMilliseconds = milliseconds;
        return Double.isNaN(milliseconds) ? DEFAULT_FRAME_TIME_MILLISECONDS : milliseconds;
    }

    /**
     * 出力参照、NGX Feature、入力共有の順に現在世代を終了し、入力状態を初期化する。
     *
     * 出力のGL参照、NGX Feature、入力共有の順で返す。解放失敗時は状態を保持して理由を呼び出し元へ渡す。
     */
    private void releaseGeneration() {
        RuntimeException outputFailure = null;
        if (featureReady) {
            try {
                // 共有出力を参照するGL側のFBOを先に切り離し、ネイティブ側の解放へ参照を残さない。
                output.beforeFeatureRelease();
            } catch (RuntimeException error) {
                outputFailure = error;
            }
            NativeError result = NativeBridge.releaseNgxFeature();
            if (result == NativeError.OK) {
                featureReady = false;
                if (outputReady) output.afterFeatureRelease();
                outputReady = false;
            }
            if (outputFailure != null) {
                if (result != NativeError.OK) {
                    outputFailure.addSuppressed(
                            new IllegalStateException("Product DLSS feature release failed: " + result));
                }
                throw outputFailure;
            }
            requireNgx(result, "feature release");
        }
        // Featureを解放できた後に入力のWGL登録を解除し、世代に属する参照と時刻を初期化する。
        if (input.registered()) input.close();
        request = null;
        generationReturnedFrames = 0;
        lastEvaluateSequence = 0;
        frameClock.clear();
        lastFrameTimeMilliseconds = Double.NaN;
        evaluateResult = "N/A";
        appliedSharpness = Double.NaN;
        inputProfile = null;
    }

    /**
     * 終了済みのセッションに対する再使用を検出する。
     */
    private void requireOpen() {
        if (closed) throw new IllegalStateException("Product DLSS GPU session is closed");
    }

    /**
     * NGXの結果コードを確認し、失敗時はネイティブ診断を添えて例外を送出する。
     *
     * @param result ネイティブ処理が返した結果コード
     * @param operation 失敗時の診断に付ける操作名
     */
    private static void requireNgx(NativeError result, String operation) {
        if (result != NativeError.OK) {
            throw new IllegalStateException("Product DLSS " + operation + " failed: " + result + "; "
                    + NativeBridge.ngxDiagnostics());
        }
    }

    private static String message(RuntimeException error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    /**
     * 現在の実行状態、進行カウンター、直近の失敗理由を診断文字列へまとめる。
     *
     * @return 取得済みの実行状態、カウンターと直近の失敗理由を含む診断文字列
     */
    String diagnostics() {
        return "initialized=" + initialized + ";featureReady=" + featureReady
                + ";outputReady=" + outputReady + ";generation=" + generation + ";preset=" + preset
                + ";evaluated=" + evaluatedFrames + ";returned=" + returnedFrames
                + ";generationReturned=" + generationReturnedFrames
                + ";profile=" + inputProfile
                + ";evaluate=" + evaluateResult + ";input={registered=" + input.registered()
                + ",size=" + input.width() + "x" + input.height() + "};output={"
                + output.diagnostics() + "};native={" + NativeBridge.ngxDiagnostics()
                + "};failure=" + (failure == null ? "none" : failure);
    }

    /**
     * 所有するリソースと保留状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        releaseGeneration();
        output.close();
        closed = true;
    }
}
