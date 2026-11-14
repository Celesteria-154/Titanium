package celeste.titanium.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** 任意の診断処理を接続する境界。キャプチャの実装は製品に含めない。 */
public interface DlssFrameObserver {
    void beforeTransfer(RenderTarget input, int motionTexture, FrameInputPacket packet);

    /**
     * 明示された色テクスチャと同一フレームのMVを、共有入力への転送直前に通知する。
     *
     * @param input 読み取り元のワールドターゲット
     * @param colourTexture 明示された入力色のGLテクスチャ識別子
     * @param motionTexture 同じ入力番号のMVを格納するGLテクスチャ識別子
     * @param packet 同じ描画に使用した確定済み入力パケット
     */
    default void beforeColourTransfer(RenderTarget input,int colourTexture,int motionTexture,FrameInputPacket packet){
        beforeTransfer(input,motionTexture,packet);
    }
    default void afterTransfer(FrameInputPacket packet) {}
    default void afterEvaluate(FrameInputPacket packet) {}

    /**
     * NGX評価が完了した入力パケットと、取得できる場合は評価へ渡したフレーム時間を通知する。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param frameTimeMilliseconds NGXへ渡す入力取得間隔。ミリ秒単位
     */
    default void afterEvaluate(FrameInputPacket packet, double frameTimeMilliseconds) {
        afterEvaluate(packet);
    }
    void afterReturn(RenderTarget output, FrameInputPacket packet);
}
