#pragma once

#include "D3D11Context.hpp"
#include <windows.h>
#include <string>
#include <vector>

namespace titanium {

class WglDxInterop {
public:
    static constexpr unsigned AccessReadOnly = 0x0000;
    static constexpr unsigned AccessReadWrite = 0x0001;

    WglDxInterop() = default;
    ~WglDxInterop();
    WglDxInterop(const WglDxInterop&) = delete;
    WglDxInterop& operator=(const WglDxInterop&) = delete;

    /** 現在のGLコンテキストでNV相互運用の入口を確認し、指定D3D11デバイスとの接続を開く。 */
    NativeError initialize(ID3D11Device* device, std::string& diagnostics);
    /**
     * D3D11テクスチャを、確保済みのGL名へ指定アクセス権で登録する。
     * @param texture 呼び出し元が保持するD3D11テクスチャ
     * @param glName Java側で確保した非0のGLテクスチャ名
     * @param glTarget 登録対象のGLテクスチャ種別
     * @param access GL側の読み取り専用または読み書きのアクセス権
     * @return 成功時の登録ハンドル。失敗時はnullptr
     */
    HANDLE registerTexture(ID3D11Texture2D* texture, unsigned glName, unsigned glTarget,
            unsigned access, std::string& diagnostics);
    NativeError lock(HANDLE object, std::string& diagnostics);
    /** 登録済みで未ロックのハンドル群を一括でGL所有へ移す。重複指定はエラーとして扱う。 */
    NativeError lock(std::vector<HANDLE> objects, std::string& diagnostics);
    NativeError unlock(HANDLE object, std::string& diagnostics);
    /** 現在GL所有のハンドル群を一括でD3D11へ返し、成功後にロック記録を更新する。 */
    NativeError unlock(std::vector<HANDLE> objects, std::string& diagnostics);
    /** GLのロックを返した単一ハンドルについて、D3D11とGLの共有登録を解除する。 */
    NativeError unregisterObject(HANDLE object, std::string& diagnostics);
    /** ロック解除、共有登録解除、デバイス接続終了の順で、残る所有ハンドルを返す。 */
    NativeError shutdown(std::string& diagnostics);
    bool initialized() const { return interopDevice_ != nullptr; }

private:
    using OpenDevice = HANDLE(WINAPI*)(void*);
    using CloseDevice = BOOL(WINAPI*)(HANDLE);
    using RegisterObject = HANDLE(WINAPI*)(HANDLE, void*, unsigned, unsigned, unsigned);
    using UnregisterObject = BOOL(WINAPI*)(HANDLE, HANDLE);
    using LockObjects = BOOL(WINAPI*)(HANDLE, int, HANDLE*);
    using UnlockObjects = BOOL(WINAPI*)(HANDLE, int, HANDLE*);

    HANDLE interopDevice_{};
    OpenDevice openDevice_{};
    CloseDevice closeDevice_{};
    RegisterObject registerObject_{};
    UnregisterObject unregisterObject_{};
    LockObjects lockObjects_{};
    UnlockObjects unlockObjects_{};
    std::vector<HANDLE> registeredObjects_;
    std::vector<HANDLE> lockedObjects_;
};

}
