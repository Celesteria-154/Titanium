package celeste.titanium.client.render;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** GPU APIに依存しないフレーム入力履歴。DLSSセッションと診断処理で共有する。 */
final class FrameInputHistory {
    record Result(FrameInputPacket previous, FrameInputPacket current, boolean valid,
                  boolean resourceMatches, boolean jitterValid) {
    }

    private FrameInputPacket previous;
    private boolean historyBroken = true;
    private String pendingResetReason = "INITIAL";
    private String lastResetReason = "N/A";
    private long historyGeneration;
    private long framesSinceReset;
    private long previousSequence;
    private long sequence;
    private long packets;
    private long resets;
    private long continuousFrames;
    private long cameraMotionFrames;
    private long invalidPackets;
    private long jitterPackets;
    private long jitterResets;
    private long jitterTransitions;
    private long invalidJitterPackets;

    /**
     * 前後の寸法・ディメンション・投影とジッターを検証し、有効な入力だけを次フレームの参照へ保存する。
     *
     * @param unjitteredProjection ジッターを適用していない入力投影
     * @param renderProjection ジッター適用後に実描画で使用した投影行列
     * @param jitter 実投影と対応する入力ピクセル単位のサンプル
     * @param modelView 同じワールド描画で使用するモデルビュー行列
     * @param cameraPosition 入力取得時のワールド座標でのカメラ位置
     * @param nearPlane 入力取得時の近クリップ距離
     * @param farPlane 入力取得時の遠クリップ距離
     * @param fieldOfView この入力で実際に使用するFOV。度単位
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param dimension 現在のワールドのディメンション識別子
     * @param resourceMatches 取得時と確定時の色・深度・ターゲットが一致する場合はtrue
     * @return 前後パケットと、リソース・入力値・ジッターの照合結果
     */
    Result accept(Matrix4fc unjitteredProjection, Matrix4fc renderProjection, FrameJitter jitter,
                  Matrix4fc modelView, Vec3 cameraPosition, float nearPlane, float farPlane,
                  float fieldOfView, int width, int height, String dimension, boolean resourceMatches) {
        // ディメンション・描画寸法・明示的な中断を確認し、履歴を継続できるか判定する。
        boolean dimensionChanged = previous != null && !dimension.equals(previous.dimension());
        boolean sizeChanged = previous != null && (width != previous.width() || height != previous.height());
        boolean reset = historyBroken || previous == null || dimensionChanged || sizeChanged;
        String resetReason = previous == null ? "INITIAL"
                : dimensionChanged ? "DIMENSION"
                : sizeChanged ? "RESOLUTION"
                : pendingResetReason;
        FrameInputPacket packet = FrameInputPacket.create(++sequence, unjitteredProjection, renderProjection,
                jitter, modelView, cameraPosition, nearPlane, farPlane, fieldOfView,
                width, height, dimension, reset);
        // 行列へ適用したジッターと位相の連続性を検証してから、履歴へ採用する。
        boolean jitterValid = validateJitter(packet, previous);
        if (!jitterValid) invalidJitterPackets++;
        boolean valid = resourceMatches && packet.valid() && jitterValid;
        FrameInputPacket prior = previous;
        // 不正な入力は直前の有効パケットを置き換えず、次の有効入力で履歴を再開する。
        if (!valid) {
            invalidPackets++;
            historyBroken = true;
            return new Result(prior, packet, false, resourceMatches, jitterValid);
        }

        packets++;
        if (reset) {
            resets++;
            historyGeneration++;
            framesSinceReset = 0;
            lastResetReason = resetReason;
        } else {
            framesSinceReset++;
        }
        if (packet.jitter().enabled()) {
            jitterPackets++;
            if (reset) jitterResets++;
            if (prior != null && packet.continuousWith(prior)) jitterTransitions++;
        }
        if (prior != null && packet.continuousWith(prior)) continuousFrames++;
        if (prior != null && packet.cameraChangedFrom(prior)) cameraMotionFrames++;
        previousSequence = prior == null ? 0 : prior.sequence();
        // 検証済みの入力だけを次フレームの参照として保存する。
        previous = packet;
        historyBroken = false;
        pendingResetReason = "N/A";
        return new Result(prior, packet, true, true, true);
    }

    /**
     * 次の入力を時間方向履歴の開始として扱い、旧フレームとの連続参照を中断する。
     */
    void breakHistory() {
        breakHistory("EXTERNAL");
    }

    /**
     * 次の入力を時間方向履歴の開始として扱い、旧フレームとの連続参照を中断する。
     *
     * @param reason 退避・履歴中断・解析終了の理由
     */
    void breakHistory(String reason) {
        historyBroken = true;
        pendingResetReason = reason == null || reason.isBlank() ? "EXTERNAL" : reason;
    }

    FrameInputPacket latest() {
        return previous;
    }

    boolean historyValid() {
        return previous != null && !historyBroken && !previous.reset();
    }

    long packets() {
        return packets;
    }

    long resets() {
        return resets;
    }

    long continuousFrames() {
        return continuousFrames;
    }

    long cameraMotionFrames() {
        return cameraMotionFrames;
    }

    long invalidPackets() {
        return invalidPackets;
    }

    long invalidJitterPackets() {
        return invalidJitterPackets;
    }

    long historyGeneration() {
        return historyGeneration;
    }

    long framesSinceReset() {
        return framesSinceReset;
    }

    String lastResetReason() {
        return lastResetReason;
    }

    long latestSequence() {
        return previous == null ? 0 : previous.sequence();
    }

    long previousSequence() {
        return previousSequence;
    }

    boolean passed() {
        return invalidPackets == 0 && packets > 0 && resets > 0 && continuousFrames > 0;
    }

    boolean historyPassed() {
        return passed() && resets >= 3 && cameraMotionFrames > 0;
    }

    boolean jitterPassed() {
        return historyPassed() && jitterPackets > 0 && jitterResets == resets
                && jitterTransitions > 0 && invalidJitterPackets == 0;
    }

    /**
     * 現在の実行状態、進行カウンター、直近の失敗理由を診断文字列へまとめる。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @return 取得済みの実行状態、カウンターと直近の失敗理由を含む診断文字列
     */
    String diagnostics(FrameInputPacket packet) {
        return "sequence=" + sequence + ";packets=" + packets + ";resets=" + resets
                + ";continuous=" + continuousFrames + ";cameraMotion=" + cameraMotionFrames
                + ";jitter={packets=" + jitterPackets + ",resets=" + jitterResets
                + ",transitions=" + jitterTransitions + ",invalid=" + invalidJitterPackets
                + ",phase=" + packet.jitter().phase() + "/" + packet.jitter().phaseCount() + "}"
                + ";size=" + packet.width() + "x" + packet.height() + ";dimension=" + packet.dimension();
    }

    /**
     * 未ジッター投影へサンプルを再適用して実投影と照合し、履歴開始・継続時の位相も確認する。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param previous 参照する直前の確定済み入力パケット
     * @return 適用行列と履歴開始・継続時の位相が入力契約を満たす場合はtrue
     */
    private static boolean validateJitter(FrameInputPacket packet, FrameInputPacket previous) {
        // 未ジッター投影に同じサンプル位置を再適用し、実描画へ渡した投影と照合する。
        Matrix4f expected = packet.jitter().enabled()
                ? ProjectionJitter.apply(packet.unjitteredProjection(), packet.jitter(),
                packet.width(), packet.height())
                : packet.unjitteredProjection();
        if (!expected.equals(packet.renderProjection(), 1.0e-6F)) return false;
        if (!packet.jitter().enabled()) return true;
        if (packet.reset() && packet.jitter().phase() != 0) return false;
        if (previous != null && packet.continuousWith(previous)) {
            FrameJitter prior = previous.jitter();
            return prior.enabled() && prior.phaseCount() == packet.jitter().phaseCount()
                    && packet.jitter().phase() == (prior.phase() + 1) % prior.phaseCount();
        }
        return packet.reset();
    }
}
