package celeste.titanium.client.render;

import java.util.Objects;

/** Irisのパイプライン寿命とフレーム境界をCPU側で追跡する。 */
final class IrisFrameBoundary {
    private Object pipeline;
    private boolean began, completed;

    void startFrame() { began = completed = false; }

    /**
     * Irisパイプラインの所有者を記録し、前回の所有者から置き換わったか返す。
     *
     * @param next 今フレームの描画を開始した実Irisパイプライン
     * @return 直前に記録したパイプライン所有者から置き換わった場合はtrue
     */
    boolean begin(Object next) {
        Objects.requireNonNull(next, "pipeline");
        boolean changed = pipeline != null && pipeline != next;
        pipeline = next;
        began = true;
        completed = false;
        return changed;
    }

    void complete(Object owner) {
        completed = began && owner == pipeline;
    }

    boolean completed() { return began && completed; }
    boolean destroyed(Object owner) {
        if (owner == null || owner != pipeline) return false;
        startFrame();
        // 置き換え前の識別情報を保持し、次の開始時に時間方向の履歴も無効化する。
        return true;
    }
    void clear() { pipeline = null; startFrame(); }
}
