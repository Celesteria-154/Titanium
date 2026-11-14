#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <wrl/client.h>
#include <dxgi1_6.h>

namespace titanium {

struct AdapterInfo {
    Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter;
    std::string description;
    std::uint32_t vendorId{};
    std::uint32_t deviceId{};
    std::uint32_t subSysId{};
    std::uint32_t revision{};
    std::uint64_t luid{};
    std::uint64_t dedicatedVideoMemory{};
    std::uint64_t sharedSystemMemory{};
};

HRESULT enumerateHighPerformanceAdapters(std::vector<AdapterInfo>& adapters, std::string& diagnostics);

}
