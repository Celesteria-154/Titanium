package celeste.titanium.client.nativebridge;

import celeste.titanium.client.TitaniumClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** JavaとWindowsネイティブ間のJNI窓口。DLLの検証・読み込みと共有リソース操作を管理する。 */
public final class NativeBridge {
    private static final String RESOURCE = "/natives/windows-x86_64/titanium_native.dll";
    private static boolean loaded;
    private static NativeCapabilities capabilities;

    private NativeBridge() { }

    /**
     * Windows x86-64の実行条件と同梱DLLを確認し、JNI・D3D11を一度だけ初期化する。
     *
     * @return ネイティブ初期化の結果、採用GPUの識別情報と診断文
     */
    public static synchronized NativeCapabilities initialize() {
        if (capabilities != null) return capabilities;
        if (!System.getProperty("os.name", "").startsWith("Windows")
                || !System.getProperty("os.arch", "").equals("amd64")) {
            return capabilities = failure(NativeError.UNSUPPORTED_PLATFORM, "Windows x86_64 required");
        }
        try {
            NgxRuntime.validateIfRequested();
            Path gameDirectory = FabricLoader.getInstance().getGameDir();
            Path directory = gameDirectory.resolve("titanium/native/")
                    .resolve(FabricLoader.getInstance().getModContainer("titanium").orElseThrow()
                            .getMetadata().getVersion().getFriendlyString()).resolve("windows-x86_64");
            // 補助DLLを先に検証・配置し、MODのバージョン別ディレクトリからJNIライブラリを読み込む。
            BundledDlssAssets.prepare(gameDirectory, directory);
            Path library = extract(directory);
            System.load(library.toAbsolutePath().toString());
            loaded = true;
            Path streamline = Path.of(System.getProperty("titanium.streamlineDir",
                    directory.resolve("streamline/2.14.1").toString())).toAbsolutePath();
            NativeError result = NativeError.fromCode(nativeInitialize(streamline.toString()));
            // ネイティブ側の固定配列をJavaの能力情報へ変換し、初期化結果を以後の呼び出しで共有する。
            String[] fields = nativeGetCapabilities();
            capabilities = new NativeCapabilities(result, Boolean.parseBoolean(fields[0]), fields[1],
                    number(fields[2]), number(fields[3]), number(fields[4]), number(fields[5]), fields[6],
                    number(fields[7]), number(fields[8]), (int) number(fields[9]),
                    (int) number(fields[10]), fields[11]);
        } catch (NgxRuntime.LoadException exception) {
            capabilities = failure(NativeError.NGX_RUNTIME_INVALID, exception.toString());
        } catch (IOException exception) {
            capabilities = failure(NativeError.NATIVE_EXTRACTION_FAILED, exception.toString());
        } catch (UnsatisfiedLinkError | RuntimeException exception) {
            capabilities = failure(NativeError.NATIVE_LOAD_FAILED, exception.toString());
        }
        return capabilities;
    }

    /**
     * 初期化済みネイティブ所有者を終了し、Java側の読み込み状態を更新する。
     */
    public static synchronized void shutdown() {
        if (loaded && capabilities != null && capabilities.initialized()) {
            NativeError result = NativeError.fromCode(nativeShutdown());
            loaded = false;
            TitaniumClient.LOGGER.info("[Titanium/Native] shutdown={}", result);
        }
    }

    /**
     * 単一カラーの相互運用診断に使う入力・出力をD3D11で生成し、指定したGL名へ登録する。
     *
     * @param inputGlName 単一カラー共有入力へ登録するGL名
     * @param outputGlName 単一カラー共有出力へ登録するGL名
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError createFrameTextures(int inputGlName, int outputGlName,
            int width, int height) {
        return ready() ? NativeError.fromCode(nativeCreateFrameTextures(inputGlName, outputGlName, width, height))
                : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    /**
     * 単一カラー診断の共有入力をGLの書き込み用にロックする。
     *
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError lockFrameInput() {
        return ready() ? NativeError.fromCode(nativeLockFrameInput()) : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    /**
     * 単一カラー診断の共有入力をD3D11へ返す。
     *
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError unlockFrameInput() {
        return ready() ? NativeError.fromCode(nativeUnlockFrameInput()) : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    /**
     * 単一カラー診断の共有出力をGLの読み取り用にロックする。
     *
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError lockFrameOutput() {
        return ready() ? NativeError.fromCode(nativeLockFrameOutput()) : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    /**
     * 単一カラー診断の共有出力をD3D11へ返す。
     *
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError unlockFrameOutput() {
        return ready() ? NativeError.fromCode(nativeUnlockFrameOutput()) : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    /**
     * D3D11側で単一カラー診断の入力を出力へコピーする。
     *
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError consumeFrameTexture() {
        return ready() ? NativeError.fromCode(nativeConsumeFrameTexture()) : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    /**
     * 単一カラー診断のWGL登録とD3D11テクスチャを解放する。
     *
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError releaseFrameTexture() {
        return ready() ? NativeError.fromCode(nativeReleaseFrameTexture()) : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    public static synchronized String frameDiagnostics() {
        return ready() ? nativeGetFrameDiagnostics() : "Native bridge unavailable";
    }

    /**
     * 同じ寸法のカラー・深度・MVをD3D11で生成し、3個のGL名へ共有登録する。
     *
     * @param colorGlName 共有カラーへ登録するGLテクスチャ名
     * @param depthGlName 共有深度へ登録するGLテクスチャ名
     * @param motionGlName 共有MVへ登録するGLテクスチャ名
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param validationEnabled 追加のGPU読み戻し診断を生成する場合はtrue
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError createFrameInterop(int colorGlName, int depthGlName, int motionGlName,
            int width, int height, boolean validationEnabled) {
        return ready() ? NativeError.fromCode(nativeCreateFrameInterop(
                colorGlName, depthGlName, motionGlName, width, height, validationEnabled))
                : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    /**
     * カラー・深度・MVの共有入力をまとめてGL所有へ移す。
     *
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError lockFrameInterop() {
        return ready() ? NativeError.fromCode(nativeLockFrameInterop()) : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    /**
     * 指定フレームのカラー・深度・MVの小領域を、明示的な診断条件で読み戻す。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param x 読み戻す領域の左端。ピクセル単位
     * @param topY 左上原点で指定した読み戻し領域の上端。ピクセル単位
     * @param size 読み戻す正方形領域の一辺のピクセル数
     * @return 1ピクセル16バイトのRGBA8・深度F32・MV F32×2を順に格納した配列
     */
    public static synchronized byte[] readFrameInputRoi(long sequence, int x, int topY, int size) {
        if (!InputReadbackPolicy.allows(Boolean.getBoolean("titanium.qualityNormalClient"),
                Boolean.getBoolean("titanium.qualityJitterCycle"), Boolean.getBoolean("titanium.qualityFixedMotion"),
                Boolean.getBoolean("titanium.qualityMotionTransfer"))
                && !InputReadbackPolicy.allowsScissorDiagnostic(Boolean.getBoolean("titanium.performanceNormalClient"),
                    FabricLoader.getInstance().isModLoaded("titanium-test"),System.getProperty("titanium.performanceInputScissorAudit","")))
            throw new IllegalStateException("D3D input readback requires an isolated quality transfer or test-mod scissor audit");
        if (!ready()) throw new IllegalStateException("Native bridge unavailable");
        byte[] result = nativeReadFrameInputRoi(sequence, x, topY, size);
        if (result == null) throw new IllegalStateException("D3D input ROI state/sequence/read failed");
        return result;
    }

    private static native byte[] nativeReadFrameInputRoi(long sequence, int x, int topY, int size);

    /**
     * GLから返却済みの同一シーケンスのDLSS出力から、診断用の小領域を読み戻す。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param x 読み戻す領域の左端。ピクセル単位
     * @param topY 左上原点で指定した読み戻し領域の上端。ピクセル単位
     * @param size 読み戻す正方形領域の一辺のピクセル数
     * @return 返却済み出力の小領域をRGBA8で格納した配列
     */
    public static synchronized byte[] readReturnedOutputRoi(long sequence, int x, int topY, int size) {
        if (!(Boolean.getBoolean("titanium.qualityNormalClient")
                && Boolean.getBoolean("titanium.qualityFixedMotion")
                && Boolean.getBoolean("titanium.qualityMotionTransfer")
                && Boolean.getBoolean("titanium.qualityOutputTransfer")))
            throw new IllegalStateException("D3D output readback requires the isolated output transfer audit");
        if (!ready()) throw new IllegalStateException("Native bridge unavailable");
        byte[] result = nativeReadReturnedOutputRoi(sequence, x, topY, size);
        if (result == null) throw new IllegalStateException("D3D output ROI state/sequence/read failed");
        return result;
    }

    private static native byte[] nativeReadReturnedOutputRoi(long sequence, int x, int topY, int size);

    /**
     * 入力コピー完了後に共有所有権をD3D11へ返し、フレーム番号とサンプル情報を確定する。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param reset この入力で時間方向履歴を開始し直す場合はtrue
     * @param jitterX 入力ピクセル単位のX方向ジッター
     * @param jitterY 入力ピクセル単位のY方向ジッター
     * @param phase サンプル周期内の0始まりの位相
     * @param phaseCount サンプル列を繰り返すまでの位相数
     * @param jitterEnabled この入力でジッターが有効な場合はtrue
     * @param validationKind 読み戻し診断の種類。0は診断なし
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError unlockFrameInterop(long sequence, boolean reset,
            double jitterX, double jitterY, int phase, int phaseCount, boolean jitterEnabled, int validationKind) {
        return ready() ? NativeError.fromCode(nativeUnlockFrameInterop(
                sequence, reset, jitterX, jitterY, phase, phaseCount, jitterEnabled, validationKind))
                : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    public static synchronized NativeError pollFrameInteropValidation() {
        return ready() ? NativeError.fromCode(nativePollFrameInteropValidation())
                : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    public static synchronized boolean frameInteropValidationPassed() {
        return ready() && nativeFrameInteropValidationPassed();
    }

    /**
     * 入力の共有登録と、D3D11側のカラー・深度・MVを解放する。
     *
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError releaseFrameInterop() {
        return ready() ? NativeError.fromCode(nativeReleaseFrameInterop()) : NativeError.FRAME_TEXTURE_UNAVAILABLE;
    }

    public static synchronized String frameInteropDiagnostics() {
        return ready() ? nativeGetFrameInteropDiagnostics() : "Native bridge unavailable";
    }

    public static synchronized boolean ngxCompiled() {
        return ready() && nativeNgxCompiled();
    }

    /**
     * 製品ランタイムとネイティブの利用条件を確認し、開始可能かおよび退避理由を返す。
     *
     * @param gameDirectory Minecraftが指定したゲームディレクトリ
     * @return 製品DLSSの利用可否と、利用できない場合の理由
     */
    public static synchronized DlssNativeCapability productDlssCapability(Path gameDirectory) {
        NativeCapabilities nativeCapabilities = initialize();
        if (!nativeCapabilities.initialized()) {
            return DlssNativeCapability.unavailable("Native bridge " + nativeCapabilities.result()
                    + ": " + nativeCapabilities.diagnostics());
        }
        if (!ngxCompiled()) {
            return DlssNativeCapability.unavailable("NGX support is not compiled into titanium_native.dll");
        }
        try {
            NgxRuntime.validateProduct(gameDirectory);
            return DlssNativeCapability.available();
        } catch (NgxRuntime.LoadException exception) {
            return DlssNativeCapability.unavailable(exception.getMessage());
        }
    }

    public static synchronized String ngxDiagnostics() {
        return ready() ? nativeGetNgxDiagnostics() : "Native bridge unavailable";
    }

    /**
     * 明示的な負荷診断の条件を確認し、ネイティブのGPUタイムスタンプ計測を切り替える。
     *
     * @param enabled 対象機能を有効にする場合はtrue
     */
    public static synchronized void configureNgxTiming(boolean enabled) {
        requireTimingConfiguration();
        if (!ready() || NativeError.fromCode(nativeConfigureNgxTiming(enabled)) != NativeError.OK)
            throw new IllegalStateException("NGX timing configuration failed");
    }

    public static synchronized void armNgxTiming(long sequence) {
        requireTimingConfiguration();
        if (!ready() || NativeError.fromCode(nativeArmNgxTiming(sequence)) != NativeError.OK)
            throw new IllegalStateException("NGX timing arm failed");
    }

    public static synchronized long[] pollNgxTiming() {
        requireTimingConfiguration();
        if (!ready()) throw new IllegalStateException("Native bridge unavailable");
        return nativePollNgxTiming();
    }

    private static void requireTimingConfiguration() {
        if (!DiagnosticTimingPolicy.allows(Boolean.getBoolean("titanium.performanceBenchmark"),
                Boolean.getBoolean("titanium.performanceNormalClient"),
                Boolean.getBoolean("titanium.performanceCostBreakdown"),
                FabricLoader.getInstance().isModLoaded("titanium-test")))
            throw new IllegalStateException("NGX timing requires the isolated performance cost run");
    }

    private static native int nativeConfigureNgxTiming(boolean enabled);
    private static native int nativeArmNgxTiming(long sequence);
    private static native long[] nativePollNgxTiming();

    /**
     * 検証済みDLSSディレクトリを指定してNGXを初期化する。
     *
     * @param logDirectory NGXがログを保存するディレクトリ
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError initializeNgx(String logDirectory) {
        return ready() ? NativeError.fromCode(nativeInitializeNgx(logDirectory, NgxRuntime.directory()))
                : NativeError.NGX_UNAVAILABLE;
    }

    /**
     * 入力・出力解像度、品質、深度方向とプリセットを固定してDLSSのFeatureを生成する。
     *
     * @param inputWidth NGX入力の幅。ピクセル単位
     * @param inputHeight NGX入力の高さ。ピクセル単位
     * @param outputWidth 出力幅。ピクセル単位
     * @param outputHeight 出力高さ。ピクセル単位
     * @param qualityMode JNIで使用するDLSS品質モードの固定整数値
     * @param depthInverted 遠側の深度が0になる反転深度の場合はtrue
     * @param validationEnabled 追加のGPU読み戻し診断を生成する場合はtrue
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError createNgxFeature(int inputWidth, int inputHeight,
            int outputWidth, int outputHeight, int qualityMode, boolean depthInverted,
            boolean validationEnabled) {
        return createNgxFeature(inputWidth, inputHeight, outputWidth, outputHeight, qualityMode,
                depthInverted, validationEnabled, 0);
    }

    /**
     * 入力・出力解像度、品質、深度方向とプリセットを固定してDLSSのFeatureを生成する。
     *
     * @param inputWidth NGX入力の幅。ピクセル単位
     * @param inputHeight NGX入力の高さ。ピクセル単位
     * @param outputWidth 出力幅。ピクセル単位
     * @param outputHeight 出力高さ。ピクセル単位
     * @param qualityMode JNIで使用するDLSS品質モードの固定整数値
     * @param depthInverted 遠側の深度が0になる反転深度の場合はtrue
     * @param validationEnabled 追加のGPU読み戻し診断を生成する場合はtrue
     * @param preset SDKへ送るモデルプリセットのHint
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError createNgxFeature(int inputWidth, int inputHeight,
            int outputWidth, int outputHeight, int qualityMode, boolean depthInverted,
            boolean validationEnabled, int preset) {
        if ("UNIT".equals(System.getProperty("titanium.qualityExposure", ""))) {
            if (!Boolean.getBoolean("titanium.qualityNormalClient")
                    || !Boolean.getBoolean("titanium.qualityFixedMotion")
                    || !Boolean.getBoolean("titanium.quality4kWindow")
                    || !Boolean.getBoolean("titanium.qualityContinuousRoi")
                    || qualityMode != 3 || preset != 11 || validationEnabled) {
                throw new IllegalStateException("Unit exposure requires isolated DLAA/K quality experiment");
            }
            NativeError result = ready() ? NativeError.fromCode(nativeCreateNgxLdrUnitExposureFeature(inputWidth, inputHeight,
                    outputWidth, outputHeight, depthInverted)) : NativeError.NGX_UNAVAILABLE;
            if (result == NativeError.OK) TitaniumClient.LOGGER.info("[Titanium/ResearchExposure] created {}", ngxDiagnostics());
            return result;
        }
        return ready() ? NativeError.fromCode(nativeCreateNgxFeature(inputWidth, inputHeight,
                outputWidth, outputHeight, qualityMode, depthInverted, validationEnabled, preset))
                : NativeError.NGX_UNAVAILABLE;
    }

    /**
     * 現在のNGX Featureと、その出力に関係する共有リソースを解放する。
     *
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError releaseNgxFeature() {
        NativeError result = ready() ? NativeError.fromCode(nativeReleaseNgxFeature()) : NativeError.NGX_UNAVAILABLE;
        if ("UNIT".equals(System.getProperty("titanium.qualityExposure", ""))
                && Boolean.getBoolean("titanium.qualityNormalClient")) {
            TitaniumClient.LOGGER.info("[Titanium/ResearchExposure] released result={} {}", result, ngxDiagnostics());
        }
        return result;
    }

    /**
     * 共有入力のシーケンス、履歴リセット、ジッターと時間を渡してDLSSを評価する。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @param reset この入力で時間方向履歴を開始し直す場合はtrue
     * @param jitterX 入力ピクセル単位のX方向ジッター
     * @param jitterY 入力ピクセル単位のY方向ジッター
     * @param frameTimeMilliseconds NGXへ渡す入力取得間隔。ミリ秒単位
     * @param validationKind 読み戻し診断の種類。0は診断なし
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError evaluateNgxFrame(long sequence, boolean reset,
            double jitterX, double jitterY, double frameTimeMilliseconds, int validationKind) {
        return ready() ? NativeError.fromCode(nativeEvaluateNgxFrame(sequence, reset,
                jitterX, jitterY, frameTimeMilliseconds, validationKind)) : NativeError.NGX_UNAVAILABLE;
    }

    /**
     * NGX所有の出力テクスチャを、Javaで確保したGL名へ共有登録する。
     *
     * @param glName 共有登録するGLテクスチャ名
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError registerNgxOutput(int glName) {
        return ready() ? NativeError.fromCode(nativeRegisterNgxOutput(glName)) : NativeError.NGX_UNAVAILABLE;
    }

    /**
     * 指定シーケンスのDLSS出力をGLが読み取れる所有状態へ移す。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError lockNgxOutput(long sequence) {
        return ready() ? NativeError.fromCode(nativeLockNgxOutput(sequence)) : NativeError.NGX_UNAVAILABLE;
    }

    /**
     * GLでの出力返却が完了したシーケンスを記録し、D3D11へ所有権を返す。
     *
     * @param sequence 同じ入力・評価・返却を識別する正のフレーム番号
     * @return 操作結果。OKで成功、その他の値で失敗理由を表す
     */
    public static synchronized NativeError unlockNgxOutput(long sequence) {
        return ready() ? NativeError.fromCode(nativeUnlockNgxOutput(sequence)) : NativeError.NGX_UNAVAILABLE;
    }

    public static synchronized NativeError pollNgxValidation() {
        return ready() ? NativeError.fromCode(nativePollNgxValidation()) : NativeError.NGX_UNAVAILABLE;
    }

    public static synchronized boolean ngxValidationPassed() {
        return ready() && nativeNgxValidationPassed();
    }

    public static synchronized boolean ngxOutputInteropPassed() {
        return ready() && nativeNgxOutputInteropPassed();
    }

    private static boolean ready() {
        return loaded && capabilities != null && capabilities.initialized();
    }

    /**
     * JAR内のJNIライブラリをハッシュで照合し、必要な場合だけバージョン別ディレクトリへ配置する。
     *
     * @param directory 配置先または診断ファイルのディレクトリ
     * @return 検証または展開が完了したJNIライブラリのパス
     */
    private static Path extract(Path directory) throws IOException {
        byte[] bytes;
        try (InputStream input = NativeBridge.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing native resource " + RESOURCE);
            bytes = input.readAllBytes();
        }
        Files.createDirectories(directory);
        Path target = directory.resolve("titanium_native.dll");
        // 展開済みDLLがJAR内と同じハッシュなら再利用し、毎起動のファイル書き換えを避ける。
        if (Files.isRegularFile(target) && MessageDigest.isEqual(sha256(bytes), sha256(Files.readAllBytes(target)))) {
            return target;
        }
        // DLLを一時ファイルへ書き切ってから置き換え、途中までの内容をSystem.loadへ渡さない。
        Path temporary = Files.createTempFile(directory, "titanium_native-", ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        TitaniumClient.LOGGER.info("[Titanium/Native] extracted {} SHA-256 {}", target,
                HexFormat.of().formatHex(sha256(bytes)));
        return target;
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long number(String value) { return value == null || value.isEmpty() ? 0 : Long.parseUnsignedLong(value); }
    private static NativeCapabilities failure(NativeError error, String message) {
        return new NativeCapabilities(error, false, "", 0, 0, 0, 0, "0", 0, 0, 0, 0, message);
    }

    private static native int nativeInitialize(String streamlineDirectory);
    private static native String[] nativeGetCapabilities();
    private static native int nativeCreateFrameTextures(int inputGlName, int outputGlName, int width, int height);
    private static native int nativeLockFrameInput();
    private static native int nativeUnlockFrameInput();
    private static native int nativeLockFrameOutput();
    private static native int nativeUnlockFrameOutput();
    private static native int nativeConsumeFrameTexture();
    private static native int nativeReleaseFrameTexture();
    private static native String nativeGetFrameDiagnostics();
    private static native int nativeCreateFrameInterop(int colorGlName, int depthGlName, int motionGlName,
                                                        int width, int height, boolean validationEnabled);
    private static native int nativeLockFrameInterop();
    private static native int nativeUnlockFrameInterop(long sequence, boolean reset, double jitterX, double jitterY,
                                                        int phase, int phaseCount, boolean jitterEnabled,
                                                        int validationKind);
    private static native int nativePollFrameInteropValidation();
    private static native boolean nativeFrameInteropValidationPassed();
    private static native int nativeReleaseFrameInterop();
    private static native String nativeGetFrameInteropDiagnostics();
    private static native boolean nativeNgxCompiled();
    private static native String nativeGetNgxDiagnostics();
    private static native int nativeInitializeNgx(String logDirectory, String featureDirectory);
    private static native int nativeCreateNgxFeature(int inputWidth, int inputHeight,
                                                     int outputWidth, int outputHeight,
                                                     int qualityMode, boolean depthInverted,
                                                     boolean validationEnabled, int preset);
    private static native int nativeReleaseNgxFeature();
    private static native int nativeCreateNgxLdrUnitExposureFeature(int inputWidth, int inputHeight,
            int outputWidth, int outputHeight, boolean depthInverted);
    private static native int nativeEvaluateNgxFrame(long sequence, boolean reset,
                                                     double jitterX, double jitterY,
                                                     double frameTimeMilliseconds, int validationKind);
    private static native int nativeRegisterNgxOutput(int glName);
    private static native int nativeLockNgxOutput(long sequence);
    private static native int nativeUnlockNgxOutput(long sequence);
    private static native int nativePollNgxValidation();
    private static native boolean nativeNgxValidationPassed();
    private static native boolean nativeNgxOutputInteropPassed();
    private static native int nativeShutdown();
}
