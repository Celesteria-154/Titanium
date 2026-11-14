package celeste.titanium.client.render;

import celeste.titanium.client.TitaniumClient;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

/** 前フレームのGPU完了確認位置を調整する、任意の描画スケジューリング。 */
public final class GuiFramePacing {
    private static final GuiPacingFence FENCE = new GuiPacingFence();
    private GuiFramePacing() {}
    /**
     * 設定、ワールド、バックエンドとGUI可視状態から、ペーシング処理の適用可否を判定する。
     *
     * @param option GUIフレームペーシング設定が有効な場合はtrue
     * @param world ワールドが描画されている場合はtrue
     * @param openGl 現在の描画バックエンドがOpenGLの場合はtrue
     * @param hiddenHud F1などでHUDが非表示の場合はtrue
     * @param screen GUI画面が表示されている場合はtrue
     * @param overlay GUIオーバーレイが表示されている場合はtrue
     * @return ワールドのOpenGL描画中で、設定が有効かつGUIが表示される場合はtrue
     */
    public static boolean allowed(boolean option,boolean world,boolean openGl,boolean hiddenHud,boolean screen,boolean overlay) {
        return option && world && openGl && (!hiddenHud || screen || overlay);
    }
    private static boolean active() {
        if(!TitaniumClient.config().guiFramePacing())return false;
        var client=Minecraft.getInstance();
        return allowed(true,client.level!=null,"OpenGL".equals(RenderSystem.getDevice().getDeviceInfo().backendName()),
                client.gui.hud.isHidden(),client.gui.screen()!=null,client.gui.overlay()!=null);
    }
    /**
     * 同じGPUデバイスの前回送信フェンスをGUI直前で待機し、失敗した場合は機能を退避する。
     */
    public static void beforeGui() {
        if(!active()){FENCE.close();return;}
        if(FENCE.failed())return;
        // CommandEncoderは一時的なラッパーのため、送信列の所有者であるGpuDeviceを基準にする。
        FENCE.beforeGui(RenderSystem.getDevice());
        if(FENCE.failed())TitaniumClient.LOGGER.warn("[Titanium] GUI pacing bypassed after failure: {}",FENCE.failure());
    }
    /**
     * 現在のコマンド列へフェンスを挿入し、送信成功後に次フレームの待機対象として保持する。
     *
     * @param encoder 現在のコマンド列を管理するエンコーダー
     * @param original Mixin適用前の値または元の描画処理
     */
    public static void submit(CommandEncoder encoder,Runnable original) {
        if(!active()){FENCE.close();original.run();return;}
        if(FENCE.failed()){original.run();return;}
        com.mojang.blaze3d.buffers.GpuFence fence;
        // 送信前のコマンド列末尾にフェンスを置き、次フレームのGUI描画前に完了を確認する。
        try{fence=encoder.createFence();}
        catch(RuntimeException error){FENCE.fail(error);TitaniumClient.LOGGER.warn("[Titanium] GUI pacing capture bypassed",error);original.run();return;}
        boolean success=false;
        try{original.run();success=true;}
        // 送信成功時だけ次回の待機対象へ渡す。送信失敗時のフェンスはその場で解放する。
        finally{if(success)FENCE.submitted(RenderSystem.getDevice(),fence);else fence.close();}
    }
    public static void close(){FENCE.close();}
    public static GuiPacingFence.Snapshot snapshot(){return FENCE.snapshot();}
}
