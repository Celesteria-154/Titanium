package celeste.titanium.client.debug;

import celeste.titanium.client.TitaniumClient;
import celeste.titanium.client.config.DebugOverlayMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

public final class DlssDebugOverlay {
    private static final int LEFT = 2;
    private static final int TOP = 2;
    private static final int LINE_HEIGHT = 9;
    private static final int BACKGROUND = -1873784752;
    private static final int TEXT = -2039584;
    private static long normalExtractions;
    private static long f3Extractions;

    private DlssDebugOverlay() {
    }

    /**
     * HUD抽出時に設定された詳細度のDLSS表示を構築し、GUI描画情報へ追加する。
     *
     * @param graphics HUD描画情報を登録するGUI抽出コンテキスト
     */
    public static void extractNormal(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        DebugOverlayMode mode = TitaniumClient.config().debugOverlay();
        if (mode == DebugOverlayMode.OFF || client.gui.hud.isHidden()
                || client.debugEntries.isOverlayVisible()) return;
        var target = client.gameRenderer.mainRenderTarget();
        List<String> lines = DlssDebugText.normalLines(
                TitaniumClient.pipeline().debugState(target.width, target.height), mode);
        if (lines.isEmpty()) return;
        graphics.nextStratum();
        drawLines(graphics, lines, mode == DebugOverlayMode.DETAILED);
        normalExtractions++;
    }

    /**
     * F3画面へ渡すDLSS基本情報の行一覧を生成する。
     *
     * @return F3へ追加する基本状態の表示行
     */
    public static List<String> f3Lines() {
        Minecraft client = Minecraft.getInstance();
        var target = client.gameRenderer.mainRenderTarget();
        List<String> lines = DlssDebugText.f3Lines(
                TitaniumClient.pipeline().debugState(target.width, target.height));
        f3Extractions++;
        return lines;
    }

    public static long normalExtractions() {
        return normalExtractions;
    }

    public static long f3Extractions() {
        return f3Extractions;
    }

    /**
     * 行一覧を表示領域に合わせて配置し、必要に応じて二列へ分割する。
     *
     * @param graphics HUD描画情報を登録するGUI抽出コンテキスト
     * @param lines 順に表示するテキスト行
     * @param detailed 詳細オーバーレイの配置を使用する場合はtrue
     */
    private static void drawLines(GuiGraphicsExtractor graphics, List<String> lines, boolean detailed) {
        if (detailed) {
            int secondColumn = lines.indexOf("Temporal");
            if (secondColumn > 0) {
                List<String> left = lines.subList(0, secondColumn);
                int right = LEFT + columnWidth(left) + 12;
                drawColumn(graphics, left, LEFT);
                drawColumn(graphics, lines.subList(secondColumn, lines.size()), right);
                return;
            }
        }
        drawColumn(graphics, lines, LEFT);
    }

    private static int columnWidth(List<String> lines) {
        Minecraft client = Minecraft.getInstance();
        return lines.stream().mapToInt(client.font::width).max().orElse(0);
    }

    /**
     * 指定した左端位置へ各行を背景付きで描画する。
     *
     * @param graphics HUD描画情報を登録するGUI抽出コンテキスト
     * @param lines 順に表示するテキスト行
     * @param left 列を描画するGUI上の左端位置
     */
    private static void drawColumn(GuiGraphicsExtractor graphics, List<String> lines, int left) {
        Minecraft client = Minecraft.getInstance();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int top = TOP + LINE_HEIGHT * i;
            int width = client.font.width(line);
            graphics.fill(left - 1, top - 1, left + width + 1, top + LINE_HEIGHT - 1, BACKGROUND);
        }
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(client.font, lines.get(i), left, TOP + LINE_HEIGHT * i, TEXT, false);
        }
    }
}
