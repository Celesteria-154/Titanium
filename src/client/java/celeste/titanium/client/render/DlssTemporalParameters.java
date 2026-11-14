package celeste.titanium.client.render;

/** 1回のNGX評価に渡す不変の時間方向パラメーター。 */
record DlssTemporalParameters(
        long sequence,
        boolean reset,
        double jitterX,
        double jitterY,
        double frameTimeMilliseconds) {
    static final double MOTION_VECTOR_SCALE_X = 1.0;
    static final double MOTION_VECTOR_SCALE_Y = 1.0;

    DlssTemporalParameters {
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        if (!Double.isFinite(jitterX) || jitterX < -0.5 || jitterX >= 0.5
                || !Double.isFinite(jitterY) || jitterY < -0.5 || jitterY >= 0.5) {
            throw new IllegalArgumentException("jitter must be a finite render-pixel offset in [-0.5, 0.5)");
        }
        if (!Double.isFinite(frameTimeMilliseconds) || frameTimeMilliseconds <= 0.0) {
            throw new IllegalArgumentException("frame time must be finite and positive");
        }
    }

    /**
     * 確定済み入力からNGXのシーケンス・履歴リセット・ジッター・入力取得時間を組み立てる。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param frameTimeMilliseconds 同一入力の取得間隔。GPUの実行時間から独立したミリ秒値
     * @return 同じ入力に属するシーケンス・リセット・送信ジッター・取得時間
     */
    static DlssTemporalParameters from(FrameInputPacket packet, double frameTimeMilliseconds) {
        return from(packet, frameTimeMilliseconds, DlssJitterFeed.configured(),
                DlssHistoryPolicy.configured());
    }

    /**
     * 確定済み入力からNGXのシーケンス・履歴リセット・ジッター・入力取得時間を組み立てる。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param frameTimeMilliseconds 同一入力の取得間隔。GPUの実行時間から独立したミリ秒値
     * @param jitterFeed 入力サンプルをNGXの変位へ換算する符号規約
     * @return 同じ入力に属するシーケンス・リセット・送信ジッター・取得時間
     */
    static DlssTemporalParameters from(FrameInputPacket packet, double frameTimeMilliseconds,
                                       DlssJitterFeed jitterFeed) {
        return from(packet, frameTimeMilliseconds, jitterFeed, DlssHistoryPolicy.NORMAL);
    }

    /**
     * 確定済み入力からNGXのシーケンス・履歴リセット・ジッター・入力取得時間を組み立てる。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param frameTimeMilliseconds 同一入力の取得間隔。GPUの実行時間から独立したミリ秒値
     * @param jitterFeed 入力サンプルをNGXの変位へ換算する符号規約
     * @param historyPolicy NGXへ渡す履歴リセット要求の方針
     * @return 同じ入力に属するシーケンス・リセット・送信ジッター・取得時間
     */
    static DlssTemporalParameters from(FrameInputPacket packet, double frameTimeMilliseconds,
                                       DlssJitterFeed jitterFeed, DlssHistoryPolicy historyPolicy) {
        if (packet == null || !packet.valid()) {
            throw new IllegalArgumentException("A valid frame packet is required");
        }
        // CPUのフレーム番号・履歴状態を維持し、NGXが期待する変位方向へジッターだけ変換する。
        FrameJitter jitter = packet.jitter();
        return new DlssTemporalParameters(packet.sequence(), historyPolicy.reset(packet.reset()),
                jitterFeed.apply(jitter.pixelX()), jitterFeed.apply(jitter.pixelY()), frameTimeMilliseconds);
    }
}
