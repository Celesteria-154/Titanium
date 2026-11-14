#include "D3D11Context.hpp"
#include <filesystem>
#include <iomanip>
#include <sstream>

namespace titanium {

NativeError D3D11Context::initialize(const std::wstring& streamlineDirectory) {
    if (initialized()) return NativeError::Ok;
    std::vector<AdapterInfo> adapters;
    HRESULT result = enumerateHighPerformanceAdapters(adapters, diagnostics_);
    if (FAILED(result)) {
        diagnostics_ += "CreateDXGIFactory2 HRESULT=0x" + std::to_string(static_cast<unsigned>(result));
        return NativeError::NoDxgiFactory6;
    }
    if (adapters.empty()) return NativeError::NoD3D11Adapter;

    // 高性能順にデバイス生成を試し、共有描画に必要なFeature Level 11系の最初の成功GPUを採用する。
    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    std::ostringstream attempts;
    for (auto& candidate : adapters) {
        // 明示アダプターではUNKNOWNを指定する。WGL共有で使用するためSINGLETHREADEDは指定しない。
        result = D3D11CreateDevice(candidate.adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT, levels, ARRAYSIZE(levels), D3D11_SDK_VERSION,
                &device_, &featureLevel_, &immediateContext_);
        attempts << candidate.description << " HRESULT=0x" << std::hex << static_cast<unsigned>(result) << ';';
        if (SUCCEEDED(result)) {
            adapter_ = std::move(candidate);
            break;
        }
        device_.Reset();
        immediateContext_.Reset();
    }
    diagnostics_ += attempts.str();
    if (!initialized()) return NativeError::D3D11CreateFailed;

    streamlineDirectory_ = streamlineDirectory;
    static constexpr const wchar_t* files[] = {
        L"sl.interposer.dll", L"sl.common.dll", L"sl.dlss.dll", L"nvngx_dlss.dll"
    };
    for (unsigned i = 0; i < ARRAYSIZE(files); ++i) {
        if (std::filesystem::is_regular_file(std::filesystem::path(streamlineDirectory_) / files[i])) {
            streamlineMask_ |= 1u << i;
        }
    }
    return NativeError::Ok;
}

NativeError D3D11Context::shutdown() {
    if (!initialized()) return NativeError::AlreadyShutdown;
    // D3D側の保持参照を解除して送信した後、コンテキスト・デバイス・アダプターの順に所有権を返す。
    immediateContext_->ClearState();
    immediateContext_->Flush();
    immediateContext_.Reset();
    device_.Reset();
    adapter_.adapter.Reset();
    return NativeError::Ok;
}
}
