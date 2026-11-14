package celeste.titanium.client.render.enhanced;

import java.util.*;

/** 実際の全画面描画で使用された入力・出力を記録する。バッファの意味はアダプター側で検証する。 */
public final class EnhancedPipelineGraph {
    public record Texture(int id, int width, int height, int internalFormat) {
        public Texture { require(id > 0 && width > 0 && height > 0, "Invalid observed texture"); }
    }
    public record Read(String sampler, int unit, Texture texture) {
        public Read { require(sampler != null && !sampler.isBlank() && unit >= 0 && texture != null, "Invalid sampler observation"); }
    }
    public record Edge(String sampler, int texture, int priorObservedDraw) {}
    public record Draw(int index, int batch, String stage, String pass, int program, int framebuffer,
                       List<Integer> viewport, List<Read> reads, List<Texture> writes, List<Edge> edges,
                       List<String> coverageGaps, boolean stateUnchanged) {}
    public record Frame(long generation, int index, List<Draw> draws, List<String> coverageGaps) {}

    private final long generation;
    private final int frame;
    private final Map<Integer,Integer> writers = new HashMap<>();
    private final List<Draw> draws = new ArrayList<>();
    private final Set<String> gaps = new LinkedHashSet<>();
    private Draw pending;
    private String stage;
    private int batch = -1;
    private boolean closed;

    public EnhancedPipelineGraph(long generation, int frame) {
        require(generation > 0 && frame >= 0, "Invalid graph lifetime");
        this.generation = generation; this.frame = frame;
    }
    /**
     * 一つのrenderer区間の観測を始め、前区間の生成元との未検証な接続を切る。
     *
     * @param name 対象または診断区間を識別する名前
     */
    public void beginBatch(String name) {
        require(!closed && pending == null && name != null && !name.isBlank(), "Batch overlaps an open draw");
        if (batch >= 0) gaps.add("UNOBSERVED_WORK_BETWEEN_RENDERER_BATCHES");
        stage = name; batch++; writers.clear();
    }
    /**
     * 実draw直前の入力・出力・寸法を検証し、観測済みの生成元への接続を持つ保留描画を作る。
     *
     * @param pass 観測するpassの識別名
     * @param program 対象のGLプログラム識別子
     * @param framebuffer 現在のGL書き込みFBO識別子
     * @param viewport 左端・下端・幅・高さを持つ実ビューポート
     * @param reads 実際にバインドされた入力サンプラーとテクスチャの観測結果
     * @param writes 実FBOへ接続された出力テクスチャの観測結果
     * @param coverageGaps この観測では追跡できないリソースや副作用の記録
     * @param priorCompute 直前までの区間に未観測のCompute処理がある場合はtrue
     */
    public void before(String pass, int program, int framebuffer, List<Integer> viewport,
                       List<Read> reads, List<Texture> writes, List<String> coverageGaps, boolean priorCompute) {
        require(!closed && pending == null && stage != null && draws.size() < 256, "Invalid or excessive draw sequence");
        require(pass != null && !pass.isBlank() && program > 0 && framebuffer > 0 && viewport.size() == 4
                && viewport.get(2) > 0 && viewport.get(3) > 0 && reads.size() <= 64
                && !writes.isEmpty() && writes.size() <= 16, "Invalid draw schema");
        var ids = new HashSet<Integer>();
        for (var output : writes) require(ids.add(output.id()), "Duplicate physical output");
        // Computeを挟むと未観測の書き込みがあり得るため、以前の生成元との接続を継続しない。
        if (priorCompute) { writers.clear(); gaps.add("COMPUTE_WRITES_UNOBSERVED"); }
        var edges = new ArrayList<Edge>();
        // 各入力テクスチャを、同じ観測区間で最後に書いた描画へ接続する。未観測の生成元は-1で残す。
        for (var read : reads) {
            require(!ids.contains(read.texture().id()), "Unmodelled input/output feedback");
            edges.add(new Edge(read.sampler(), read.texture().id(), writers.getOrDefault(read.texture().id(), -1)));
        }
        gaps.addAll(coverageGaps);
        pending = new Draw(draws.size(), batch, stage, pass, program, framebuffer, List.copyOf(viewport),
                List.copyOf(reads), List.copyOf(writes), List.copyOf(edges), List.copyOf(coverageGaps), false);
    }
    public void unobservedWrites(String reason) {
        require(!closed && pending == null && reason != null && !reason.isBlank(), "Invalid unobserved-write barrier");
        writers.clear(); gaps.add(reason);
    }
    /**
     * 実draw後のプログラムとFBOを照合し、保留描画を確定して出力の生成元を更新する。
     *
     * @param program 対象のGLプログラム識別子
     * @param framebuffer 現在のGL書き込みFBO識別子
     */
    public void after(int program, int framebuffer) {
        require(!closed && pending != null && pending.program() == program && pending.framebuffer() == framebuffer,
                "Actual draw post does not match pre");
        var p = pending;
        draws.add(new Draw(p.index(), p.batch(), p.stage(), p.pass(), p.program(), p.framebuffer(),
                p.viewport(), p.reads(), p.writes(), p.edges(), p.coverageGaps(), true));
        // 描画前後のプログラム・FBOが一致した後に、出力テクスチャの生成元を更新する。
        for (var write : p.writes()) writers.put(write.id(), p.index());
        pending = null;
    }
    /**
     * 開いた描画がないことを確認し、観測範囲の不足も記録した不変のフレーム結果を返す。
     *
     * @return 実draw、入力・出力接続と未観測範囲を持つ不変の観測フレーム
     */
    public Frame finish() {
        require(!closed && pending == null && !draws.isEmpty(), "Incomplete observed frame");
        closed = true;
        // このグラフが観測していない処理を明示し、完全なフレーム全体の依存解析と混同させない。
        gaps.add("GEOMETRY_CLEAR_COPY_SHADOW_COMPUTE_NOT_FULLY_OBSERVED");
        gaps.add("COLOR_SPACE_EXPOSURE_TEMPORAL_STAGE_UNKNOWN");
        return new Frame(generation, frame, List.copyOf(draws), List.copyOf(gaps));
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
