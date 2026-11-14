package celeste.titanium.client.config;

import java.util.Objects;

/** 要求されたバックエンドと、実行条件を満たす有効バックエンドを分離する。 */
public record BackendSelection(UpscaleBackend requested, UpscaleBackend active, String reason) {
    public BackendSelection {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(active, "active");
        reason = reason == null ? "" : reason;
    }

    /**
     * 要求バックエンドとDLSSの利用可否から、有効バックエンドおよび退避理由を決定する。
     *
     * @param requested 利用者が要求したバックエンド
     * @param dlssReady DLSSの実行条件を満たす場合はtrue
     * @param unavailableReason DLSSを利用できない場合の説明
     * @return 要求・有効バックエンドと退避理由を持つ選択結果
     */
    public static BackendSelection resolve(UpscaleBackend requested, boolean dlssReady, String unavailableReason) {
        Objects.requireNonNull(requested, "requested");
        if (requested == UpscaleBackend.BILINEAR) {
            return new BackendSelection(requested, UpscaleBackend.BILINEAR, "");
        }
        if (dlssReady) {
            return new BackendSelection(requested, UpscaleBackend.DLSS, "");
        }
        String reason = unavailableReason == null || unavailableReason.isBlank()
                ? "DLSS unavailable" : unavailableReason;
        return new BackendSelection(requested, UpscaleBackend.BILINEAR, reason);
    }

    public boolean fallback() {
        return requested != active;
    }
}
