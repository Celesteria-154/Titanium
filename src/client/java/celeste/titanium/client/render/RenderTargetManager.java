package celeste.titanium.client.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.systems.RenderSystem;

/** MOD 所有の target だけを確保・破棄する。呼び出しは描画スレッドのフレーム境界に限定。 */
public final class RenderTargetManager implements AutoCloseable {
    private MainTarget worldTarget;

    /**
     * 要求寸法のワールド描画先を生成またはリサイズし、GPU確保後の実寸法を検証する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param size 準備するワールド描画先の整数寸法
     * @return 要求と実寸法の一致を確認した、所有するワールド描画先
     */
    public MainTarget prepare(RenderResolution size) {
        RenderSystem.assertOnRenderThread();
        if (worldTarget == null) {
            worldTarget = new MainTarget(size.width(), size.height());
        } else if (worldTarget.width != size.width() || worldTarget.height != size.height()) {
            worldTarget.resize(size.width(), size.height());
        }
        // MainTarget は VRAM 不足時に 854x480 へフォールバックするため、実寸を確認する。
        if (worldTarget.width != size.width() || worldTarget.height != size.height()) {
            throw new IllegalStateException("MainTarget allocation did not match requested resolution");
        }
        return worldTarget;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (worldTarget != null) {
            worldTarget.destroyBuffers();
            worldTarget = null;
        }
    }
}
