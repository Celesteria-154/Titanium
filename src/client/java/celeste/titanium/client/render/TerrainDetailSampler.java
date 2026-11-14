package celeste.titanium.client.render;

import celeste.titanium.client.TitaniumClient;
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import java.util.OptionalDouble;

/** 地形描画用のサンプラーを所有する。共有元のサンプラーの変更・解放は行わない。 */
public final class TerrainDetailSampler implements AutoCloseable {
    record Descriptor(AddressMode u, AddressMode v, FilterMode min, FilterMode mag,
                      int anisotropy, OptionalDouble maxLod) {
        static Descriptor from(GpuSampler source) {
            return new Descriptor(source.getAddressModeU(), source.getAddressModeV(),
                    source.getMinFilter(), source.getMagFilter(), source.getMaxAnisotropy(), source.getMaxLod());
        }
        boolean matches(GpuSampler source) {
            return u == source.getAddressModeU() && v == source.getAddressModeV()
                    && min == source.getMinFilter() && mag == source.getMagFilter()
                    && anisotropy == source.getMaxAnisotropy() && maxLod.equals(source.getMaxLod());
        }
        boolean mipmapped() { return maxLod.isEmpty() || maxLod.getAsDouble() > 0; }
    }

    private GlSampler sampler, source;
    private final int minimumAnisotropy;
    private final boolean preserveSourceDescriptor;
    private Descriptor descriptor;
    private GlSampler excludedSource;
    private Descriptor excludedDescriptor;
    private float appliedBias, sourceBias;
    private boolean failed;
    private int creations, releases, biasWrites;

    public TerrainDetailSampler() { this(1); }

    /**
     * 専用サンプラーの最低AFと、元の実GL設定を保持するかを設定する。
     *
     * @param minimumAnisotropy 専用サンプラーで要求する最低AF。1または8
     */
    TerrainDetailSampler(int minimumAnisotropy) { this(minimumAnisotropy, false); }

    public static TerrainDetailSampler preservingSource() { return new TerrainDetailSampler(1, true); }

    /**
     * 専用サンプラーの最低AFと、元の実GL設定を保持するかを設定する。
     *
     * @param minimumAnisotropy 専用サンプラーで要求する最低AF。1または8
     * @param preserveSourceDescriptor 元サンプラーの実GL記述を保持する場合はtrue
     */
    private TerrainDetailSampler(int minimumAnisotropy, boolean preserveSourceDescriptor) {
        if (minimumAnisotropy != 1 && minimumAnisotropy != 8)
            throw new IllegalArgumentException("Unsupported terrain anisotropy");
        this.minimumAnisotropy = minimumAnisotropy;
        this.preserveSourceDescriptor = preserveSourceDescriptor;
    }

    /**
     * 通常経路のLOD差分が有限かつ[-1,0)に収まっているか確認する。
     *
     * @param bias 元のLODバイアスに加算するミップ単位の差分
     * @return 通常経路のLOD差分が有限かつ[-1,0)に収まっている場合はtrue
     */
    static boolean validBias(float bias) { return Float.isFinite(bias) && bias >= -1 && bias < 0; }
    /**
     * 認証されたEnhanced倍率向けのLOD差分が有限かつ[-2,0)に収まるか確認する。
     *
     * @param bias 元のLODバイアスに加算するミップ単位の差分
     * @return 認証されたEnhanced倍率向けのLOD差分が有限かつ[-2,0)に収まる場合はtrue
     */
    static boolean validEnhancedFootprintBias(float bias) { return Float.isFinite(bias) && bias >= -2 && bias < 0; }

    /**
     * 元サンプラーと補正条件を確認し、対応する専用サンプラーを生成または再利用して返す。
     *
     * @param original 設定を基準にする共有元サンプラー
     * @param bias 元のLODバイアスに加算するミップ単位の差分
     * @return 対応する所有補正サンプラー。非対象または失敗時は元のサンプラー
     */
    GpuSampler resolve(GpuSampler original, float bias) {
        return resolve(original,bias,false);
    }

    /**
     * Enhancedの対応モード向けに、元サンプラー記述を維持した解像度差補正を解決する。
     *
     * @param original 設定を基準にする共有元サンプラー
     * @param bias 元のLODバイアスに加算するミップ単位の差分
     * @return Enhancedの解像度LOD差分を反映した所有サンプラー。非対象時は元のサンプラー
     */
    GpuSampler resolveEnhancedFootprint(GpuSampler original,float bias) {
        return resolve(original,bias,preserveSourceDescriptor);
    }

    /**
     * 元サンプラーと補正条件を確認し、対応する専用サンプラーを生成または再利用して返す。
     *
     * 描画スレッドから呼び出す。
     *
     * @param original 設定を基準にする共有元サンプラー
     * @param bias 元のLODバイアスに加算するミップ単位の差分
     * @param enhancedFootprint Enhancedの解像度差分を許可する経路の場合はtrue
     * @return 対応する所有補正サンプラー。非対象または失敗時は元のサンプラー
     */
    private GpuSampler resolve(GpuSampler original, float bias, boolean enhancedFootprint) {
        RenderSystem.assertOnRenderThread();
        if (failed || !(original instanceof GlSampler gl) || gl.isClosed()
                || !(validBias(bias) || enhancedFootprint && validEnhancedFootprintBias(bias)
                    || minimumAnisotropy == 8 && bias == 0)) return original;
        // 同じ非対応サンプラーを毎回GLへ問い合わせないよう、除外した元オブジェクトと設定を記憶する。
        if (preserveSourceDescriptor && excludedSource == gl && excludedDescriptor.matches(original)) return original;
        try {
            if (descriptor == null || !descriptor.matches(original)
                    || (minimumAnisotropy == 8 || preserveSourceDescriptor) && source != gl) {
                if (minimumAnisotropy == 8 && (!GL.getCapabilities().GL_EXT_texture_filter_anisotropic
                        && !GL.getCapabilities().GL_ARB_texture_filter_anisotropic
                        || GL33C.glGetFloat(0x84FF) < 8)) {
                    failed = true;
                    TitaniumClient.LOGGER.warn("[Titanium] AF8 unavailable; original SOLID sampler retained");
                    return original;
                }
                // アドレス・フィルター・AF・最大LODを元サンプラーから引き継ぎ、ミップを使わない入力は除外する。
                Descriptor next = Descriptor.from(original);
                if (!next.mipmapped()) return original;
                if (preserveSourceDescriptor) {
                    int min = GL33C.glGetSamplerParameteri(gl.getId(), GL33C.GL_TEXTURE_MIN_FILTER);
                    if (min != GL33C.GL_NEAREST_MIPMAP_NEAREST && min != GL33C.GL_NEAREST_MIPMAP_LINEAR
                            && min != GL33C.GL_LINEAR_MIPMAP_NEAREST && min != GL33C.GL_LINEAR_MIPMAP_LINEAR) {
                        excludedSource = gl;
                        excludedDescriptor = next;
                        return original;
                    }
                }
                // 共有サンプラーを直接変更せず、Titanium所有の複製へ補正を適用する。
                GpuSampler replacement = RenderSystem.getDevice().createSampler(
                        next.u(), next.v(), next.min(), next.mag(),
                        Math.max(next.anisotropy(), minimumAnisotropy), next.maxLod());
                if (!(replacement instanceof GlSampler owned)) {
                    replacement.close();
                    throw new IllegalStateException("Terrain detail compensation requires OpenGL");
                }
                float baseBias = 0;
                try {
                    if (minimumAnisotropy == 8 || preserveSourceDescriptor) {
                        // Creation-only GL reads. Preserve Iris's real mip/filter descriptor,
                        // not merely the CPU approximation exposed by GpuSampler.
                        copyDescriptor(gl, owned, minimumAnisotropy);
                        baseBias = GL33C.glGetSamplerParameterf(gl.getId(), GL33C.GL_TEXTURE_LOD_BIAS);
                    }
                } catch (RuntimeException error) { owned.close(); throw error; }
                release();
                sampler = owned;
                descriptor = next;
                sourceBias = baseBias;
                appliedBias = 0;
                creations++;
            }
            // 補正値が変わった場合だけ元のLODバイアスへ差分を加え、描画ごとのGL設定更新を抑える。
            if (Float.floatToIntBits(appliedBias) != Float.floatToIntBits(bias)) {
                GL33C.glSamplerParameterf(sampler.getId(), GL33C.GL_TEXTURE_LOD_BIAS, sourceBias + bias);
                appliedBias = bias;
                biasWrites++;
            }
            source = gl;
            return sampler;
        } catch (RuntimeException error) {
            failed = true;
            TitaniumClient.LOGGER.warn("[Titanium] Terrain detail compensation disabled; original sampler retained", error);
            return original;
        }
    }

    /**
     * 元サンプラーの実GLパラメーターを所有サンプラーへ複製し、必要な最小AFを反映する。
     *
     * @param source 設定の基準となる借用元GLサンプラー
     * @param owned Titaniumが生成・解放を所有するGLサンプラー
     * @param minimumAnisotropy 専用サンプラーで要求する最低AF。1または8
     */
    private static void copyDescriptor(GlSampler source, GlSampler owned, int minimumAnisotropy) {
        for (int p : new int[]{GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_TEXTURE_WRAP_R,
                GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_TEXTURE_MAG_FILTER,
                GL33C.GL_TEXTURE_COMPARE_MODE, GL33C.GL_TEXTURE_COMPARE_FUNC})
            GL33C.glSamplerParameteri(owned.getId(), p, GL33C.glGetSamplerParameteri(source.getId(), p));
        for (int p : new int[]{GL33C.GL_TEXTURE_MIN_LOD, GL33C.GL_TEXTURE_MAX_LOD, GL33C.GL_TEXTURE_LOD_BIAS})
            GL33C.glSamplerParameterf(owned.getId(), p, GL33C.glGetSamplerParameterf(source.getId(), p));
        try (var stack = MemoryStack.stackPush()) {
            var border = stack.mallocFloat(4);
            GL33C.glGetSamplerParameterfv(source.getId(), GL33C.GL_TEXTURE_BORDER_COLOR, border);
            GL33C.glSamplerParameterfv(owned.getId(), GL33C.GL_TEXTURE_BORDER_COLOR, border);
        }
        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic
                || GL.getCapabilities().GL_ARB_texture_filter_anisotropic)
            GL33C.glSamplerParameterf(owned.getId(), 0x84FE,
                    Math.max(minimumAnisotropy, GL33C.glGetSamplerParameterf(source.getId(), 0x84FE)));
    }

    // CPU-side ownership evidence. GL descriptor reads are creation-only, never per draw.
    public GlSampler source() { return source; }
    public GlSampler owned() { return sampler; }
    public float appliedBias() { return appliedBias; }
    public int creations() { return creations; }
    public int releases() { return releases; }
    public int biasWrites() { return biasWrites; }

    /**
     * 現在の所有サンプラーを解放し、解放カウンターを更新する。
     */
    private void release() {
        if (sampler == null) return;
        sampler.close();
        sampler = null;
        releases++;
    }

    /**
     * 所有するリソースと保留状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        release();
        descriptor = null;
        source = null;
        excludedSource = null;
        excludedDescriptor = null;
        if (creations != 0) TitaniumClient.LOGGER.info(
                "[Titanium] terrain detail sampler closed: creations={}, releases={}, biasWrites={}",
                creations, releases, biasWrites);
    }
}
