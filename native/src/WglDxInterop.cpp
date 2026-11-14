#include "WglDxInterop.hpp"
#include <GL/gl.h>
#include <algorithm>
#include <cstdint>
#include <sstream>

namespace titanium {
namespace {
template<typename T>
T load(const char* name) {
    PROC address = wglGetProcAddress(name);
    auto value = reinterpret_cast<std::uintptr_t>(address);
    // wglGetProcAddressが返す既知の失敗用番地も除外し、無効な関数ポインターを呼ばない。
    if (!address || value == 1 || value == 2 || value == 3 || value == static_cast<std::uintptr_t>(-1)) {
        return nullptr;
    }
    return reinterpret_cast<T>(address);
}

void appendLastError(std::string& diagnostics, const char* operation) {
    std::ostringstream output;
    output << operation << " GetLastError=" << GetLastError() << ';';
    diagnostics += output.str();
}
}

WglDxInterop::~WglDxInterop() {
    std::string ignored;
    shutdown(ignored);
}

NativeError WglDxInterop::initialize(ID3D11Device* device, std::string& diagnostics) {
    if (initialized()) return NativeError::Ok;
    if (!device || !wglGetCurrentContext()) {
        diagnostics += "WGL context or D3D11 device missing;";
        return NativeError::WglContextFailed;
    }
    // 現在のGLコンテキストで共有拡張の入口を取得し、D3D11デバイスとの接続を開く。
    openDevice_ = load<OpenDevice>("wglDXOpenDeviceNV");
    closeDevice_ = load<CloseDevice>("wglDXCloseDeviceNV");
    registerObject_ = load<RegisterObject>("wglDXRegisterObjectNV");
    unregisterObject_ = load<UnregisterObject>("wglDXUnregisterObjectNV");
    lockObjects_ = load<LockObjects>("wglDXLockObjectsNV");
    unlockObjects_ = load<UnlockObjects>("wglDXUnlockObjectsNV");
    if (!openDevice_ || !closeDevice_ || !registerObject_ || !unregisterObject_
            || !lockObjects_ || !unlockObjects_) {
        diagnostics += "WGL_NV_DX_interop entry point missing;";
        return NativeError::WglExtensionMissing;
    }
    SetLastError(ERROR_SUCCESS);
    interopDevice_ = openDevice_(device);
    if (!interopDevice_) {
        appendLastError(diagnostics, "wglDXOpenDeviceNV");
        return NativeError::WglOpenDeviceFailed;
    }
    diagnostics += "WGL_NV_DX_interop2 device opened;";
    return NativeError::Ok;
}

HANDLE WglDxInterop::registerTexture(ID3D11Texture2D* texture, unsigned glName, unsigned glTarget,
        unsigned access, std::string& diagnostics) {
    if (!initialized() || !texture || glName == 0) {
        diagnostics += "Invalid interop registration state;";
        return nullptr;
    }
    SetLastError(ERROR_SUCCESS);
    HANDLE object = registerObject_(interopDevice_, texture, glName, glTarget, access);
    if (!object) appendLastError(diagnostics, "wglDXRegisterObjectNV");
    else registeredObjects_.push_back(object);
    return object;
}

NativeError WglDxInterop::lock(HANDLE object, std::string& diagnostics) {
    return lock(std::vector<HANDLE>{object}, diagnostics);
}

NativeError WglDxInterop::lock(std::vector<HANDLE> objects, std::string& diagnostics) {
    if (!initialized() || !lockObjects_ || objects.empty()
            || std::any_of(objects.begin(), objects.end(), [&](HANDLE object) {
                return !object
                        || std::find(registeredObjects_.begin(), registeredObjects_.end(), object)
                                == registeredObjects_.end()
                        || std::find(lockedObjects_.begin(), lockedObjects_.end(), object) != lockedObjects_.end()
                        || std::count(objects.begin(), objects.end(), object) != 1;
            })) {
        diagnostics += "Invalid interop lock state;";
        return NativeError::WglLockFailed;
    }
    SetLastError(ERROR_SUCCESS);
    if (!lockObjects_(interopDevice_, static_cast<int>(objects.size()), objects.data())) {
        appendLastError(diagnostics, "wglDXLockObjectsNV");
        return NativeError::WglLockFailed;
    }
    // 拡張APIのロック成功後だけGL所有として記録し、重複ロックと未登録オブジェクトの使用を防ぐ。
    lockedObjects_.insert(lockedObjects_.end(), objects.begin(), objects.end());
    return NativeError::Ok;
}

NativeError WglDxInterop::unlock(HANDLE object, std::string& diagnostics) {
    return unlock(std::vector<HANDLE>{object}, diagnostics);
}

NativeError WglDxInterop::unlock(std::vector<HANDLE> objects, std::string& diagnostics) {
    if (!initialized() || !unlockObjects_ || objects.empty()
            || std::any_of(objects.begin(), objects.end(), [&](HANDLE object) {
                return !object
                        || std::find(lockedObjects_.begin(), lockedObjects_.end(), object) == lockedObjects_.end()
                        || std::count(objects.begin(), objects.end(), object) != 1;
            })) {
        diagnostics += "Invalid interop unlock state;";
        return NativeError::WglUnlockFailed;
    }
    SetLastError(ERROR_SUCCESS);
    if (!unlockObjects_(interopDevice_, static_cast<int>(objects.size()), objects.data())) {
        appendLastError(diagnostics, "wglDXUnlockObjectsNV");
        return NativeError::WglUnlockFailed;
    }
    for (auto object : objects) std::erase(lockedObjects_, object);
    return NativeError::Ok;
}

NativeError WglDxInterop::unregisterObject(HANDLE object, std::string& diagnostics) {
    if (!initialized() || !unregisterObject_ || !object
            || std::find(registeredObjects_.begin(), registeredObjects_.end(), object) == registeredObjects_.end()
            || std::find(lockedObjects_.begin(), lockedObjects_.end(), object) != lockedObjects_.end()) {
        diagnostics += "Invalid interop unregister state;";
        return NativeError::WglUnregisterFailed;
    }
    SetLastError(ERROR_SUCCESS);
    if (!unregisterObject_(interopDevice_, object)) {
        appendLastError(diagnostics, "wglDXUnregisterObjectNV");
        return NativeError::WglUnregisterFailed;
    }
    std::erase(registeredObjects_, object);
    return NativeError::Ok;
}

NativeError WglDxInterop::shutdown(std::string& diagnostics) {
    if (!interopDevice_) return NativeError::AlreadyShutdown;
    NativeError result = NativeError::Ok;
    // 終了時はGLロックを返してから共有登録を解除し、最後に共有デバイスを閉じる。
    for (auto object : lockedObjects_) {
        SetLastError(ERROR_SUCCESS);
        if (!unlockObjects_(interopDevice_, 1, &object)) {
            appendLastError(diagnostics, "wglDXUnlockObjectsNV(shutdown)");
            result = NativeError::WglUnlockFailed;
        }
    }
    lockedObjects_.clear();
    for (auto object : registeredObjects_) {
        SetLastError(ERROR_SUCCESS);
        if (!unregisterObject_(interopDevice_, object)) {
            appendLastError(diagnostics, "wglDXUnregisterObjectNV(shutdown)");
            if (result == NativeError::Ok) result = NativeError::WglUnregisterFailed;
        }
    }
    registeredObjects_.clear();
    SetLastError(ERROR_SUCCESS);
    if (!closeDevice_(interopDevice_)) {
        appendLastError(diagnostics, "wglDXCloseDeviceNV");
        return NativeError::WglCloseDeviceFailed;
    }
    interopDevice_ = nullptr;
    return result;
}
}
