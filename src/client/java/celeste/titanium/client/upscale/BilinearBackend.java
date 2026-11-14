package celeste.titanium.client.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Objects;
import java.util.Optional;

/** 不透明な全面コピー。GUI を含む前フレームの色をブレンドしない。 */
public final class BilinearBackend {
    /**
     * 入力ワールド色を線形フィルター付きの全画面三角形で拡大し、出力先へ不透明にコピーする。
     *
     * 描画スレッドから呼び出す。
     *
     * @param input 読み取り元のワールドターゲット
     * @param output 再構成結果を返すターゲット
     */
    public void dispatch(RenderTarget input, RenderTarget output) {
        RenderSystem.assertOnRenderThread();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Titanium Bilinear", Objects.requireNonNull(output.getColorTextureView()), Optional.empty())) {
            // 全画面三角形で入力カラーを出力へ拡大し、線形フィルターをクランプして端のにじみを抑える。
            pass.setPipeline(RenderPipelines.TRACY_BLIT);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", Objects.requireNonNull(input.getColorTextureView()),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }
}
