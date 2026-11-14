package celeste.titanium.client.nativebridge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/** ネイティブ読み込み前に、JAR同梱のDLSSランタイムを検証・配置する。 */
final class BundledDlssAssets {
    static final String MANIFEST_RESOURCE = "/META-INF/titanium/dlss-assets.properties";
    private static final int MAX_ASSETS = 16;

    private BundledDlssAssets() { }

    static boolean prepare(Path gameDirectory, Path nativeDirectory) throws IOException {
        return prepare(gameDirectory, nativeDirectory, BundledDlssAssets.class::getResourceAsStream);
    }

    /**
     * 同梱マニフェストを読み、管理領域内のDLLを検証し、不足する資材を配置する。
     *
     * 同梱マニフェストがない通常版はfalseを返す。配置済み資材はサイズとハッシュを照合し、不一致を失敗として報告する。
     *
     * @param gameDirectory Minecraftが指定したゲームディレクトリ
     * @param nativeDirectory バージョン別のJNI DLL配置ディレクトリ
     * @param resources JAR内資材を開くリソース供給元
     * @return 同梱資材を検証または配置できた場合はtrue、同梱マニフェストがない場合はfalse
     */
    static boolean prepare(Path gameDirectory, Path nativeDirectory, ResourceSource resources) throws IOException {
        // ランタイム同梱版だけ配置処理へ進む。通常版には同梱マニフェストがないため、そのまま戻る。
        byte[] manifestBytes = readOptional(resources, MANIFEST_RESOURCE);
        if (manifestBytes == null) return false;

        Properties manifest = new Properties();
        manifest.load(new ByteArrayInputStream(manifestBytes));
        String bundleId = required(manifest, "bundle.id");
        if (!bundleId.matches("[a-zA-Z0-9._-]{1,96}")) {
            throw new IOException("Invalid bundled DLSS asset ID: " + bundleId);
        }
        int count = parseCount(required(manifest, "asset.count"));

        Path gameRoot = gameDirectory.toAbsolutePath().normalize();
        Path managedRoot = gameRoot.resolve("titanium").normalize();
        Path nativeRoot = nativeDirectory.toAbsolutePath().normalize();
        requireManaged(nativeRoot, managedRoot, "native directory");
        rejectSymlinkPath(gameRoot, managedRoot);
        rejectSymlinkPath(managedRoot, nativeRoot);

        // バンドルIDは複数のMODバージョンで共有されるため、マーカーだけで個々の配置完了を判断しない。
        Path marker = managedRoot.resolve("runtime").resolve(bundleId).resolve("installed.properties");
        boolean installed = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS);
        for (int index = 0; index < count; index++) {
            Asset asset = parseAsset(manifest, index, gameRoot, nativeRoot, managedRoot);
            // 既存DLLは検証し、欠損分はJAR内の検証済み資材から補う。内容の異なる既存DLLは上書きしない。
            if (Files.exists(asset.target(), LinkOption.NOFOLLOW_LINKS)) {
                verifyFile(asset);
            } else {
                installAsset(resources, asset, managedRoot);
            }
        }

        if (!installed) {
            writeAtomic(marker, manifestBytes, managedRoot);
        }
        return true;
    }

    /**
     * 同梱マニフェストの一項目から資材の配置先・サイズ・ハッシュを復元して検証する。
     *
     * @param manifest 同梱資材の配置先・サイズ・ハッシュを宣言するプロパティ
     * @param index 対象のスロットまたは位相の番号
     * @param gameRoot 正規化済みのゲームディレクトリ
     * @param nativeRoot JNIと補助DLLを配置する正規化済み管理パス
     * @param managedRoot Titaniumが配置を管理するディレクトリの正規化済みパス
     * @return 読み取り先と配置先、検証用サイズ・SHA-256を持つ資材定義
     */
    private static Asset parseAsset(Properties manifest, int index, Path gameRoot, Path nativeRoot,
            Path managedRoot) throws IOException {
        String prefix = "asset." + index + ".";
        String resource = required(manifest, prefix + "resource");
        if (!resource.startsWith("/") || resource.contains("..") || resource.indexOf('\\') >= 0) {
            throw new IOException("Invalid bundled DLSS resource path: " + resource);
        }
        String scope = required(manifest, prefix + "scope");
        String relativeText = required(manifest, prefix + "target");
        Path relative = Path.of(relativeText);
        if (relative.isAbsolute() || relativeText.indexOf('\\') >= 0 || relativeText.contains("..")) {
            throw new IOException("Invalid bundled DLSS target path: " + relativeText);
        }
        Path base = switch (scope) {
            case "game" -> gameRoot;
            case "native" -> nativeRoot;
            default -> throw new IOException("Invalid bundled DLSS asset scope: " + scope);
        };
        // マニフェストから得た配置先を正規化し、Titaniumの管理領域を越えるパスを拒否する。
        Path target = base.resolve(relative).normalize();
        requireManaged(target, managedRoot, "asset target");
        long size;
        try {
            size = Long.parseLong(required(manifest, prefix + "size"));
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid bundled DLSS asset size at index " + index, invalid);
        }
        if (size < 1 || size > 256L * 1024L * 1024L) {
            throw new IOException("Bundled DLSS asset size is out of range: " + size);
        }
        String sha256 = required(manifest, prefix + "sha256").toLowerCase();
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid bundled DLSS SHA-256 at index " + index);
        }
        return new Asset(resource, target, size, sha256);
    }

    private static void verifyFile(Asset asset) throws IOException {
        if (!Files.isRegularFile(asset.target(), LinkOption.NOFOLLOW_LINKS)
                || Files.size(asset.target()) != asset.size()) {
            throw new IOException("Bundled DLSS asset size mismatch: " + asset.target());
        }
        String actual = sha256(Files.readAllBytes(asset.target()));
        if (!MessageDigest.isEqual(asset.sha256().getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Bundled DLSS asset SHA-256 mismatch: " + asset.target());
        }
    }

    /**
     * 管理領域内の一時ファイルへ全内容を書いた後、最終配置先へ移す。
     *
     * @param target 配置または範囲検証の対象パス
     * @param bytes 読み書きするバイト列
     * @param managedRoot Titaniumが配置を管理するディレクトリの正規化済みパス
     */
    private static void writeAtomic(Path target, byte[] bytes, Path managedRoot) throws IOException {
        Files.createDirectories(target.getParent());
        rejectSymlinkPath(managedRoot, target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, target);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    /**
     * JAR内の資材を検証しながら展開し、全バイトの検証成功後に最終配置先へ移す。
     *
     * @param resources JAR内資材を開くリソース供給元
     * @param asset 配置先・サイズ・ハッシュを検証する同梱資材
     * @param managedRoot Titaniumが配置を管理するディレクトリの正規化済みパス
     */
    private static void installAsset(ResourceSource resources, Asset asset, Path managedRoot) throws IOException {
        Files.createDirectories(asset.target().getParent());
        rejectSymlinkPath(managedRoot, asset.target().getParent());
        Path temporary = Files.createTempFile(asset.target().getParent(), asset.target().getFileName().toString(), ".tmp");
        boolean moved = false;
        try (InputStream input = resources.open(asset.resource());
                var output = Files.newOutputStream(temporary)) {
            if (input == null) throw new IOException("Missing bundled DLSS resource: " + asset.resource());
            // JAR内の資材を一時ファイルへ展開しながら検証し、完全なDLLだけを最終配置先へ移す。
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[64 * 1024];
            long size = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                size += read;
                if (size > asset.size()) {
                    throw new IOException("Bundled DLSS resource exceeds declared size: " + asset.resource());
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (size != asset.size() || !MessageDigest.isEqual(
                    asset.sha256().getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("Bundled DLSS JAR resource failed verification: " + asset.resource());
            }
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        try {
            try {
                Files.move(temporary, asset.target(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, asset.target());
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    /**
     * 管理領域から対象までの各階層を確認し、シンボリックリンク経由の領域逸脱を除外する。
     *
     * @param root 検証する管理領域の起点パス
     * @param target 配置または範囲検証の対象パス
     */
    private static void rejectSymlinkPath(Path root, Path target) throws IOException {
        if (root == null) throw new IOException("Bundled DLSS path has no root: " + target);
        Path current = root;
        Path relative = root.relativize(target);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(
                        current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException("Bundled DLSS path contains a reparse-like entry: " + current);
                }
            }
        }
    }

    /**
     * 正規化された対象パスがTitaniumの管理ディレクトリ配下であることを確認する。
     *
     * @param target 配置または範囲検証の対象パス
     * @param managedRoot Titaniumが配置を管理するディレクトリの正規化済みパス
     * @param label 表示欄または診断対象を識別する名前
     */
    private static void requireManaged(Path target, Path managedRoot, String label) throws IOException {
        if (!target.startsWith(managedRoot)) {
            throw new IOException("Bundled DLSS " + label + " escapes managed root: " + target);
        }
    }

    /**
     * マニフェストの資材数を整数へ変換し、許可された上限内であることを確認する。
     *
     * @param value 変換・検証・表示の対象値
     * @return 許容範囲を確認した同梱資材の項目数
     */
    private static int parseCount(String value) throws IOException {
        try {
            int count = Integer.parseInt(value);
            if (count < 1 || count > MAX_ASSETS) throw new NumberFormatException();
            return count;
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid bundled DLSS asset count: " + value, invalid);
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing bundled DLSS property: " + key);
        return value.trim();
    }

    private static byte[] readOptional(ResourceSource resources, String path) throws IOException {
        try (InputStream input = resources.open(path)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @FunctionalInterface
    interface ResourceSource {
        InputStream open(String path) throws IOException;
    }

    record Asset(String resource, Path target, long size, String sha256) { }
}
