#pragma once

#include "AdapterSelector.hpp"
#include <d3d11.h>

namespace titanium {

enum class NativeError : int {
    Ok = 0,
    NoDxgiFactory6 = 5,
    NoD3D11Adapter = 6,
    D3D11CreateFailed = 7,
    AlreadyShutdown = 8,
    InternalError = 9,
    WglContextFailed = 10,
    WglExtensionMissing = 11,
    WglOpenDeviceFailed = 12,
    WglRegisterFailed = 13,
    WglLockFailed = 14,
    WglUnlockFailed = 15,
    WglUnregisterFailed = 16,
    WglCloseDeviceFailed = 17,
    WglDataMismatch = 18,
    FrameTextureCreateFailed = 19,
    FrameTextureUnavailable = 20,
    FrameInteropInvalidState = 21,
    FrameInteropCreateFailed = 22,
    FrameInteropSequenceInvalid = 23,
    FrameInteropValidationFailed = 24,
    NgxRuntimeInvalid = 25,
    NgxUnavailable = 26,
    NgxInvalidState = 27,
    NgxInitializeFailed = 28,
    NgxOptimalSettingsFailed = 29,
    NgxFeatureCreateFailed = 30,
    NgxReleaseFailed = 31,
    NgxEvaluateFailed = 32,
    NgxOutputValidationFailed = 33
};

class D3D11Context {
public:
    /** 高性能順の実GPUでD3D11デバイスを生成し、採用したアダプターと共有用コンテキストを保持する。 */
    NativeError initialize(const std::wstring& streamlineDirectory);
    /** 即時コンテキストの参照とコマンドを返してから、デバイス・アダプターの所有権を解放する。 */
    NativeError shutdown();
    bool initialized() const { return device_ != nullptr; }
    const AdapterInfo& adapter() const { return adapter_; }
    ID3D11Device* device() const { return device_.Get(); }
    ID3D11DeviceContext* immediateContext() const { return immediateContext_.Get(); }
    D3D_FEATURE_LEVEL featureLevel() const { return featureLevel_; }
    const std::wstring& streamlineDirectory() const { return streamlineDirectory_; }
    unsigned streamlineMask() const { return streamlineMask_; }
    const std::string& diagnostics() const { return diagnostics_; }

private:
    AdapterInfo adapter_{};
    Microsoft::WRL::ComPtr<ID3D11Device> device_;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> immediateContext_;
    D3D_FEATURE_LEVEL featureLevel_{};
    std::wstring streamlineDirectory_;
    unsigned streamlineMask_{};
    std::string diagnostics_;
};

}
