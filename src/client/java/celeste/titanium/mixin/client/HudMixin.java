package celeste.titanium.mixin.client;

import celeste.titanium.client.debug.DlssDebugOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
    /**
     * 通常HUDの抽出後に、選択された詳細度のDLSS状態をGUIへ追加する。
     *
     * @param graphics HUD描画情報を登録するGUI抽出コンテキスト
     * @param deltaTracker 現在フレームのMinecraft時間・補間情報
     * @param ci 元メソッドの実行境界を通知するMixinコールバック
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void titanium$extractDlssOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        // 通常HUDの抽出が終わった位置へ、設定で選択されたCPU状態のデバッグ表示を追加する。
        DlssDebugOverlay.extractNormal(graphics);
    }
}
