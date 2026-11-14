#include "AdapterSelector.hpp"
#include <windows.h>
#include <sstream>

namespace titanium {
namespace {
std::string utf8(const wchar_t* value) {
    int size = WideCharToMultiByte(CP_UTF8, 0, value, -1, nullptr, 0, nullptr, nullptr);
    if (size <= 1) return {};
    std::string result(static_cast<std::size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value, -1, result.data(), size, nullptr, nullptr);
    result.pop_back();
    return result;
}
}

HRESULT enumerateHighPerformanceAdapters(std::vector<AdapterInfo>& adapters, std::string& diagnostics) {
    Microsoft::WRL::ComPtr<IDXGIFactory6> factory;
    HRESULT result = CreateDXGIFactory2(0, IID_PPV_ARGS(&factory));
    if (FAILED(result)) return result;

    std::ostringstream log;
    for (UINT index = 0;; ++index) {
        Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter;
        result = factory->EnumAdapterByGpuPreference(index, DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,
                IID_PPV_ARGS(&adapter));
        if (result == DXGI_ERROR_NOT_FOUND) break;
        if (FAILED(result)) return result;
        DXGI_ADAPTER_DESC1 desc{};
        if (FAILED(adapter->GetDesc1(&desc))) continue;
        // ソフトウェアアダプターを除き、高性能順の実GPUだけをD3D11初期化候補へ残す。
        if ((desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) != 0) continue;
        AdapterInfo info{};
        info.adapter = adapter;
        info.description = utf8(desc.Description);
        info.vendorId = desc.VendorId;
        info.deviceId = desc.DeviceId;
        info.subSysId = desc.SubSysId;
        info.revision = desc.Revision;
        // DXGIの上下32ビットを64ビット識別子へ結合し、Java側でも同じGPUを識別できる形にする。
        info.luid = (static_cast<std::uint64_t>(static_cast<std::uint32_t>(desc.AdapterLuid.HighPart)) << 32)
                | static_cast<std::uint32_t>(desc.AdapterLuid.LowPart);
        info.dedicatedVideoMemory = desc.DedicatedVideoMemory;
        info.sharedSystemMemory = desc.SharedSystemMemory;
        log << "adapter[" << index << "]=" << info.description << ",vendor=0x" << std::hex
            << info.vendorId << ",device=0x" << info.deviceId << std::dec << ';';
        adapters.push_back(std::move(info));
    }
    diagnostics = log.str();
    return S_OK;
}
}
