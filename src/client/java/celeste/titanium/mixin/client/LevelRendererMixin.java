package celeste.titanium.mixin.client;

import celeste.titanium.client.TitaniumClient;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private RenderTarget entityOutlineTarget;
    @Shadow @Final private GameRenderer gameRenderer;

    // Outline は framegraph 外の永続 target。World の実寸に同期し、異なる解像度の attachment 混在を防ぐ。
    /**
     * 永続するエンティティ輪郭ターゲットの寸法を、現在のワールド描画先へ同期する。
     *
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void titanium$resizeOutline(CallbackInfo ci) {
        RenderTarget main = gameRenderer.mainRenderTarget();
        if (entityOutlineTarget.width != main.width || entityOutlineTarget.height != main.height) {
            entityOutlineTarget.resize(main.width, main.height);
        }
    }

    /**
     * 実ワールドへ渡すモデルビューとカメラを、投影に対応するフレーム入力へ記録する。
     *
     * @param allocator Minecraftがフレーム内リソースを確保するためのアロケーター
     * @param delta 現在フレームのMinecraft時間・補間情報
     * @param renderOutline 元描画でエンティティ輪郭を描く場合はtrue
     * @param camera このワールド描画へ実際に渡すカメラ状態
     * @param modelView 同じワールド描画で使用するモデルビュー行列
     * @param fog 元描画へ引き継ぐ霧の設定またはuniform参照
     * @param fogColor 元描画へ引き継ぐ霧色
     * @param renderSky 元描画で空を描く場合はtrue
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void titanium$captureFrameInputs(GraphicsResourceAllocator allocator, DeltaTracker delta,
                                              boolean renderOutline, CameraRenderState camera,
                                              Matrix4fc modelView, GpuBufferSlice fog, Vector4f fogColor,
                                              boolean renderSky, CallbackInfo ci) {
        // 実際にワールドへ渡すモデルビューとカメラを、ジッター投影と組になる入力として取得する。
        TitaniumClient.pipeline().captureFrameWorldInputs(modelView, camera, gameRenderer.mainRenderTarget());
    }
}
