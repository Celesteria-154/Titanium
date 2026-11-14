package celeste.titanium.client.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 明示的に指定されたNGXランタイムの形式と固定ハッシュを読み込み前に検証する。 */
final class NgxRuntime {
    static final String EXPECTED_SHA256 =
            "3975567b8943c53acce397f2b72380092f84f162d00b0d2c7d08a1025c563983";
    static final Path PRODUCT_RELATIVE_PATH = Path.of("titanium", "dlss", "310.9.1", "nvngx_dlss.dll");

    private NgxRuntime() { }

    private static Path directory;

    /**
     * 明示された診断ランタイムがある場合に、そのDLLを読み込み前に検証する。
     */
    static void validateIfRequested() throws LoadException {
        if (!Boolean.getBoolean("titanium.ngxFrameProbe")) return;
        String configured = System.getProperty("titanium.ngxRuntime", "");
        if (configured.isBlank()) throw new LoadException("Missing titanium.ngxRuntime for P9 probe");
        validate(Path.of(configured).toAbsolutePath().normalize());
    }

    static void validateProduct(Path gameDirectory) throws LoadException {
        if (gameDirectory == null) throw new LoadException("Missing game directory for product DLSS runtime");
        validate(gameDirectory.toAbsolutePath().normalize().resolve(PRODUCT_RELATIVE_PATH));
    }

    /**
     * 指定されたDLSS DLLのサイズと固定ハッシュを検証する。
     *
     * @param runtime 検証するDLSSランタイムのDLLパス
     */
    private static void validate(Path runtime) throws LoadException {
        directory = null;
        if (!Files.isRegularFile(runtime) || !runtime.getFileName().toString().equalsIgnoreCase("nvngx_dlss.dll")) {
            throw new LoadException("Invalid fixed NGX runtime: " + runtime);
        }
        String actual;
        try {
            actual = sha256(runtime);
        } catch (IOException error) {
            throw new LoadException("Unable to hash fixed NGX runtime: " + runtime, error);
        }
        if (!MessageDigest.isEqual(EXPECTED_SHA256.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new LoadException("NGX runtime SHA-256 mismatch: " + actual);
        }
        directory = runtime.getParent();
    }

    static String directory() {
        return directory == null ? "" : directory.toString();
    }

    static final class LoadException extends IOException {
        LoadException(String message) { super(message); }
        LoadException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * 入力の全バイトからSHA-256を計算する。
     *
     * @param file ハッシュ検証対象のDLLファイル
     * @return DLL全体のSHA-256を表す16進文字列
     */
    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
