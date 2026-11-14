#pragma once

#include "D3D11Context.hpp"
#include "FrameColorEncoding.hpp"
#include "WglDxInterop.hpp"
#include <array>
#include <cstdint>
#include <string>
#include <vector>
#include <wrl/client.h>

namespace titanium {

class FrameInteropSet {
public:
    enum class State { Empty, D3DReady, GlLocked, Failed };

    /**
     * 同寸法のカラー・深度・MVをD3D11で生成し、指定GL名へ共有登録する。
     * 生成途中の失敗でも登録を逆順で解除し、解除できない資源は保持する。
     * @param glNames カラー、深度、MVの順のGL名。すべて非0かつ相互に異なる値
     * @param width 入力幅。ピクセル単位
     * @param height 入力高さ。ピクセル単位
     * @param validationEnabled 診断用読み戻しリングを確保する場合はtrue
     * @param diagnostics ネイティブ操作の失敗理由を追記する出力引数
     * @param colorEncoding 呼び出し元が検証した入力色の意味と格納形式
     * @return 全入力を登録できた場合はOk
     */
    NativeError create(ID3D11Device* device, WglDxInterop& interop,
            const std::array<unsigned, 3>& glNames, unsigned width, unsigned height,
            bool validationEnabled, std::string& diagnostics,
            FrameColorEncoding colorEncoding = FrameColorEncoding::LdrUnorm);
    /** コピー先の3入力をまとめてGL所有へ移し、描画スレッドからの書き込みを許可する。 */
    NativeError lock(WglDxInterop& interop, std::string& diagnostics);
    /**
     * コピー済みの3入力をD3D11へ返し、フレーム番号とジッターの整合性を確認する。
     * @param sequence 世代内で単調増加する正の入力番号
     * @param reset この入力で履歴を再開する場合はtrue
     * @param jitterX 入力ピクセル単位のXサンプル位置
     * @param jitterY 入力ピクセル単位のYサンプル位置
     * @param phase 0始まりのサンプル位相。reset時は0
     * @param phaseCount 位相を繰り返すまでの周期
     * @param jitterEnabled サンプル変位が有効な場合はtrue
     * @param validationKind 0で通常入力、非0で明示的な読み戻し診断
     * @return 所有権と入力メタデータを確定できた場合はOk
     */
    NativeError unlock(ID3D11DeviceContext* context, WglDxInterop& interop, std::int64_t sequence, bool reset,
            double jitterX, double jitterY, int phase, int phaseCount, bool jitterEnabled,
            int validationKind, std::string& diagnostics);
    /** 完了済みの診断リングだけを読み戻して解析し、通常の入力転送へ追加待機を持ち込まない。 */
    NativeError pollValidation(ID3D11DeviceContext* context, std::string& diagnostics);
    // Explicit diagnostic only: bounded synchronous read, no persistent resources.
    bool readRoi(ID3D11Device* device, ID3D11DeviceContext* context, std::int64_t sequence,
            unsigned x, unsigned y, unsigned size, std::vector<unsigned char>& output);
    /**
     * 保留中の読み戻しを破棄し、GLロック・登録・D3D11テクスチャを順に解放する。
     * 解除が失敗した登録と対応テクスチャは、再試行可能な状態で保持する。
     */
    NativeError release(WglDxInterop& interop, std::string& diagnostics);
    bool validationPassed() const;
    std::string validationDiagnostics() const;
    bool validationEnabled() const { return validationEnabled_; }
    std::size_t validationResourceCount() const;

    State state() const { return state_; }
    bool empty() const { return state_ == State::Empty; }
    unsigned width() const { return width_; }
    unsigned height() const { return height_; }
    FrameColorEncoding colorEncoding() const { return colorEncoding_; }
    std::uint64_t lastSequence() const { return lastSequence_; }
    ID3D11Texture2D* color() const { return textures_[0].Get(); }
    ID3D11Texture2D* depth() const { return textures_[1].Get(); }
    ID3D11Texture2D* motion() const { return textures_[2].Get(); }

private:
    static constexpr std::size_t ValidationSlotCount = 4;
    struct ValidationSlot {
        std::array<Microsoft::WRL::ComPtr<ID3D11Texture2D>, 3> staging{};
        Microsoft::WRL::ComPtr<ID3D11Query> query;
        std::uint64_t sequence{};
        int kind{};
        bool pending{};
    };

    /** 明示診断用に、3入力のステージングとGPU完了クエリーをリング分確保する。 */
    NativeError createValidationResources(ID3D11Device* device, std::string& diagnostics);
    /** 空きスロットへ中央の診断領域をコピーし、同じ番号でGPU完了クエリーを予約する。 */
    NativeError submitValidation(ID3D11DeviceContext* context, int kind, std::string& diagnostics);
    /** 完了したスロットをmapし、色・深度・MVの有限性と診断種別の条件を集計する。 */
    NativeError analyzeValidation(ID3D11DeviceContext* context, ValidationSlot& slot,
            std::string& diagnostics);
    void releaseValidationResources();

    State state_{State::Empty};
    std::array<Microsoft::WRL::ComPtr<ID3D11Texture2D>, 3> textures_{};
    std::array<HANDLE, 3> objects_{};
    unsigned width_{};
    unsigned height_{};
    std::uint64_t lastSequence_{};
    bool validationEnabled_{};
    FrameColorEncoding colorEncoding_{FrameColorEncoding::LdrUnorm};
    unsigned sampleWidth_{};
    unsigned sampleHeight_{};
    std::array<ValidationSlot, ValidationSlotCount> validationSlots_{};
    std::uint64_t validationSubmitted_{};
    std::uint64_t validationCompleted_{};
    std::uint64_t validationCancelled_{};
    std::uint64_t validationSkipped_{};
    std::array<std::uint64_t, 4> validationKinds_{};
    float maximumStaticMotion_{};
    float maximumCameraMotion_{};
    std::string validationFailure_;
};

}
