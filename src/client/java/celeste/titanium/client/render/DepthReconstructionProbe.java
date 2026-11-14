package celeste.titanium.client.render;

import celeste.titanium.client.TitaniumClient;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 同一フレームの深度読み戻しを再利用し、座標復元と再投影の誤差を検証する。 */
final class DepthReconstructionProbe {
    private final DepthStatistics.Endpoint clearEndpoint;
    private boolean finished;
    private boolean passed;
    private String diagnostics = "depth reconstruction pending";

    DepthReconstructionProbe() {
        String configured = System.getProperty("titanium.depthReconstruction.expectedClear", "");
        clearEndpoint = configured.isBlank() ? DepthStatistics.Endpoint.NONE
                : DepthStatistics.Endpoint.valueOf(configured.toUpperCase(Locale.ROOT));
    }

    /**
     * 確定済みパケットと深度の全ピクセルを再投影し、誤差統計と診断画像を保存する。
     *
     * @param packet 同じ描画に使用した確定済み入力パケット
     * @param depth 入力と同じフレームの深度floatの読み戻し
     */
    void analyze(FrameInputPacket packet, ByteBuffer depth) {
        if (finished) return;
        try {
            // 入力パケットと同じ深度をCPUで再投影し、復元誤差の統計と画像を出力する。
            DepthReconstructionStatistics statistics =
                    DepthReconstructionStatistics.analyze(depth, packet, clearEndpoint);
            Path directory = Path.of("screenshots").toAbsolutePath();
            Files.createDirectories(directory);
            Path depthPath = directory.resolve("titanium-p6a-depth.png");
            Path errorPath = directory.resolve("titanium-p6a-roundtrip-error.png");
            if (!ImageIO.write(statistics.depthImage(), "png", depthPath.toFile())
                    || !ImageIO.write(statistics.errorImage(), "png", errorPath.toFile())) {
                throw new IOException("PNG writer unavailable");
            }
            diagnostics = "sequence=" + packet.sequence() + ";clear=" + clearEndpoint + ";"
                    + statistics.summary() + ";depthImage=" + depthPath + ";errorImage=" + errorPath;
            passed = statistics.passed();
            finished = true;
            if (passed) {
                TitaniumClient.LOGGER.info("[Titanium/P6A] depth reconstruction PASS; {}", diagnostics);
            } else {
                TitaniumClient.LOGGER.error("[Titanium/P6A] depth reconstruction FAIL; {}", diagnostics);
            }
        } catch (RuntimeException | IOException error) {
            finished = true;
            passed = false;
            diagnostics = error.getClass().getSimpleName() + ": " + error.getMessage();
            TitaniumClient.LOGGER.error("[Titanium/P6A] depth reconstruction failed", error);
        }
    }

    boolean finished() {
        return finished;
    }

    boolean passed() {
        return finished && passed;
    }

    String diagnostics() {
        return diagnostics;
    }
}
