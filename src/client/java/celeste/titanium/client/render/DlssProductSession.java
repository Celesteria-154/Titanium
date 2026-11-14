package celeste.titanium.client.render;

import celeste.titanium.client.config.BackendSelection;
import celeste.titanium.client.config.DlssQuality;
import celeste.titanium.client.config.UpscaleBackend;
import celeste.titanium.client.debug.DlssRuntimeState;

import java.util.Objects;

/** DLSSセッションの要求・有効状態、再生成要求、失敗時の退避を管理する。GPUリソースはDlssGpuSessionが所有する。 */
public final class DlssProductSession implements AutoCloseable {
    public enum DisplayPath {
        VANILLA,
        IRIS_SOLAS // 旧内部識別名。製品の対応判定はパック名から独立して行う。
    }

    public record Capability(boolean ready, String reason) {
        public Capability {
            reason = reason == null ? "" : reason;
            if (!ready && reason.isBlank()) reason = "DLSS unavailable";
        }

        public static Capability available() {
            return new Capability(true, "");
        }

        public static Capability unavailable(String reason) {
            return new Capability(false, reason);
        }
    }

    public record Request(DlssQuality quality, DlssFeatureMode featureMode,
                          int renderWidth, int renderHeight,
                          int outputWidth, int outputHeight, boolean depthInverted,
                          DisplayPath displayPath) {
        public Request {
            Objects.requireNonNull(quality, "quality");
            Objects.requireNonNull(featureMode, "featureMode");
            Objects.requireNonNull(displayPath, "displayPath");
            if (featureMode != DlssFeatureMode.fromQuality(quality)) {
                throw new IllegalArgumentException("DLSS quality and feature mode do not match");
            }
            if (renderWidth < 1 || renderHeight < 1 || outputWidth < 1 || outputHeight < 1) {
                throw new IllegalArgumentException("DLSS session dimensions must be positive");
            }
        }
    }

    public record State(BackendSelection backend, DlssRuntimeState runtimeState,
                        Request activeRequest, Request pendingRequest,
                        long generation, String detail) {
        public State {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(runtimeState, "runtimeState");
            detail = detail == null ? "" : detail;
            if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        }
    }

    private static final String INITIALIZING = "DLSS product session is initializing";

    private State state = inactive(UpscaleBackend.BILINEAR, "", 0);
    private Request failedRequest;
    private boolean closed;

    /**
     * 有効設定・要求・能力情報を照合し、未使用・初期化待ち・継続・退避の状態を更新する。
     *
     * @param titaniumEnabled ワールド解像度制御が有効な場合はtrue
     * @param requested 利用者が要求したバックエンド
     * @param request 現在の寸法・品質・深度方向・描画経路を持つ要求
     * @param capability DLSSを開始できるかと、その理由を表す能力情報
     * @return 要求・能力・既存実行状態を同期した結果
     */
    public State synchronize(boolean titaniumEnabled, UpscaleBackend requested,
                             Request request, Capability capability) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(capability, "capability");
        if (closed) throw new IllegalStateException("DLSS product session is closed");

        if (!titaniumEnabled) {
            clearRequests();
            state = inactive(requested, "Titanium is disabled", state.generation());
            return state;
        }
        if (requested == UpscaleBackend.BILINEAR) {
            clearRequests();
            state = inactive(requested, "", state.generation());
            return state;
        }
        Objects.requireNonNull(request, "request");
        if (!capability.ready()) {
            clearRequests();
            state = inactive(requested, capability.reason(), state.generation());
            return state;
        }
        // 同一要求の失敗後は毎フレームの再初期化を避け、設定変更または明示的な再試行を待つ。
        if (request.equals(failedRequest)) return state;
        if (request.equals(state.activeRequest())) return state;
        if (request.equals(state.pendingRequest())) return state;

        // 要求を保留状態へ移す。GPU側で出力返却に成功するまではBilinearを有効バックエンドとして報告する。
        failedRequest = null;
        state = new State(new BackendSelection(UpscaleBackend.DLSS, UpscaleBackend.BILINEAR, INITIALIZING),
                DlssRuntimeState.INITIALIZING, null, request, state.generation(), INITIALIZING);
        return state;
    }

    /**
     * 保留中の要求の出力返却が成功したことを記録し、DLSSを有効状態へ移す。
     *
     * @param request 現在の寸法・品質・深度方向・描画経路を持つ要求
     * @return 初回出力を確認して有効化した新世代の状態
     */
    public State markActive(Request request) {
        Objects.requireNonNull(request, "request");
        if (closed || !request.equals(state.pendingRequest())) {
            throw new IllegalStateException("DLSS activation does not match the pending request");
        }
        failedRequest = null;
        // 保留していた要求に対応する初回出力が完了した時点で、DLSSの有効世代を進める。
        state = new State(new BackendSelection(UpscaleBackend.DLSS, UpscaleBackend.DLSS, ""),
                DlssRuntimeState.ACTIVE, request, null, state.generation() + 1, "");
        return state;
    }

    /**
     * 現在の要求を失敗状態として保持し、同じ要求での毎フレームの再初期化を抑える。
     *
     * @param request 現在の寸法・品質・深度方向・描画経路を持つ要求
     * @param reason 退避・履歴中断・解析終了の理由
     * @return 失敗した要求と理由を保持する退避状態
     */
    public State markError(Request request, String reason) {
        Objects.requireNonNull(request, "request");
        String detail = reason == null || reason.isBlank() ? "DLSS product session failed" : reason;
        if (closed || !request.equals(state.pendingRequest()) && !request.equals(state.activeRequest())) {
            throw new IllegalStateException("DLSS failure does not match the current request");
        }
        failedRequest = request;
        state = new State(new BackendSelection(UpscaleBackend.DLSS, UpscaleBackend.BILINEAR, detail),
                DlssRuntimeState.ERROR, null, null, state.generation(), detail);
        return state;
    }

    /**
     * 次の入力を時間方向履歴の開始として扱い、旧フレームとの連続参照を中断する。
     *
     * @return 有効要求を再初期化待ちへ移した状態
     */
    public State breakHistory() {
        if (closed || state.activeRequest() == null) return state;
        Request request = state.activeRequest();
        state = new State(new BackendSelection(UpscaleBackend.DLSS, UpscaleBackend.BILINEAR, INITIALIZING),
                DlssRuntimeState.INITIALIZING, null, request, state.generation(), INITIALIZING);
        return state;
    }

    /**
     * 失敗した要求の保持を解除し、次の同期で再初期化できる状態にする。
     *
     * @return 失敗要求の保持を解除した再試行可能な状態
     */
    public State retry() {
        if (closed) throw new IllegalStateException("DLSS product session is closed");
        failedRequest = null;
        if (state.runtimeState() == DlssRuntimeState.ERROR) {
            state = inactive(state.backend().requested(), "DLSS retry requested", state.generation());
        }
        return state;
    }

    public State state() {
        return state;
    }

    /**
     * 所有するリソースと保留状態を解放する。
     */
    @Override
    public void close() {
        if (closed) return;
        clearRequests();
        state = inactive(UpscaleBackend.BILINEAR, "", state.generation());
        closed = true;
    }

    private void clearRequests() {
        failedRequest = null;
    }

    private static State inactive(UpscaleBackend requested, String reason, long generation) {
        BackendSelection selection = requested == UpscaleBackend.BILINEAR
                ? BackendSelection.resolve(requested, false, reason)
                : new BackendSelection(requested, UpscaleBackend.BILINEAR, reason);
        return new State(selection, DlssRuntimeState.INACTIVE, null, null, generation, reason);
    }
}
