package celeste.titanium.client.render.enhanced;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import java.lang.reflect.*;
import java.util.*;

/** Irisの対応バージョンから描画メタデータを取得する独立アダプター。 */
final class IrisPipelineAccess {
    record Pass(String stage, String name, int program, List<Integer> declaredOutputs, int computes) {}
    private record Owner(Object renderer, String stage, boolean last, List<Object> passes) {}
    private final IdentityHashMap<Object,Owner> owners = new IdentityHashMap<>();
    private final List<Pass> topology = new ArrayList<>();
    private final IdentityHashMap<Object,Pass> passMetadata = new IdentityHashMap<>();
    private final Map<String,Field> fields = new HashMap<>();
    private int beforeProgram, beforeFbo, beforeActive;
    private int[] beforeViewport;
    private Object pendingRenderer;
    private Object batchRenderer;
    private int lastPassIndex = -1;
    private long drawQueries;

    /**
     * 現在のIris rendererとpassを走査し、実drawを照合するためのプログラム・出力宣言を取得する。
     *
     * @param pipeline 開始・完了・破棄を観測するIrisパイプライン
     */
    IrisPipelineAccess(Object pipeline) throws ReflectiveOperationException {
        // 構築済みIrisパイプラインからrendererとpassの対応を取得し、後の実GL描画と照合する。
        for (String field : List.of("beginRenderer", "prepareRenderer", "deferredRenderer", "compositeRenderer", "finalPassRenderer")) {
            Object renderer = field(pipeline, field);
            if (renderer == null) continue;
            boolean last = field.equals("finalPassRenderer");
            String stage = last ? "FINAL" : String.valueOf(field(renderer, "compositePass"));
            var passes = new ArrayList<Object>();
            if (last) { Object pass = field(renderer, "finalPass"); if (pass != null) passes.add(pass); }
            else for (Object pass : (Iterable<?>) field(renderer, "passes")) passes.add(pass);
            if (passes.size() > 256) throw new IllegalStateException("Iris pass inventory bound");
            for (Object pass : passes) {
                Object program = field(pass, "program");
                int id = program == null ? 0 : (int) program.getClass().getMethod("getProgramId").invoke(program);
                int computes = 0; for (Object compute : (Object[])field(pass, "computes")) if (compute != null) computes++;
                var outputs = new ArrayList<Integer>();
                // 計算専用passには描画プログラムとdrawBuffersがない。計算の存在は後続drawの未観測書き込みとして扱う。
                if (!last && program != null) for (int slot : (int[])field(pass, "drawBuffers")) outputs.add(slot);
                var metadata = new Pass(stage, last ? "FINAL" : String.valueOf(field(pass, "name")), id, List.copyOf(outputs), computes);
                topology.add(metadata); passMetadata.put(pass, metadata);
            }
            owners.put(renderer, new Owner(renderer, stage, last, List.copyOf(passes)));
        }
        if (owners.isEmpty() || topology.isEmpty()) throw new IllegalStateException("No supported Iris pipeline topology");
    }
    /**
     * 構築済みrendererの所属を確認し、新しい観測区間としてグラフへ登録する。
     *
     * @param renderer 対象のワールドまたはIris renderer
     * @param graph 現在フレームの実描画接続を記録する観測グラフ
     */
    void beginBatch(Object renderer, EnhancedPipelineGraph graph) {
        Owner owner = owners.get(renderer);
        if (owner == null) throw new IllegalStateException("Renderer belongs to a different pipeline generation");
        graph.beginBatch(owner.stage());
        batchRenderer = renderer; lastPassIndex = -1;
    }
    /**
     * 実行中プログラムとFBOを既知passへ対応づけ、バインド済みの入力・出力と寸法を記録する。
     *
     * @param renderer 対象のワールドまたはIris renderer
     * @param graph 現在フレームの実描画接続を記録する観測グラフ
     */
    void before(Object renderer, EnhancedPipelineGraph graph) throws ReflectiveOperationException {
        if (pendingRenderer != null) throw new IllegalStateException("Overlapping Iris draw observer");
        Owner owner = owners.get(renderer);
        if (owner == null || batchRenderer != renderer) throw new IllegalStateException("Unknown actual renderer/batch");
        beforeProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        beforeFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        beforeActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        beforeViewport = viewport();
        Pass selected = null;
        Object selectedObject = null;
        int selectedIndex = -1;
        // 現在使用中のGLプログラムをコンパイル済みpassへ対応づけ、重複や実行順の変化を検出する。
        for (int index = 0; index < owner.passes().size(); index++) {
            Object p = owner.passes().get(index);
            Pass m = passMetadata.get(p);
            if (m.program() == beforeProgram) {
                if (selected != null) throw new IllegalStateException("Ambiguous compiled pass program");
                selected = m;
                selectedObject = p; selectedIndex = index;
            }
        }
        if (selected == null || beforeProgram <= 0 || beforeFbo <= 0) throw new IllegalStateException("Actual draw not in compiled topology");
        if (selectedIndex <= lastPassIndex) throw new IllegalStateException("Actual pass execution differs from compiled order");
        boolean priorCompute = false;
        for (int index = lastPassIndex + 1; index <= selectedIndex; index++)
            priorCompute |= passMetadata.get(owner.passes().get(index)).computes() > 0;
        lastPassIndex = selectedIndex;
        if (!owner.last()) {
            Object framebuffer = field(selectedObject, "framebuffer");
            if ((int)framebuffer.getClass().getMethod("getId").invoke(framebuffer) != beforeFbo)
                throw new IllegalStateException("Actual framebuffer differs from compiled pass");
        }
        var outputs = new ArrayList<EnhancedPipelineGraph.Texture>();
        var reads = new ArrayList<EnhancedPipelineGraph.Read>();
        var gaps = new ArrayList<String>();
        boolean imageAccess = false;
        var textures = new HashMap<Integer,EnhancedPipelineGraph.Texture>();
        int max = GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);
        if (max > 16) throw new IllegalStateException("Draw buffer observation bound");
        // 宣言されたバッファ番号だけを使わず、実際のFBOアタッチメントから出力テクスチャを取得する。
        for (int slot = 0; slot < max; slot++) {
            int attachment = GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0 + slot);
            if (attachment == GL11C.GL_NONE) continue;
            if (attachment < GL30C.GL_COLOR_ATTACHMENT0 || attachment >= GL30C.GL_COLOR_ATTACHMENT0 + 16
                    || GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11C.GL_TEXTURE
                    || GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) != 0
                    || GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL32C.GL_FRAMEBUFFER_ATTACHMENT_LAYERED) != 0)
                throw new IllegalStateException("Unsupported actual framebuffer attachment");
            int texture = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            outputs.add(texture(texture, textures));
        }
        if (!owner.last() && outputs.size() != selected.declaredOutputs().size()) throw new IllegalStateException("Actual/declared output count differs");
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1); var type = stack.mallocInt(1);
            int uniforms = GL20C.glGetProgrami(beforeProgram, GL20C.GL_ACTIVE_UNIFORMS);
            if (uniforms > 2048) throw new IllegalStateException("Uniform observation bound");
            // 実uniform値で指定されたサンプラーユニットを読み、現在バインド中の入力テクスチャへ辿る。
            for (int i = 0; i < uniforms; i++) {
                String name = GL20C.glGetActiveUniform(beforeProgram, i, size, type);
                if (type.get(0) != GL20C.GL_SAMPLER_2D) {
                    if (isResourceType(type.get(0))) gaps.add("UNOBSERVED_RESOURCE_TYPE:" + name + ":" + type.get(0));
                    imageAccess |= isImageType(type.get(0));
                    continue;
                }
                if (size.get(0) != 1) { gaps.add("SAMPLER_ARRAY_UNOBSERVED:" + name); continue; }
                int unit = GL20C.glGetUniformi(beforeProgram, GL20C.glGetUniformLocation(beforeProgram, name));
                if (unit < 0 || unit >= GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)) throw new IllegalStateException("Sampler unit outside actual device limits");
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                int id = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                if (id == 0) { gaps.add("ACTIVE_SAMPLER_UNBOUND:" + name); continue; }
                reads.add(new EnhancedPipelineGraph.Read(name, unit, texture(id, textures)));
                if (reads.size() > 64) throw new IllegalStateException("Sampler observation bound");
            }
        } finally { GL13C.glActiveTexture(beforeActive); }
        if (GL43C.glGetProgramInterfacei(beforeProgram, GL43C.GL_SHADER_STORAGE_BLOCK, GL43C.GL_ACTIVE_RESOURCES) > 0
                || GL20C.glGetProgrami(beforeProgram, GL42C.GL_ACTIVE_ATOMIC_COUNTER_BUFFERS) > 0)
            gaps.add("STORAGE_ATOMIC_WRITES_UNOBSERVED");
        if (!stateUnchanged()) throw new IllegalStateException("Read-only observation changed GL state");
        if (imageAccess || gaps.contains("STORAGE_ATOMIC_WRITES_UNOBSERVED"))
            graph.unobservedWrites("IMAGE_STORAGE_ATOMIC_WRITES_UNOBSERVED");
        graph.before(selected.name(), beforeProgram, beforeFbo,
                Arrays.stream(beforeViewport).boxed().toList(), reads, outputs, gaps,
                priorCompute);
        drawQueries++; pendingRenderer = renderer;
    }
    void after(Object renderer, EnhancedPipelineGraph graph) {
        if (pendingRenderer != renderer || !stateUnchanged()) throw new IllegalStateException("Actual draw boundary changed unexpectedly");
        graph.after(beforeProgram, beforeFbo); pendingRenderer = null;
    }
    List<Pass> topology() { return List.copyOf(topology); }
    long drawQueries() { return drawQueries; }
    private static boolean isResourceType(int type) {
        // All unsupported sampler/image families are explicit gaps; ordinary uniforms are not resources.
        return (type >= 0x8B5D && type <= 0x8B64) || (type >= 0x8DC0 && type <= 0x8DD7)
                || (type >= 0x900C && type <= 0x900F) || (type >= 0x904C && type <= 0x906C)
                || (type >= 0x9108 && type <= 0x910D);
    }
    static boolean isImageType(int type) { return type >= 0x904C && type <= 0x906C; }
    private static EnhancedPipelineGraph.Texture texture(int id, Map<Integer,EnhancedPipelineGraph.Texture> cache) {
        return cache.computeIfAbsent(id, key -> {
            if (key <= 0 || GL45C.glGetTextureParameteri(key, GL45C.GL_TEXTURE_TARGET) != GL11C.GL_TEXTURE_2D)
                throw new IllegalStateException("Only actual 2D textures are modelled");
            return new EnhancedPipelineGraph.Texture(key,
                    GL45C.glGetTextureLevelParameteri(key, 0, GL11C.GL_TEXTURE_WIDTH),
                    GL45C.glGetTextureLevelParameteri(key, 0, GL11C.GL_TEXTURE_HEIGHT),
                    GL45C.glGetTextureLevelParameteri(key, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT));
        });
    }
    private boolean stateUnchanged() {
        return beforeProgram == GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)
                && beforeFbo == GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)
                && beforeActive == GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)
                && Arrays.equals(beforeViewport, viewport());
    }
    private static int[] viewport() { int[] result = new int[4]; GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, result); return result; }
    /**
     * Irisオブジェクトの継承階層からフィールドを読み、取得済みFieldを再利用する。
     *
     * @param owner 反射またはパイプライン寿命を追跡する所有オブジェクト
     * @param name 対象または診断区間を識別する名前
     * @return 取得したフィールド値。参照は元のIrisオブジェクトに属する
     */
    private Object field(Object owner, String name) throws ReflectiveOperationException {
        String key = owner.getClass().getName() + ":" + name;
        Field f = fields.get(key);
        if (f == null) {
            // ComputeOnlyPassはPassのフィールドを継承する。実クラスだけを検索すると計算passを含むパックで失敗する。
            for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    f = type.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException absent) {
                    // 次の親クラスで宣言を確認する。
                }
            }
            if (f == null) throw new NoSuchFieldException(key);
            f.setAccessible(true);
            fields.put(key, f);
        }
        return f.get(owner);
    }
}
