package celeste.titanium.client.render;

import celeste.titanium.client.TitaniumClient;
import celeste.titanium.client.nativebridge.NativeBridge;
import celeste.titanium.client.nativebridge.NativeError;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;

/** 同一フレームのカラーと深度をD3D11所有の共有入力へ転送し、診断結果を取得する。 */
final class FrameInteropProbe implements AutoCloseable {
    private final FrameInteropInput input = new FrameInteropInput(true);
    private int outputWidth;
    private int outputHeight;
    private long submitted;
    private long ngxSubmitted;
    private long ngxGenerations;
    private long lastCaptureNanos;
    private double lastFrameTimeMilliseconds = Double.NaN;
    private boolean ngxInitialized;
    private boolean ngxFeatureReady;
    private boolean ngxEvaluated;
    private boolean failed;
    private boolean reported;
    private boolean needsStaticValidation = true;
    private boolean needsMotionValidation = true;
    private boolean validationComplete;
    private boolean ngxValidationComplete;
    private final boolean ngxEnabled = Boolean.getBoolean("titanium.ngxFrameProbe");
    private final NgxOutputInteropProbe ngxOutputProbe = Boolean.getBoolean("titanium.ngxOutputProbe")
            ? new NgxOutputInteropProbe() : null;
    private final boolean depthInverted = ngxEnabled && expectedDepthInverted();

    /**
     * 同一フレームの色・深度・MVを共有し、診断または指定表示先へのNGX出力返却を進める。
     *
     * 描画スレッドから呼び出す。
     *
     * @param target 操作対象の描画先またはリソース
     * @param motionProducer 同一フレームのカメラMVを生成する所有者
     * @param previous 参照する直前の確定済み入力パケット
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param nativeWidth 出力解像度の幅。ピクセル単位
     * @param nativeHeight 出力解像度の高さ。ピクセル単位
     * @param displayTarget 診断出力を表示するターゲット。非表示診断ではnull
     * @return NGX評価結果を指定された実表示先へ返せた場合はtrue
     */
    boolean capture(RenderTarget target, CameraMotionVectorProducer motionProducer,
            FrameInputPacket previous, FrameInputPacket packet, int nativeWidth, int nativeHeight,
            RenderTarget displayTarget) {
        RenderSystem.assertOnRenderThread();
        if (failed || !(target.getColorTexture() instanceof GlTexture color)
                || !(target.getDepthTexture() instanceof GlTexture sourceDepth)) return false;
        boolean displayed = false;
        try {
            poll();
            if (failed) return false;
            prepare(target, sourceDepth, nativeWidth, nativeHeight);
            int validationKind = validationKind(previous, packet);
            input.transfer(color.glId(), motionProducer.motionGlId(packet.sequence()), packet, validationKind);
            if (validationKind == 2) needsStaticValidation = false;
            if (validationKind == 3) needsMotionValidation = false;
            if (ngxEnabled) {
                DlssTemporalParameters temporal = DlssTemporalParameters.from(
                        packet, frameTimeMilliseconds(packet));
                requireNgx(NativeBridge.evaluateNgxFrame(temporal.sequence(), temporal.reset(),
                        temporal.jitterX(), temporal.jitterY(), temporal.frameTimeMilliseconds(), validationKind),
                        "evaluate");
                ngxSubmitted++;
                ngxEvaluated = true;
                if (ngxOutputProbe != null) {
                    ngxOutputProbe.returnOutput(packet.sequence(), validationKind, displayTarget);
                    displayed = displayTarget != null;
                }
            }
            submitted++;
            if (!reported) {
                reported = true;
                TitaniumClient.LOGGER.info("[Titanium/P8] color + raw depth frame interop submitted; "
                                + "camera motion active; sequence={}; size={}x{}; {}",
                        packet.sequence(), input.width(), input.height(), NativeBridge.frameInteropDiagnostics());
            }
        } catch (RuntimeException error) {
            failed = true;
            TitaniumClient.LOGGER.error("[Titanium/P8] frame interop disabled; Bilinear remains active", error);
        }
        return displayed;
    }

    /**
     * パケットのリセットと前後カメラの変化から、入力をリセット・静止・移動の診断へ分類する。
     *
     * @param previous 参照する直前の確定済み入力パケット
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @return 0で診断不要、1でリセット、2で静止、3で移動の入力分類
     */
    private int validationKind(FrameInputPacket previous, FrameInputPacket packet) {
        if (validationComplete) return 0;
        if (packet.reset()) {
            needsStaticValidation = true;
            return 1;
        }
        if (previous != null && packet.continuousWith(previous)) {
            boolean cameraChanged = packet.cameraChangedFrom(previous);
            if (needsStaticValidation && !cameraChanged) return 2;
            if (needsMotionValidation && cameraChanged) return 3;
        }
        return 0;
    }

    /**
     * 転送済みの診断結果を完了通知後に取得し、判定と診断状態を更新する。
     */
    void poll() {
        if (!input.registered() || failed) return;
        try {
            require(NativeBridge.pollFrameInteropValidation(), "validation poll");
            validationComplete = NativeBridge.frameInteropValidationPassed();
            if (ngxEnabled && ngxInitialized) {
                requireNgx(NativeBridge.pollNgxValidation(), "output validation poll");
                ngxValidationComplete = NativeBridge.ngxValidationPassed();
                if (ngxOutputProbe != null) ngxOutputProbe.poll();
            }
        } catch (RuntimeException error) {
            failed = true;
            TitaniumClient.LOGGER.error("[Titanium/P8] validation poll disabled frame interop; "
                    + "Bilinear remains active", error);
        }
    }

    /**
     * 入力共有、指定出力寸法のNGX Feature、必要な診断出力を準備する。
     *
     * @param target 操作対象の描画先またはリソース
     * @param sourceDepth ワールド入力と同じフレームのGL深度テクスチャ
     * @param nativeWidth 出力解像度の幅。ピクセル単位
     * @param nativeHeight 出力解像度の高さ。ピクセル単位
     */
    private void prepare(RenderTarget target, GlTexture sourceDepth, int nativeWidth, int nativeHeight) {
        if (input.matches(target, sourceDepth)
                && (!ngxEnabled || outputWidth == nativeWidth && outputHeight == nativeHeight)) return;
        release();
        outputWidth = nativeWidth;
        outputHeight = nativeHeight;
        try {
            input.prepare(target, sourceDepth);
            if (ngxEnabled) {
                initializeNgx();
                requireNgx(NativeBridge.createNgxFeature(input.width(), input.height(), outputWidth, outputHeight,
                        qualityMode(input.width(), input.height(), outputWidth, outputHeight), depthInverted, true),
                        "feature create");
                ngxFeatureReady = true;
                ngxEvaluated = false;
                ngxGenerations++;
                if (ngxOutputProbe != null) ngxOutputProbe.prepare(outputWidth, outputHeight);
            }
        } catch (RuntimeException error) {
            if (ngxFeatureReady) {
                try {
                    releaseNgxFeature();
                } catch (RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            try {
                input.close();
            } catch (RuntimeException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private static void require(NativeError result, String operation) {
        if (result != NativeError.OK) {
            throw new IllegalStateException(operation + " failed: " + result + "; "
                    + NativeBridge.frameInteropDiagnostics());
        }
    }

    /**
     * NGXの結果コードを確認し、失敗時はネイティブ診断を添えて例外を送出する。
     *
     * @param result ネイティブ処理が返した結果コード
     * @param operation 失敗時の診断に付ける操作名
     */
    private static void requireNgx(NativeError result, String operation) {
        if (result != NativeError.OK) {
            throw new IllegalStateException("P9 " + operation + " failed: " + result + "; "
                    + NativeBridge.ngxDiagnostics());
        }
    }

    /**
     * 診断用のネイティブ能力を確認し、NGXの初期化を一度だけ行う。
     */
    private void initializeNgx() {
        if (ngxInitialized) return;
        if (!NativeBridge.ngxCompiled()) {
            throw new IllegalStateException("P9 property requires an NGX-enabled native build");
        }
        String logs = FabricLoader.getInstance().getGameDir().resolve("titanium/ngx-logs")
                .toAbsolutePath().normalize().toString();
        requireNgx(NativeBridge.initializeNgx(logs), "initialize");
        ngxInitialized = true;
    }

    /**
     * 入力と出力の寸法が対応する診断用Quality・Performance倍率か確認し、JNIモード値を返す。
     *
     * @param renderWidth ワールド入力の幅。ピクセル単位
     * @param renderHeight ワールド入力の高さ。ピクセル単位
     * @param nativeWidth 出力解像度の幅。ピクセル単位
     * @param nativeHeight 出力解像度の高さ。ピクセル単位
     * @return 入力寸法に対応するQualityまたはPerformanceの固定JNI値
     */
    private static int qualityMode(int renderWidth, int renderHeight, int nativeWidth, int nativeHeight) {
        if (renderWidth == Math.round(nativeWidth * 0.5) && renderHeight == Math.round(nativeHeight * 0.5)) {
            return 0;
        }
        if (renderWidth == Math.round(nativeWidth * (2.0 / 3.0))
                && renderHeight == Math.round(nativeHeight * (2.0 / 3.0))) return 1;
        throw new IllegalStateException("P9 supports only 0.5 Performance and 2/3 Quality; actual="
                + renderWidth + "x" + renderHeight + "->" + nativeWidth + "x" + nativeHeight);
    }

    /**
     * 診断で指定された深度クリア端点からNGXの反転深度フラグを求める。
     *
     * @return 診断指定がZEROの場合はtrue、ONEの場合はfalse
     */
    private static boolean expectedDepthInverted() {
        return switch (System.getProperty("titanium.motionVector.expectedClear", "")) {
            case "ZERO" -> true;
            case "ONE" -> false;
            default -> throw new IllegalStateException("P9 requires explicit ZERO/ONE depth polarity");
        };
    }

    /**
     * 前回の診断入力からの経過時間をミリ秒で求める。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @return 診断の入力取得間隔。ミリ秒単位
     */
    private double frameTimeMilliseconds(FrameInputPacket packet) {
        long now = System.nanoTime();
        double milliseconds = packet.reset() || lastCaptureNanos == 0
                ? Double.NaN : Math.max(0.001, Math.min(1000.0, (now - lastCaptureNanos) / 1_000_000.0));
        lastCaptureNanos = now;
        lastFrameTimeMilliseconds = milliseconds;
        return Double.isNaN(milliseconds) ? 16.6667 : milliseconds;
    }

    /**
     * 診断世代のNGX Featureと入出力共有を終了し、次の準備へ戻す。
     */
    private void release() {
        if (ngxFeatureReady) releaseNgxFeature();
        NativeError postDrainPoll = NativeError.OK;
        if (input.registered()) {
            if (ngxEnabled) postDrainPoll = NativeBridge.pollFrameInteropValidation();
            input.close();
        }
        outputWidth = 0;
        outputHeight = 0;
        lastCaptureNanos = 0;
        lastFrameTimeMilliseconds = Double.NaN;
        require(postDrainPoll, "post-drain validation poll");
    }

    /**
     * 診断出力側の参照を先に返し、NGX Featureと対応する世代情報を解放する。
     */
    private void releaseNgxFeature() {
        RuntimeException outputFailure = null;
        if (ngxOutputProbe != null) {
            try {
                ngxOutputProbe.beforeFeatureRelease();
            } catch (RuntimeException error) {
                outputFailure = error;
            }
        }
        NativeError result = NativeBridge.releaseNgxFeature();
        if (result == NativeError.OK) {
            ngxFeatureReady = false;
            ngxEvaluated = false;
            if (ngxOutputProbe != null) ngxOutputProbe.afterFeatureRelease();
        }
        if (outputFailure != null) {
            if (result != NativeError.OK) {
                outputFailure.addSuppressed(new IllegalStateException("P9 feature release failed: " + result));
            }
            throw outputFailure;
        }
        requireNgx(result, "feature release");
    }

    boolean passed() {
        return submitted > 0 && !failed && NativeBridge.frameInteropValidationPassed()
                && (!ngxEnabled || ngxSubmitted > 0 && NativeBridge.ngxValidationPassed())
                && (ngxOutputProbe == null || ngxOutputProbe.passed());
    }

    boolean outputFallbackSafe() {
        return ngxOutputProbe == null || ngxOutputProbe.fallbackSafe();
    }

    boolean ngxInitialized() {
        return ngxInitialized;
    }

    boolean ngxFeatureReady() {
        return ngxFeatureReady;
    }

    long ngxGenerations() {
        return ngxGenerations;
    }

    String ngxEvaluateResult() {
        if (failed) return "ERROR";
        return ngxEvaluated ? "SUCCESS" : "N/A";
    }

    double lastFrameTimeMilliseconds() {
        return lastFrameTimeMilliseconds;
    }

    /**
     * 現在の実行状態、進行カウンター、直近の失敗理由を診断文字列へまとめる。
     *
     * @return 取得済みの実行状態、カウンターと直近の失敗理由を含む診断文字列
     */
    String diagnostics() {
        return "submitted=" + submitted + ";registrations=" + input.registrations() + ";failed=" + failed
                + ";size=" + input.width() + "x" + input.height()
                + ";native={" + NativeBridge.frameInteropDiagnostics() + "}"
                + (ngxEnabled ? ";ngx={submitted=" + ngxSubmitted + ",generations=" + ngxGenerations
                + ",validationComplete=" + ngxValidationComplete + ",depthInverted=" + depthInverted
                + ",native=" + NativeBridge.ngxDiagnostics() + "}" : "")
                + (ngxOutputProbe == null ? "" : ";ngxOutput={" + ngxOutputProbe.diagnostics() + "}");
    }

    void breakHistory() {
        input.requestRecreate();
        if (!validationComplete) needsStaticValidation = true;
    }

    void failProducer(RuntimeException error) {
        failed = true;
        TitaniumClient.LOGGER.error("[Titanium/P8] frame interop disabled because camera motion failed; "
                + "Bilinear remains active", error);
    }

    /**
     * 所有するリソースと保留状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        try {
            release();
            if (ngxOutputProbe != null) ngxOutputProbe.close();
        } catch (RuntimeException error) {
            TitaniumClient.LOGGER.error("[Titanium/P8] frame interop release failed", error);
        }
    }
}
