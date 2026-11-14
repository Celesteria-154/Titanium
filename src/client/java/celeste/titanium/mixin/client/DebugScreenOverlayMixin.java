package celeste.titanium.mixin.client;

import celeste.titanium.client.debug.DlssDebugOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
    /**
     * F3の元の表示行の先頭へDLSSの基本情報を追加する。
     *
     * @param lines 順に表示するテキスト行
     * @return DLSS基本情報を先頭へ加えたF3表示行
     */
    @ModifyArg(method = "extractRenderState", index = 1,
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;"
                            + "extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;Z)V"))
    private List<String> titanium$prependDlssLines(List<String> lines) {
        if (!Minecraft.getInstance().debugEntries.isOverlayVisible()) return lines;
        List<String> combined = new ArrayList<>(lines.size() + 5);
        combined.addAll(DlssDebugOverlay.f3Lines());
        combined.add("");
        combined.addAll(lines);
        return combined;
    }
}
