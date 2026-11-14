package celeste.titanium.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 同一フレームの深度と入力履歴から、再利用可能なカメラMVのRG32Fテクスチャを生成する。 */
final class CameraMotionVectorProducer implements AutoCloseable {
    private static final int UNIFORM_BYTES = 416;
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withSampler("DepthSampler")
            .withUniform("MotionData", UniformType.UNIFORM_BUFFER)
            .build();
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("titanium", "pipeline/camera_motion_vector"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("titanium", "core/camera_motion_vector"))
            .withBindGroupLayout(LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RG32_FLOAT,
                    ColorTargetState.WRITE_COLOR))
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    private static final RenderPipeline IMAGE_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("titanium", "pipeline/image_motion_vector"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("titanium", "core/camera_motion_vector"))
            .withShaderDefine("TITANIUM_IMAGE_MOTION")
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("DepthSampler")
                    .withSampler("CurrentColorSampler").withSampler("PreviousColorSampler")
                    .withSampler("PreviousDepthSampler")
                    .withUniform("MotionData", UniformType.UNIFORM_BUFFER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RG32_FLOAT,
                    ColorTargetState.WRITE_COLOR))
            .withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();

    private final DepthStatistics.Endpoint clearEndpoint;
    private final boolean imageMotion;
    private GpuTexture sourceColor;
    private GpuTexture previousColor;
    private GpuTexture previousDepth;
    private GpuTextureView currentColorView;
    private GpuTextureView previousColorView;
    private GpuTextureView previousDepthView;
    private GpuSampler colorSampler;
    private GpuTexture sourceDepth;
    private GpuTexture motionTexture;
    private GpuTextureView depthView;
    private GpuTextureView motionView;
    private GpuSampler depthSampler;
    private GpuBuffer uniformBuffer;
    private int width;
    private int height;
    private long sequence;
    private long generated;
    private long resets;

    static CameraMotionVectorProducer configuredProbe() {
        String configured = System.getProperty("titanium.motionVector.expectedClear", "");
        DepthStatistics.Endpoint endpoint = configured.isBlank() ? DepthStatistics.Endpoint.NONE
                : DepthStatistics.Endpoint.valueOf(configured.toUpperCase(Locale.ROOT));
        return new CameraMotionVectorProducer(endpoint);
    }

    /**
     * 背景判定用の深度端点を保持し、画像推定を使う場合は独立診断の実行条件を確認する。
     *
     * @param clearEndpoint 背景として扱う深度の端点
     */
    CameraMotionVectorProducer(DepthStatistics.Endpoint clearEndpoint) {
        this.clearEndpoint = Objects.requireNonNull(clearEndpoint, "clearEndpoint");
        imageMotion = Boolean.getBoolean("titanium.debugImageMotion");
        if (imageMotion && (!Boolean.getBoolean("titanium.qualityNormalClient")
                || !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("titanium-test")))
            throw new IllegalStateException("Image-estimated motion requires the isolated quality harness");
    }

    String motionDescription() {
        return imageMotion ? "Image estimated (test)" : "Camera only";
    }

    DlssInputProfile.MotionSource motionSource() {
        return imageMotion ? DlssInputProfile.MotionSource.IMAGE_ESTIMATED_TEST : DlssInputProfile.MotionSource.CAMERA;
    }

    /**
     * 同一フレームの深度と前後入力からMVを生成する。履歴開始時は0で初期化する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param target 操作対象の描画先またはリソース
     * @param previous 参照する直前の確定済み入力パケット
     * @param current 解析対象の現在フレーム
     */
    void generate(RenderTarget target, FrameInputPacket previous, FrameInputPacket current) {
        RenderSystem.assertOnRenderThread();
        GpuTexture targetDepth = Objects.requireNonNull(target.getDepthTexture(), "World depth is unavailable");
        ensureResources(targetDepth, target.getColorTexture(), current.width(), current.height());
        // 履歴開始時は参照フレームがないためMVを0で埋め、連続したフレームだけ再投影する。
        boolean continuous = previous != null && current.continuousWith(previous);
        if (!continuous) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(motionTexture, new Vector4f(0.0F));
            resets++;
        } else {
            render(previous, current);
        }
        if (imageMotion) {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            // 次フレームは完了済みの不変入力を参照し、更新後のuniformや供給元を再取得しない。
            encoder.copyTextureToTexture(sourceColor, previousColor, 0, 0, 0, 0, 0, width, height);
            encoder.copyTextureToTexture(targetDepth, previousDepth, 0, 0, 0, 0, 0, width, height);
        }
        sequence = current.sequence();
        generated++;
    }

    /**
     * 前後の行列とカメラ位置差をuniformへ格納し、全画面パスでピクセル単位のMVを生成する。
     *
     * @param previous 参照する直前の確定済み入力パケット
     * @param current 解析対象の現在フレーム
     */
    private void render(FrameInputPacket previous, FrameInputPacket current) {
        if (clearEndpoint == DepthStatistics.Endpoint.NONE) {
            throw new IllegalStateException("Camera motion clear endpoint is not configured");
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        // ジッター付き投影の逆行列で現在の深度を復元し、未ジッターの前後投影で画面変位を計算する。
        Matrix4f inverseProjection = current.renderProjection().invert();
        Matrix4f inverseModelView = current.modelView().invert();
        float clear = clearEndpoint == DepthStatistics.Endpoint.ZERO ? 0.0F : 1.0F;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var builder = Std140Builder.onStack(stack, imageMotion ? UNIFORM_BYTES + 16 : UNIFORM_BYTES)
                    .putMat4f(inverseProjection)
                    .putMat4f(inverseModelView)
                    .putMat4f(current.modelView())
                    .putMat4f(current.unjitteredProjection())
                    .putMat4f(previous.modelView())
                    .putMat4f(previous.unjitteredProjection())
                    // カメラ位置差はワールド原点の移動を補う。画像上のサンプル位置と一緒にuniformへ格納する。
                    .putVec4((float) (current.cameraPosition().x - previous.cameraPosition().x),
                            (float) (current.cameraPosition().y - previous.cameraPosition().y),
                            (float) (current.cameraPosition().z - previous.cameraPosition().z), clear)
                    .putIVec4(width, height,
                            DepthClipRange.forPacket(current) == DepthClipRange.MINUS_ONE_TO_ONE ? 1 : 0,
                            imageMotion && sequence == previous.sequence() ? 1 : 0);
            if (imageMotion) builder.putVec4(
                    (float) (current.jitter().pixelX() - previous.jitter().pixelX()),
                    (float) (previous.jitter().pixelY() - current.jitter().pixelY()), 0, 0);
            ByteBuffer data = builder.get();
            encoder.writeToBuffer(uniformBuffer.slice(), data);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Titanium camera motion",
                motionView, Optional.empty())) {
            pass.setPipeline(imageMotion ? IMAGE_PIPELINE : PIPELINE);
            pass.bindTexture("DepthSampler", depthView, depthSampler);
            if (imageMotion) {
                pass.bindTexture("CurrentColorSampler", currentColorView, colorSampler);
                pass.bindTexture("PreviousColorSampler", previousColorView, colorSampler);
                pass.bindTexture("PreviousDepthSampler", previousDepthView, depthSampler);
            }
            pass.setUniform("MotionData", uniformBuffer);
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * 入力オブジェクトと寸法を確認し、必要な場合だけ描画・読み戻し用リソースを再生成する。
     *
     * @param requestedDepth 次のMV生成で参照する実深度
     * @param requestedColor 次のMV診断で参照する実入力色
     * @param requestedWidth 要求された入力幅。ピクセル単位
     * @param requestedHeight 要求された入力高さ。ピクセル単位
     */
    private void ensureResources(GpuTexture requestedDepth, GpuTexture requestedColor,
                                 int requestedWidth, int requestedHeight) {
        // 入力深度のオブジェクトまたは寸法が変わるまで、MVターゲットとuniformバッファを再利用する。
        if (requestedWidth == width && requestedHeight == height && motionTexture != null
                && sourceDepth == requestedDepth && (!imageMotion || sourceColor == requestedColor)) return;
        releaseResources();
        width = requestedWidth;
        height = requestedHeight;
        sourceDepth = requestedDepth;
        sourceColor = requestedColor;
        var device = RenderSystem.getDevice();
        motionTexture = device.createTexture(() -> "Titanium camera motion",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RG32_FLOAT, width, height, 1, 1);
        // The MV pass only attaches its own color target. The world depth is sampled, never attached/written.
        // FrameInputRuntime binds this producer to the same live resources at the synchronous pre-hand boundary.
        depthView = device.createTextureView(sourceDepth);
        motionView = device.createTextureView(motionTexture);
        depthSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, java.util.OptionalDouble.empty());
        uniformBuffer = device.createBuffer(() -> "Titanium camera motion uniforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, imageMotion ? UNIFORM_BYTES + 16 : UNIFORM_BYTES);
        if (imageMotion) {
            previousColor = device.createTexture(() -> "Titanium previous input color",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            previousDepth = device.createTexture(() -> "Titanium previous input depth",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.D32_FLOAT, width, height, 1, 1);
            currentColorView = device.createTextureView(sourceColor);
            previousColorView = device.createTextureView(previousColor);
            previousDepthView = device.createTextureView(previousDepth);
            colorSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, java.util.OptionalDouble.empty());
        }
    }

    /**
     * 生成番号の一致を確認し、所有するMVテクスチャを借用参照として返す。
     *
     * @param expectedSequence 取得する生成済み入力のフレーム番号
     * @return 同じ生成番号の借用MVテクスチャ。解放は生成器が担当する
     */
    GpuTexture motionTexture(long expectedSequence) {
        requireSequence(expectedSequence);
        return motionTexture;
    }

    /**
     * MVの生成に使った深度テクスチャを、同じ生成番号に対する借用参照として返す。
     *
     * @param expectedSequence 取得する生成済み入力のフレーム番号
     * @return 同じMV生成で参照した借用深度。解放は元のターゲット所有者が担当する
     */
    GpuTexture depthInput(long expectedSequence) {
        requireSequence(expectedSequence);
        return sourceDepth; // 同一フレームの利用が完了するまで借用する。解放は元の所有者が行う。
    }

    /**
     * 生成番号の一致を確認し、MVテクスチャのGL識別子を返す。
     *
     * @param expectedSequence 取得する生成済み入力のフレーム番号
     * @return 指定番号で生成したMVテクスチャのGL識別子
     */
    int motionGlId(long expectedSequence) {
        GpuTexture texture = motionTexture(expectedSequence);
        if (!(texture instanceof GlTexture glTexture)) {
            throw new IllegalStateException("Camera motion requires an OpenGL texture");
        }
        return glTexture.glId();
    }

    DepthStatistics.Endpoint clearEndpoint() {
        return clearEndpoint;
    }

    /**
     * 現在の実行状態、進行カウンター、直近の失敗理由を診断文字列へまとめる。
     *
     * @return 取得済みの実行状態、カウンターと直近の失敗理由を含む診断文字列
     */
    String diagnostics() {
        return "generated=" + generated + ";resets=" + resets + ";sequence=" + sequence
                + ";size=" + width + "x" + height
                + ";source=" + (imageMotion ? "camera+image-estimated" : "camera-only");
    }

    /**
     * 取得要求のフレーム番号が、生成済みのMV入力と一致することを確認する。
     *
     * @param expectedSequence 取得する生成済み入力のフレーム番号
     */
    private void requireSequence(long expectedSequence) {
        if (expectedSequence <= 0 || sequence != expectedSequence || motionTexture == null) {
            throw new IllegalStateException("Camera motion sequence mismatch: expected=" + expectedSequence
                    + ", actual=" + sequence);
        }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        releaseResources();
    }

    /**
     * 再生成または終了に備え、所有するテクスチャ・ビュー・バッファを解放する。
     */
    private void releaseResources() {
        if (currentColorView != null) currentColorView.close();
        if (previousColorView != null) previousColorView.close();
        if (previousDepthView != null) previousDepthView.close();
        if (previousColor != null) previousColor.close();
        if (previousDepth != null) previousDepth.close();
        if (colorSampler != null) colorSampler.close();
        currentColorView = previousColorView = previousDepthView = null;
        previousColor = previousDepth = sourceColor = null;
        colorSampler = null;
        if (uniformBuffer != null) uniformBuffer.close();
        if (depthSampler != null) depthSampler.close();
        if (motionView != null) motionView.close();
        if (depthView != null) depthView.close();
        if (motionTexture != null) motionTexture.close();
        uniformBuffer = null;
        depthSampler = null;
        motionView = null;
        depthView = null;
        motionTexture = null;
        sourceDepth = null;
        width = 0;
        height = 0;
        sequence = 0;
    }
}
