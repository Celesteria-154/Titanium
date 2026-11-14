#pragma once

#include "D3D11Context.hpp"
#include "FrameColorEncoding.hpp"
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

struct ID3D11Device;
struct ID3D11DeviceContext;
struct ID3D11Texture2D;

namespace titanium {

class WglDxInterop;

// Stable Java/JNI IDs; do not reuse the legacy UltraQuality value.
enum class NgxQualityMode : int {
    Performance = 0, Quality = 1, UltraQuality = 2, Dlaa = 3,
    Balanced = 4, UltraPerformance = 5
};
enum class NgxRenderPreset : int { SdkDefault = 0, J = 10, K = 11, L = 12, M = 13 };

struct NgxOptimalSettings {
    unsigned width{};
    unsigned height{};
    unsigned minimumWidth{};
    unsigned minimumHeight{};
    unsigned maximumWidth{};
    unsigned maximumHeight{};
};

/** Direct NGXのセッションとFeatureを所有する。SDK未指定のビルドでは同じAPIの未対応実装を使用する。 */
class FrameNgxEvaluator {
public:
    FrameNgxEvaluator();
    ~FrameNgxEvaluator();
    FrameNgxEvaluator(const FrameNgxEvaluator&) = delete;
    FrameNgxEvaluator& operator=(const FrameNgxEvaluator&) = delete;

    bool compiledWithNgx() const;
    /**
     * 指定D3D11デバイスと検証済みランタイムのディレクトリでNGXを初期化する。
     * DLSSの利用可否も確認し、非対応時はドライバー条件を診断へ記録する。
     * @param device 入出力の共有テクスチャを所有するデバイス
     * @param context 同じdeviceに属する即時コンテキスト
     * @param logDirectory NGXログの保存先
     * @param featureDirectory 検証済みのnvngx_dlss.dllを含むディレクトリ
     * @return Okで利用可能。その他の値は初期化・利用条件の失敗を示す
     */
    NativeError initialize(ID3D11Device* device, ID3D11DeviceContext* context,
            const std::wstring& logDirectory, const std::wstring& featureDirectory);
    /**
     * 出力寸法と品質モードに対応するSDK推奨入力寸法・許容範囲を取得する。
     * @param settings 成功時に推奨値を書き込む出力引数
     * @return 推奨寸法を取得できた場合はOk
     */
    NativeError queryOptimalSettings(unsigned outputWidth, unsigned outputHeight,
            NgxQualityMode mode, NgxOptimalSettings& settings);
    /**
     * 一世代で固定する寸法・深度・露出・色形式を指定してDLSS Featureと出力を生成する。
     * 同じ世代に対して再生成する前にreleaseFeatureで旧共有状態を返す必要がある。
     * @param inputWidth 入力幅。ピクセル単位
     * @param inputHeight 入力高さ。ピクセル単位
     * @param outputWidth 再構成する出力幅。ピクセル単位
     * @param outputHeight 再構成する出力高さ。ピクセル単位
     * @param mode SDKに指定する品質モード
     * @param depthInverted 遠側の深度値が0の場合はtrue
     * @param validationEnabled 追加の出力読み戻し診断を確保する場合はtrue
     * @param preset SDKへ渡す公開プリセットHint
     * @param autoExposure SDKの自動露出を使用する場合はtrue
     * @param colorEncoding 呼び出し元が確認した入力色の意味と格納形式
     * @return Featureと出力の生成に成功した場合はOk
     */
    NativeError createFeature(unsigned inputWidth, unsigned inputHeight,
            unsigned outputWidth, unsigned outputHeight, NgxQualityMode mode, bool depthInverted,
            bool validationEnabled, NgxRenderPreset preset = NgxRenderPreset::SdkDefault,
            bool autoExposure = true, FrameColorEncoding colorEncoding = FrameColorEncoding::LdrUnorm);
    /**
     * D3D11所有の同一フレームのカラー・深度・MVを検証してDLSSを評価する。
     * 出力がGL所有の間は評価できない。入力テクスチャは呼び出し元が所有する。
     * @param color 同じ入力寸法のカラー
     * @param depth 線形化していない[0,1]深度。R32F
     * @param motion 現在から前フレームへの後方MV。RG32F、入力ピクセル単位
     * @param sequence 世代内で単調増加する正の入力番号
     * @param reset この評価で時間方向履歴を開始し直す場合はtrue
     * @param jitterX 入力ピクセル単位のX方向ジッター
     * @param jitterY 入力ピクセル単位のY方向ジッター
     * @param frameTimeMilliseconds 入力取得間隔。ミリ秒単位
     * @param validationKind 0で通常評価、非0で明示的な読み戻し診断
     * @return 入力契約と評価が成功した場合はOk
     */
    NativeError evaluate(ID3D11Texture2D* color, ID3D11Texture2D* depth, ID3D11Texture2D* motion,
            std::uint64_t sequence, bool reset, float jitterX, float jitterY,
            float frameTimeMilliseconds, int validationKind
#if defined(TITANIUM_TEST_NGX_BIAS_MASK)
            , ID3D11Texture2D* biasCurrentColor = nullptr
#endif
            );
    /**
     * Featureが所有する出力を既存のGL名へ読み取り共有として登録する。
     * GL名自体の生成・削除はJava側が担当する。
     */
    NativeError registerOutput(WglDxInterop& interop, unsigned glName);
    /** 指定番号の最新評価結果を一度だけGLへ渡し、返却コピーのための所有権を取得する。 */
    NativeError lockOutput(std::uint64_t sequence);
    /** ロックした番号を照合し、返却完了を記録して出力の所有権をD3D11へ戻す。 */
    NativeError unlockOutput(std::uint64_t sequence);
    // Sparse diagnostic, only after the same output has returned to D3D ownership.
    bool readReturnedOutputRoi(std::uint64_t sequence, unsigned x, unsigned y, unsigned size,
            std::vector<unsigned char>& output);
    NativeError pollValidation();
    NativeError configureTiming(bool enabled);
    NativeError armTiming(std::uint64_t sequence);
    std::vector<std::int64_t> pollTiming();
    /**
     * 出力のGLロック・共有登録を返してからFeatureと出力テクスチャを解放する。
     * 共有解除が失敗した資源は保持し、所有権の診断を呼び出し元へ返す。
     */
    NativeError releaseFeature();
    /** Featureを解放してからNGXパラメーターと実行時セッションを終了する。 */
    NativeError shutdown();
#ifdef TITANIUM_TEST_NGX_HISTORY
    // Separate feature/parameters, borrowing only the already-initialized SDK runtime.
    NativeError createHistoryPeer(const FrameNgxEvaluator& primary);
#endif
    bool initialized() const;
    bool featureReady() const;
    bool validationPassed() const;
    bool outputInteropPassed() const;
    ID3D11Texture2D* output() const;
    std::string diagnostics() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}
