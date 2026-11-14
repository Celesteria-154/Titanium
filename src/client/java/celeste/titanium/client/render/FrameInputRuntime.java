package celeste.titanium.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** フレーム入力とカメラMVを生成する。通常描画では検証用の読み戻しを追加しない。 */
final class FrameInputRuntime implements AutoCloseable {
    record Completion(FrameInputHistory.Result frame, boolean motionReady, RuntimeException motionFailure,
                      DlssInputProfile profile, long captureNanos) {}

    private final FrameInputHistory history = new FrameInputHistory();
    private final CameraMotionVectorProducer motionProducer;
    private final DlssInputProfile profile;
    private final FrameResourceIdentity pendingResources = new FrameResourceIdentity();
    private record Prepared(FrameInputHistory.Result frame,long captureNanos) {}
    private final FramePacketPreparation<Prepared> earlyPacket=new FramePacketPreparation<>();
    private final FramePacketPreparation<Completion> earlyCompletion=new FramePacketPreparation<>();
    private boolean motionProducerFailed;
    private Matrix4f pendingUnjitteredProjection;
    private Matrix4f pendingRenderProjection;
    private FrameJitter pendingJitter = FrameJitter.none();
    private Matrix4f pendingModelView;
    private Vec3 pendingCameraPosition;
    private long pendingCaptureNanos;
    private float pendingFarPlane;
    private int pendingWidth;
    private int pendingHeight;
    private String pendingDimension;
    private String diagnostics = "waiting for world projection";

    /**
     * この入力経路のMV生成器と、指定された場合は製品入力プロファイルを保持する。
     *
     * @param motionProducer 同一フレームのカメラMVを生成する所有者
     */
    FrameInputRuntime(CameraMotionVectorProducer motionProducer) {
        this(motionProducer, null); // Diagnostic probes do not declare a product renderer profile.
    }

    /**
     * この入力経路のMV生成器と、指定された場合は製品入力プロファイルを保持する。
     *
     * @param motionProducer 同一フレームのカメラMVを生成する所有者
     * @param profile 色・履歴・MVの取得境界を示す入力プロファイル
     */
    FrameInputRuntime(CameraMotionVectorProducer motionProducer, DlssInputProfile profile) {
        this.motionProducer = motionProducer;
        this.profile = profile;
    }

    /**
     * 実描画へ渡す投影とサンプルをコピーし、旧フレームの未消費入力を破棄する。
     *
     * @param unjitteredProjection ジッターを適用していない入力投影
     * @param renderProjection ジッター適用後に実描画で使用した投影行列
     * @param jitter 実投影と対応する入力ピクセル単位のサンプル
     */
    void captureProjection(Matrix4fc unjitteredProjection, Matrix4fc renderProjection, FrameJitter jitter) {
        if(earlyPacket.available() || earlyCompletion.available())history.breakHistory("EARLY_PACKET_ABANDONED");
        // 新しい投影の取得を境界に、前フレームから残った準備済み入力と借用参照を破棄する。
        earlyCompletion.clear();
        earlyPacket.clear();
        pendingResources.clear();
        pendingUnjitteredProjection = new Matrix4f(unjitteredProjection);
        pendingRenderProjection = new Matrix4f(renderProjection);
        pendingJitter = jitter;
        pendingCaptureNanos = 0;
        pendingModelView = null;
        pendingCameraPosition = null;
        pendingDimension = null;
        diagnostics = "waiting for level renderer inputs";
    }

    /**
     * 実際のモデルビュー・カメラ・入力寸法と取得時刻を保存し、後段の色深度境界との照合に備える。
     *
     * @param modelView 同じワールド描画で使用するモデルビュー行列
     * @param camera このワールド描画へ実際に渡すカメラ状態
     * @param target 操作対象の描画先またはリソース
     * @param dimension 現在のワールドのディメンション識別子
     */
    void captureWorldInputs(Matrix4fc modelView, CameraRenderState camera, RenderTarget target, String dimension) {
        if (pendingUnjitteredProjection == null) return;
        // 行列が後続描画で変更されても同じフレームを再現できるよう、その場でコピーする。
        pendingModelView = new Matrix4f(modelView);
        pendingCameraPosition = camera.pos;
        pendingCaptureNanos = System.nanoTime();
        pendingFarPlane = camera.depthFar;
        pendingWidth = target.width;
        pendingHeight = target.height;
        pendingDimension = dimension;
        pendingResources.capture(target, target.getColorTexture(), target.getDepthTexture(), target.width, target.height);
        diagnostics = "waiting for matching color/depth boundary";
    }

    /**
     * 色深度の同一性を確認してCPU入力だけを早期確定し、後段へ一度だけ渡せる形で保持する。
     *
     * 描画スレッドから呼び出す。
     *
     * この時点ではMVを生成せず、入力メタデータを確定する。後続の完了処理でMVを一度だけ生成する。
     *
     * @param target 操作対象の描画先またはリソース
     * @param fieldOfView この入力で実際に使用するFOV。度単位
     * @return 入力履歴の照合結果。必要な入力が未取得の場合はnull
     */
    FrameInputHistory.Result preparePacket(RenderTarget target,float fieldOfView) {
        RenderSystem.assertOnRenderThread();
        if(earlyPacket.available() || earlyCompletion.available())throw new IllegalStateException("Packet already prepared before final");
        long captureNanos=pendingCaptureNanos;
        FrameInputHistory.Result frame=acceptPending(target,fieldOfView);
        if(frame==null)return null;
        earlyPacket.offer(new Prepared(frame,captureNanos),target,target.getColorTexture(),target.getDepthTexture(),
                target.width,target.height,fieldOfView);
        return frame;
    }

    /**
     * 取得済みの行列と実ターゲットを照合して履歴へ採用し、保留中の借用参照を消去する。
     *
     * @param target 操作対象の描画先またはリソース
     * @param fieldOfView この入力で実際に使用するFOV。度単位
     * @return 入力履歴の照合結果。行列またはカメラが未取得の場合はnull
     */
    private FrameInputHistory.Result acceptPending(RenderTarget target,float fieldOfView) {
        if (pendingUnjitteredProjection == null || pendingRenderProjection == null
                || pendingModelView == null || pendingCameraPosition == null) return null;
        try {
            // 投影取得時と終了時のターゲット・寸法・色深度形式を照合し、別の描画先との混在を除外する。
            boolean resourceMatches = pendingResources.matches(target, target.getColorTexture(),
                    target.getDepthTexture(), target.width, target.height)
                    && target.getColorTexture() != null
                    && target.getColorTexture().getFormat() == GpuFormat.RGBA8_UNORM
                    && target.getDepthTexture() != null
                    && target.getDepthTexture().getFormat() == GpuFormat.D32_FLOAT;
            return history.accept(
                    pendingUnjitteredProjection, pendingRenderProjection, pendingJitter, pendingModelView,
                    pendingCameraPosition, 0.05F, pendingFarPlane, fieldOfView,
                    pendingWidth, pendingHeight, pendingDimension, resourceMatches);
        } finally {
            clearPending();
        }
    }

    /**
     * 準備済み入力を一度だけ消費するか現在の境界で確定し、同じ番号のMVと完了情報を生成する。
     *
     * 描画スレッドから呼び出す。
     *
     * 未取得の入力がある場合はnullを返す。準備済み入力はターゲットとFOVを照合して一度だけ消費する。
     *
     * @param target 操作対象の描画先またはリソース
     * @param fieldOfView この入力で実際に使用するFOV。度単位
     * @return 同じ入力番号の履歴照合結果とMVの生成状態。入力未取得時はnull
     */
    Completion complete(RenderTarget target, float fieldOfView) {
        RenderSystem.assertOnRenderThread();
        if(earlyCompletion.available())try {
            return earlyCompletion.take(target,target.getColorTexture(),target.getDepthTexture(),target.width,target.height,fieldOfView);
        }catch(RuntimeException error){history.breakHistory("EARLY_COMPLETION_RESOURCE_CHANGED");throw error;}
        return complete(target,target,fieldOfView);
    }

    /**
     * 明示された深度境界で入力とMVを早期確定し、後段での一度の消費まで保持する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param acceptedTarget 行列・色・深度の同一性を確認した入力ターゲット
     * @param motionTarget MV生成に使用する深度を持つ借用ターゲット
     * @param fov この入力で実際に使用するFOV。度単位
     * @return 後段へ一度だけ渡す完了情報。必要な入力が未取得の場合はnull
     */
    Completion prepareComplete(RenderTarget acceptedTarget,RenderTarget motionTarget,float fov){
        RenderSystem.assertOnRenderThread();
        if(earlyCompletion.available())throw new IllegalStateException("Motion completion already prepared");
        if(motionTarget.width!=acceptedTarget.width || motionTarget.height!=acceptedTarget.height
                || motionTarget.getDepthTexture()==null || motionTarget.getDepthTexture().getFormat()!=GpuFormat.D32_FLOAT)
            throw new IllegalStateException("Prepared motion target differs in extent/format");
        Completion result=complete(acceptedTarget,motionTarget,fov);
        if(result!=null)try {
            earlyCompletion.offer(result,acceptedTarget,acceptedTarget.getColorTexture(),acceptedTarget.getDepthTexture(),acceptedTarget.width,acceptedTarget.height,fov);
        }catch(RuntimeException error){
            history.breakHistory("EARLY_COMPLETION_PREPARATION_REJECTED");
            throw new IllegalStateException("Early completion preparation rejected: fov="+fov+";size="+acceptedTarget.width+"x"+acceptedTarget.height
                    +";colour="+(acceptedTarget.getColorTexture()!=null)+";depth="+(acceptedTarget.getDepthTexture()!=null)
                    +";frameValid="+result.frame().valid()+";motionReady="+result.motionReady(),error);
        }
        return result;
    }

    /**
     * 準備済み入力を一度だけ消費するか現在の境界で確定し、同じ番号のMVと完了情報を生成する。
     *
     * 未取得の入力がある場合はnullを返す。準備済み入力はターゲットとFOVを照合して一度だけ消費する。
     *
     * @param target 操作対象の描画先またはリソース
     * @param motionTarget MV生成に使用する深度を持つ借用ターゲット
     * @param fieldOfView この入力で実際に使用するFOV。度単位
     * @return 同じ入力番号の履歴照合結果とMVの生成状態。入力未取得時はnull
     */
    private Completion complete(RenderTarget target,RenderTarget motionTarget,float fieldOfView){
        long captureNanos=pendingCaptureNanos;
        FrameInputHistory.Result frame;
        // 早期に確定した入力があれば一度だけ消費する。未準備の場合だけ、この境界で入力を確定する。
        if(earlyPacket.available()){
            try {
                Prepared prepared=earlyPacket.take(target,target.getColorTexture(),target.getDepthTexture(),
                        target.width,target.height,fieldOfView);
                frame=prepared.frame();captureNanos=prepared.captureNanos();
            }catch(RuntimeException e){history.breakHistory("EARLY_PACKET_RESOURCE_CHANGED");throw e;}
        }else frame=acceptPending(target,fieldOfView);
        if(frame==null)return null;
        FrameInputPacket packet = frame.current();
        if (!frame.valid()) {
            diagnostics = "invalid frame packet;sequence=" + packet.sequence()
                    + ";resourceMatches=" + frame.resourceMatches()
                    + ";packetValid=" + packet.valid() + ";jitterValid=" + frame.jitterValid()
                    + ";invalidPackets=" + history.invalidPackets()
                    + ";invalidJitter=" + history.invalidJitterPackets();
            return new Completion(frame, false, null, profile, captureNanos);
        }
        diagnostics = history.diagnostics(packet);
        if (motionProducer == null || motionProducerFailed) {
            return new Completion(frame, false, null, profile, captureNanos);
        }
        try {
            // 前後フレームの確定行列と、指定された深度から同一シーケンスのMVを生成する。
            motionProducer.generate(motionTarget, frame.previous(), packet);
            return new Completion(frame, true, null, profile, captureNanos);
        } catch (RuntimeException error) {
            // MV生成が失敗した状態を保持し、不完全なMVを後続フレームで有効として再利用させない。
            motionProducerFailed = true;
            return new Completion(frame, false, error, profile, captureNanos);
        }
    }

    void failMotionProducer() {
        motionProducerFailed = true;
    }

    CameraMotionVectorProducer motionProducer() {
        return motionProducer;
    }

    String motionDiagnostics() {
        return motionProducer == null ? "disabled" : motionProducer.diagnostics();
    }

    /**
     * 次の入力を時間方向履歴の開始として扱い、旧フレームとの連続参照を中断する。
     */
    void breakHistory() {
        earlyCompletion.clear();
        earlyPacket.clear();
        history.breakHistory();
        clearPending();
    }

    /**
     * 次の入力を時間方向履歴の開始として扱い、旧フレームとの連続参照を中断する。
     *
     * @param reason 退避・履歴中断・解析終了の理由
     */
    void breakHistory(String reason) {
        earlyCompletion.clear();
        earlyPacket.clear();
        history.breakHistory(reason);
        clearPending();
    }

    /**
     * 今フレームの取得済み投影を保持したまま、過去の時間方向参照を無効化する。
     *
     * Iris開始は投影取得後に呼ばれるため、取得済みの今フレーム投影を残して過去履歴だけを無効化する。
     *
     * @param reason 退避・履歴中断・解析終了の理由
     */
    void invalidatePreviousFrame(String reason) {
        earlyCompletion.clear();
        earlyPacket.clear();
        history.breakHistory(reason);
    }

    long packets() {
        return history.packets();
    }

    long resets() {
        return history.resets();
    }

    long continuousFrames() {
        return history.continuousFrames();
    }

    long cameraMotionFrames() {
        return history.cameraMotionFrames();
    }

    long invalidPackets() {
        return history.invalidPackets();
    }

    boolean passed() {
        return history.passed();
    }

    boolean historyPassed() {
        return history.historyPassed();
    }

    boolean jitterPassed() {
        return history.jitterPassed();
    }

    FrameJitter latestJitter() {
        FrameInputPacket latest = history.latest();
        return latest == null ? FrameJitter.none() : latest.jitter();
    }

    boolean latestReset() {
        FrameInputPacket latest = history.latest();
        return latest != null && latest.reset();
    }

    boolean historyValid() {
        return history.historyValid();
    }

    long latestSequence() {
        return history.latestSequence();
    }

    long previousSequence() {
        return history.previousSequence();
    }

    long historyGeneration() {
        return history.historyGeneration();
    }

    long framesSinceReset() {
        return history.framesSinceReset();
    }

    String lastResetReason() {
        return history.lastResetReason();
    }

    String diagnostics() {
        return diagnostics;
    }

    /**
     * 行列・カメラ・取得時刻と借用リソースの保留情報を消去する。
     */
    private void clearPending() {
        pendingResources.clear();
        pendingUnjitteredProjection = null;
        pendingRenderProjection = null;
        pendingJitter = FrameJitter.none();
        pendingCaptureNanos = 0;
        pendingModelView = null;
        pendingCameraPosition = null;
        pendingDimension = null;
    }

    /**
     * 所有するリソースと保留状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        earlyCompletion.clear();
        earlyPacket.clear();
        if (motionProducer != null) motionProducer.close();
        clearPending();
    }
}
