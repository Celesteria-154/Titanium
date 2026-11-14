package celeste.titanium.client.render.enhanced;

import java.util.*;

/** 2フレーム分の描画構造を収集する。描画介入の可否は別の実行時アダプターが判定する。 */
public final class EnhancedAnalysisSession {
    public enum State { ANALYZING, FALLBACK, CLOSED }
    public static final int FRAME_LIMIT = 2;
    private final long generation;
    private final List<EnhancedPipelineGraph.Frame> frames = new ArrayList<>();
    private State state = State.ANALYZING;
    private String reason = "READ_ONLY_ANALYSIS";
    private EnhancedPipelineGraph graph;

    public EnhancedAnalysisSession(long generation) {
        if (generation <= 0) throw new IllegalArgumentException("Invalid generation");
        this.generation = generation;
    }
    public void beginFrame() {
        if (state != State.ANALYZING || graph != null || frames.size() >= FRAME_LIMIT)
            throw new IllegalStateException("Analysis frame lifetime");
        graph = new EnhancedPipelineGraph(generation, frames.size());
    }
    public EnhancedPipelineGraph graph() {
        if (state != State.ANALYZING || graph == null) throw new IllegalStateException("No open analysis frame");
        return graph;
    }
    /**
     * 現在の観測結果を保存し、2フレーム収集後に実行時アダプターで照合可能な状態へ移す。
     */
    public void finishFrame() {
        frames.add(graph().finish()); graph = null;
        if (frames.size() == FRAME_LIMIT) { state = State.FALLBACK; reason = "NO_VALIDATED_ENHANCED_ADAPTER"; }
    }
    public void fail(String detail) { graph = null; state = State.FALLBACK; reason = "ANALYSIS_FAILED: " + detail; }
    /**
     * 所有するリソースと保留状態を解放する。
     */
    public void close() { graph = null; state = State.CLOSED; reason = "INVALIDATED"; frames.clear(); }
    public boolean observing() { return state == State.ANALYZING && graph != null; }
    public State state() { return state; }
    public String reason() { return reason; }
    public long generation() { return generation; }
    public List<EnhancedPipelineGraph.Frame> frames() { return List.copyOf(frames); }
    public boolean enhancedRenderingActive() { return false; }
}
