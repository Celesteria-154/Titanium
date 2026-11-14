#include "D3D11Context.hpp"
#include "FrameInteropSet.hpp"
#include "FrameNgxEvaluator.hpp"
#include "WglDxInterop.hpp"
#include <GL/gl.h>
#include <d3d11.h>
#include <jni.h>
#include <mutex>
#ifdef TITANIUM_TEST_NGX_HISTORY
#include <array>
#include <cstring>
#endif
#include <string>
#include <wrl/client.h>

namespace {
// JNI入口は同じネイティブ所有者を共有するため、状態更新をこのmutexで直列化する。
std::mutex mutex;
titanium::D3D11Context context;
titanium::WglDxInterop interop;
Microsoft::WRL::ComPtr<ID3D11Texture2D> frameTexture;
Microsoft::WRL::ComPtr<ID3D11Texture2D> outputTexture;
HANDLE frameInputObject{};
HANDLE frameOutputObject{};
std::string frameDiagnostics;
bool frameCopyReported{};
titanium::FrameInteropSet frameInterop;
std::string frameInteropDiagnostics;
titanium::FrameNgxEvaluator frameNgxEvaluator;
#ifdef TITANIUM_TEST_NGX_HISTORY
titanium::FrameNgxEvaluator historyPeer;
std::array<jlong, 10> historySnapshot{}; // creates, releases, pairs, resets, sequence, jitter/delta bits, sameInputs, error
bool historyFirst{};
jlong floatBits(float value) {
    std::uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}
#endif

titanium::NativeError releaseFrameTexture() {
    titanium::NativeError firstError = titanium::NativeError::Ok;
    if (frameOutputObject) {
        auto result = interop.unregisterObject(frameOutputObject, frameDiagnostics);
        if (result == titanium::NativeError::Ok) {
            frameOutputObject = nullptr;
            outputTexture.Reset();
        } else {
            firstError = result;
        }
    } else {
        outputTexture.Reset();
    }
    if (frameInputObject) {
        auto result = interop.unregisterObject(frameInputObject, frameDiagnostics);
        if (result == titanium::NativeError::Ok) {
            frameInputObject = nullptr;
            frameTexture.Reset();
        } else if (firstError == titanium::NativeError::Ok) {
            firstError = result;
        }
    } else {
        frameTexture.Reset();
    }
    if (!frameInputObject && !frameOutputObject) {
        frameCopyReported = false;
    }
    return firstError;
}

// Java文字列のUTF-16をWindowsのワイド文字列へコピーし、JNIから借用した文字配列を直ちに返す。
std::wstring wide(JNIEnv* env, jstring value) {
    if (!value) return {};
    const jchar* chars = env->GetStringChars(value, nullptr);
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring text(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeInitialize(JNIEnv* env, jclass, jstring path) {
    std::scoped_lock lock(mutex);
    return static_cast<jint>(context.initialize(wide(env, path)));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeGetCapabilities(JNIEnv* env, jclass) {
    std::scoped_lock lock(mutex);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray values = env->NewObjectArray(12, stringClass, nullptr);
    const auto& adapter = context.adapter();
    // Java側の固定フィールド順に合わせて、初期化結果とGPU識別情報を文字列配列へ詰める。
    const std::string fields[] = {
        context.initialized() ? "true" : "false", adapter.description,
        std::to_string(adapter.vendorId), std::to_string(adapter.deviceId),
        std::to_string(adapter.subSysId), std::to_string(adapter.revision),
        std::to_string(adapter.luid), std::to_string(adapter.dedicatedVideoMemory),
        std::to_string(adapter.sharedSystemMemory), std::to_string(context.featureLevel()),
        std::to_string(context.streamlineMask()), context.diagnostics()
    };
    for (jsize i = 0; i < 12; ++i) env->SetObjectArrayElement(values, i, text(env, fields[i]));
    return values;
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeCreateFrameTextures(
        JNIEnv*, jclass, jint inputGlName, jint outputGlName, jint width, jint height) {
    std::scoped_lock lock(mutex);
    if (!context.initialized() || inputGlName <= 0 || outputGlName <= 0 || inputGlName == outputGlName
            || width <= 0 || height <= 0 || frameInputObject || frameOutputObject || !frameInterop.empty()) {
        frameDiagnostics += "Invalid frame texture create state;";
        return static_cast<jint>(titanium::NativeError::FrameTextureUnavailable);
    }
    // リサイズを繰り返しても、診断記録は現在の共有登録分だけを保持する。
    frameDiagnostics.clear();
    auto result = interop.initialize(context.device(), frameDiagnostics);
    if (result != titanium::NativeError::Ok) return static_cast<jint>(result);

    D3D11_TEXTURE2D_DESC description{};
    description.Width = static_cast<UINT>(width);
    description.Height = static_cast<UINT>(height);
    description.MipLevels = 1;
    description.ArraySize = 1;
    description.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    description.SampleDesc.Count = 1;
    description.Usage = D3D11_USAGE_DEFAULT;
    description.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    HRESULT hr = context.device()->CreateTexture2D(&description, nullptr, &frameTexture);
    if (FAILED(hr)) {
        frameDiagnostics += "CreateTexture2D(frame) failed;";
        return static_cast<jint>(titanium::NativeError::FrameTextureCreateFailed);
    }
    hr = context.device()->CreateTexture2D(&description, nullptr, &outputTexture);
    if (FAILED(hr)) {
        frameTexture.Reset();
        frameDiagnostics += "CreateTexture2D(output) failed;";
        return static_cast<jint>(titanium::NativeError::FrameTextureCreateFailed);
    }
    frameInputObject = interop.registerTexture(frameTexture.Get(), static_cast<unsigned>(inputGlName), GL_TEXTURE_2D,
            titanium::WglDxInterop::AccessReadWrite, frameDiagnostics);
    if (!frameInputObject) {
        frameTexture.Reset();
        outputTexture.Reset();
        return static_cast<jint>(titanium::NativeError::WglRegisterFailed);
    }
    frameOutputObject = interop.registerTexture(outputTexture.Get(), static_cast<unsigned>(outputGlName), GL_TEXTURE_2D,
            titanium::WglDxInterop::AccessReadWrite, frameDiagnostics);
    if (!frameOutputObject) {
        auto cleanup = interop.unregisterObject(frameInputObject, frameDiagnostics);
        if (cleanup == titanium::NativeError::Ok) frameInputObject = nullptr;
        if (!frameInputObject) frameTexture.Reset();
        outputTexture.Reset();
        return static_cast<jint>(titanium::NativeError::WglRegisterFailed);
    }
    frameDiagnostics += "Minecraft input/output textures registered;";
    frameCopyReported = false;
    return static_cast<jint>(titanium::NativeError::Ok);
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeLockFrameInput(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    if (!frameInputObject) return static_cast<jint>(titanium::NativeError::FrameTextureUnavailable);
    return static_cast<jint>(interop.lock(frameInputObject, frameDiagnostics));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeUnlockFrameInput(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    if (!frameInputObject) return static_cast<jint>(titanium::NativeError::FrameTextureUnavailable);
    return static_cast<jint>(interop.unlock(frameInputObject, frameDiagnostics));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeLockFrameOutput(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    if (!frameOutputObject) return static_cast<jint>(titanium::NativeError::FrameTextureUnavailable);
    return static_cast<jint>(interop.lock(frameOutputObject, frameDiagnostics));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeUnlockFrameOutput(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    if (!frameOutputObject) return static_cast<jint>(titanium::NativeError::FrameTextureUnavailable);
    return static_cast<jint>(interop.unlock(frameOutputObject, frameDiagnostics));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeConsumeFrameTexture(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    if (!frameTexture || !outputTexture) {
        return static_cast<jint>(titanium::NativeError::FrameTextureUnavailable);
    }
    context.immediateContext()->CopyResource(outputTexture.Get(), frameTexture.Get());
    context.immediateContext()->Flush();
    if (!frameCopyReported) {
        frameDiagnostics += "D3D11 input to output copy completed;";
        frameCopyReported = true;
    }
    return static_cast<jint>(titanium::NativeError::Ok);
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeReleaseFrameTexture(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    return static_cast<jint>(releaseFrameTexture());
}

extern "C" JNIEXPORT jstring JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeGetFrameDiagnostics(JNIEnv* env, jclass) {
    std::scoped_lock lock(mutex);
    return text(env, frameDiagnostics);
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeCreateFrameInterop(
        JNIEnv*, jclass, jint colorGlName, jint depthGlName, jint motionGlName, jint width, jint height,
        jboolean validationEnabled) {
    std::scoped_lock lock(mutex);
    if (!context.initialized() || frameInputObject || frameOutputObject || !frameInterop.empty()
            || colorGlName <= 0 || depthGlName <= 0 || motionGlName <= 0 || width <= 0 || height <= 0) {
        frameInteropDiagnostics += "Invalid or conflicting frame interop create state;";
        return static_cast<jint>(titanium::NativeError::FrameInteropInvalidState);
    }
    frameInteropDiagnostics.clear();
    auto result = interop.initialize(context.device(), frameInteropDiagnostics);
    if (result != titanium::NativeError::Ok) return static_cast<jint>(result);
    return static_cast<jint>(frameInterop.create(context.device(), interop,
            {static_cast<unsigned>(colorGlName), static_cast<unsigned>(depthGlName),
                    static_cast<unsigned>(motionGlName)},
            static_cast<unsigned>(width), static_cast<unsigned>(height), validationEnabled == JNI_TRUE,
            frameInteropDiagnostics));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeLockFrameInterop(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    return static_cast<jint>(frameInterop.lock(interop, frameInteropDiagnostics));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeUnlockFrameInterop(
        JNIEnv*, jclass, jlong sequence, jboolean reset, jdouble jitterX, jdouble jitterY,
        jint phase, jint phaseCount, jboolean jitterEnabled, jint validationKind) {
    std::scoped_lock lock(mutex);
    return static_cast<jint>(frameInterop.unlock(context.immediateContext(), interop,
            static_cast<std::int64_t>(sequence), reset == JNI_TRUE,
            jitterX, jitterY, phase, phaseCount, jitterEnabled == JNI_TRUE,
            validationKind, frameInteropDiagnostics));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativePollFrameInteropValidation(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    return static_cast<jint>(frameInterop.pollValidation(context.immediateContext(), frameInteropDiagnostics));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeReadFrameInputRoi(
        JNIEnv* env, jclass, jlong sequence, jint x, jint y, jint size) {
    std::scoped_lock lock(mutex);
    try {
        std::vector<unsigned char> data;
        if (x < 0 || y < 0 || size <= 0 || !frameInterop.readRoi(context.device(),
                context.immediateContext(),sequence,x,y,size,data)) return nullptr;
        auto result=env->NewByteArray(static_cast<jsize>(data.size()));
        if(result) env->SetByteArrayRegion(result,0,static_cast<jsize>(data.size()),
                reinterpret_cast<const jbyte*>(data.data()));
        return result;
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeFrameInteropValidationPassed(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    return frameInterop.validationPassed() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeReadReturnedOutputRoi(
        JNIEnv* env, jclass, jlong sequence, jint x, jint y, jint size) {
    std::scoped_lock lock(mutex);
    try {
        std::vector<unsigned char> data;
        if (sequence <= 0 || x < 0 || y < 0 || size <= 0
                || !frameNgxEvaluator.readReturnedOutputRoi(sequence, x, y, size, data)) return nullptr;
        auto result = env->NewByteArray(static_cast<jsize>(data.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                reinterpret_cast<const jbyte*>(data.data()));
        return result;
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeReleaseFrameInterop(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    return static_cast<jint>(frameInterop.release(interop, frameInteropDiagnostics));
}

extern "C" JNIEXPORT jstring JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeGetFrameInteropDiagnostics(JNIEnv* env, jclass) {
    std::scoped_lock lock(mutex);
    return text(env, frameInteropDiagnostics + frameInterop.validationDiagnostics());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeNgxCompiled(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    return frameNgxEvaluator.compiledWithNgx() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeGetNgxDiagnostics(JNIEnv* env, jclass) {
    std::scoped_lock lock(mutex);
    return text(env, frameNgxEvaluator.diagnostics());
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeInitializeNgx(
        JNIEnv* env, jclass, jstring logDirectory, jstring featureDirectory) {
    std::scoped_lock lock(mutex);
    if (!context.initialized()) return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    return static_cast<jint>(frameNgxEvaluator.initialize(
            context.device(), context.immediateContext(), wide(env, logDirectory),
            wide(env, featureDirectory)));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeCreateNgxFeature(
        JNIEnv*, jclass, jint inputWidth, jint inputHeight, jint outputWidth, jint outputHeight,
        jint qualityMode, jboolean depthInverted, jboolean validationEnabled, jint preset) {
    std::scoped_lock lock(mutex);
    if (inputWidth <= 0 || inputHeight <= 0 || outputWidth <= 0 || outputHeight <= 0
            || (qualityMode != 0 && qualityMode != 1 && qualityMode != 3
                    && qualityMode != 4 && qualityMode != 5)
            || (preset != 0 && preset != 10 && preset != 11 && preset != 12 && preset != 13)) {
        return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    }
    return static_cast<jint>(frameNgxEvaluator.createFeature(
            static_cast<unsigned>(inputWidth), static_cast<unsigned>(inputHeight),
            static_cast<unsigned>(outputWidth), static_cast<unsigned>(outputHeight),
            static_cast<titanium::NgxQualityMode>(qualityMode), depthInverted == JNI_TRUE,
            validationEnabled == JNI_TRUE, static_cast<titanium::NgxRenderPreset>(preset)));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeCreateNgxLdrUnitExposureFeature(
        JNIEnv*, jclass, jint inputWidth, jint inputHeight, jint outputWidth, jint outputHeight,
        jboolean depthInverted) {
    std::scoped_lock lock(mutex);
    if (inputWidth <= 0 || inputHeight <= 0 || inputWidth != outputWidth || inputHeight != outputHeight) {
        return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    }
    return static_cast<jint>(frameNgxEvaluator.createFeature(inputWidth, inputHeight, outputWidth, outputHeight,
            titanium::NgxQualityMode::Dlaa, depthInverted == JNI_TRUE, false,
            titanium::NgxRenderPreset::K, false));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeReleaseNgxFeature(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
#ifdef TITANIUM_TEST_NGX_HISTORY
    auto peerResult = historyPeer.shutdown();
    if (peerResult != titanium::NativeError::Ok) return static_cast<jint>(peerResult);
#endif
    return static_cast<jint>(frameNgxEvaluator.releaseFeature());
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeEvaluateNgxFrame(
        JNIEnv*, jclass, jlong sequence, jboolean reset, jdouble jitterX, jdouble jitterY,
        jdouble frameTimeMilliseconds, jint validationKind) {
    std::scoped_lock lock(mutex);
    if (sequence <= 0 || frameInterop.state() != titanium::FrameInteropSet::State::D3DReady
            || frameInterop.lastSequence() != static_cast<std::uint64_t>(sequence)) {
        return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    }
    auto result = frameNgxEvaluator.evaluate(
            frameInterop.color(), frameInterop.depth(), frameInterop.motion(),
            static_cast<std::uint64_t>(sequence), reset == JNI_TRUE,
            static_cast<float>(jitterX), static_cast<float>(jitterY),
            static_cast<float>(frameTimeMilliseconds), validationKind);
#ifdef TITANIUM_TEST_NGX_HISTORY
    if (result == titanium::NativeError::Ok && historyPeer.featureReady() && historySnapshot[9] == 0) {
        // Same command stream, exact same input objects and float metadata, no intervening input writes.
        auto peerResult = historyPeer.evaluate(frameInterop.color(), frameInterop.depth(), frameInterop.motion(),
                static_cast<std::uint64_t>(sequence), reset == JNI_TRUE,
                static_cast<float>(jitterX), static_cast<float>(jitterY),
                static_cast<float>(frameTimeMilliseconds), 0);
        if (peerResult == titanium::NativeError::Ok) {
            historySnapshot[2]++;
            if (historyFirst || reset == JNI_TRUE) historySnapshot[3]++;
            historyFirst = false;
            historySnapshot[4] = sequence;
            historySnapshot[5] = floatBits(static_cast<float>(jitterX));
            historySnapshot[6] = floatBits(static_cast<float>(jitterY));
            historySnapshot[7] = floatBits(static_cast<float>(frameTimeMilliseconds));
            historySnapshot[8]++;
        } else historySnapshot[9] = static_cast<jlong>(peerResult);
    }
#endif
    // The diagnostic must never select or replace the displayed output/fallback.
    return static_cast<jint>(result);
}

#ifdef TITANIUM_TEST_NGX_HISTORY
extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_quality_HistoryFeatureComparison_nativeStart(JNIEnv*, jclass, jint glName) {
    std::scoped_lock lock(mutex);
    if (glName <= 0 || historySnapshot[9] || historyPeer.featureReady())
        return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    auto result = historyPeer.createHistoryPeer(frameNgxEvaluator);
    if (result == titanium::NativeError::Ok) {
        historySnapshot[0]++;
        historyFirst = true;
        result = historyPeer.registerOutput(interop, static_cast<unsigned>(glName));
    }
    if (result != titanium::NativeError::Ok) historySnapshot[9] = static_cast<jlong>(result);
    return static_cast<jint>(result);
}
extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_quality_HistoryFeatureComparison_nativeLock(JNIEnv*, jclass, jlong sequence) {
    std::scoped_lock lock(mutex);
    if (historySnapshot[9] || sequence <= 0 || sequence != historySnapshot[4])
        return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    return static_cast<jint>(historyPeer.lockOutput(static_cast<std::uint64_t>(sequence)));
}
extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_quality_HistoryFeatureComparison_nativeUnlock(JNIEnv*, jclass, jlong sequence) {
    std::scoped_lock lock(mutex);
    return static_cast<jint>(historyPeer.unlockOutput(static_cast<std::uint64_t>(sequence)));
}
extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_quality_HistoryFeatureComparison_nativeClose(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    bool feature = historyPeer.featureReady();
    auto result = historyPeer.shutdown(); // Borrowed runtime: never global NGX shutdown.
    if (result == titanium::NativeError::Ok && feature) historySnapshot[1]++;
    if (result != titanium::NativeError::Ok) historySnapshot[9] = static_cast<jlong>(result);
    return static_cast<jint>(result);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_celeste_titanium_client_quality_HistoryFeatureComparison_nativeSnapshot(JNIEnv* env, jclass) {
    std::scoped_lock lock(mutex);
    auto output = env->NewLongArray(static_cast<jsize>(historySnapshot.size()));
    if (output) env->SetLongArrayRegion(output, 0, static_cast<jsize>(historySnapshot.size()), historySnapshot.data());
    return output;
}
#endif

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeRegisterNgxOutput(
        JNIEnv*, jclass, jint glName) {
    std::scoped_lock lock(mutex);
    if (glName <= 0) return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    return static_cast<jint>(frameNgxEvaluator.registerOutput(interop, static_cast<unsigned>(glName)));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeConfigureNgxTiming(
        JNIEnv*, jclass, jboolean enabled) {
    std::scoped_lock lock(mutex);
    return static_cast<jint>(frameNgxEvaluator.configureTiming(enabled == JNI_TRUE));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeArmNgxTiming(
        JNIEnv*, jclass, jlong sequence) {
    std::scoped_lock lock(mutex);
    if (sequence <= 0) return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    return static_cast<jint>(frameNgxEvaluator.armTiming(static_cast<std::uint64_t>(sequence)));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativePollNgxTiming(JNIEnv* env, jclass) {
    std::scoped_lock lock(mutex);
    auto snapshot = frameNgxEvaluator.pollTiming();
    std::vector<jlong> result(snapshot.begin(), snapshot.end());
    auto output = env->NewLongArray(static_cast<jsize>(result.size()));
    if (output) env->SetLongArrayRegion(output, 0, static_cast<jsize>(result.size()), result.data());
    return output;
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeLockNgxOutput(
        JNIEnv*, jclass, jlong sequence) {
    std::scoped_lock lock(mutex);
    if (sequence <= 0) return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    return static_cast<jint>(frameNgxEvaluator.lockOutput(static_cast<std::uint64_t>(sequence)));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeUnlockNgxOutput(
        JNIEnv*, jclass, jlong sequence) {
    std::scoped_lock lock(mutex);
    if (sequence <= 0) return static_cast<jint>(titanium::NativeError::NgxInvalidState);
    return static_cast<jint>(frameNgxEvaluator.unlockOutput(static_cast<std::uint64_t>(sequence)));
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativePollNgxValidation(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    return static_cast<jint>(frameNgxEvaluator.pollValidation());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeNgxValidationPassed(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    return frameNgxEvaluator.validationPassed() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeNgxOutputInteropPassed(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
    return frameNgxEvaluator.outputInteropPassed() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_celeste_titanium_client_nativebridge_NativeBridge_nativeShutdown(JNIEnv*, jclass) {
    std::scoped_lock lock(mutex);
#ifdef TITANIUM_TEST_NGX_HISTORY
    auto peerResult = historyPeer.shutdown();
    if (peerResult != titanium::NativeError::Ok) return static_cast<jint>(peerResult);
#endif
    auto ngxResult = frameNgxEvaluator.shutdown();
    if (ngxResult != titanium::NativeError::Ok) return static_cast<jint>(ngxResult);
    auto result = releaseFrameTexture();
    auto frameInteropResult = frameInterop.release(interop, frameInteropDiagnostics);
    auto interopResult = interop.shutdown(frameDiagnostics);
    auto contextResult = context.shutdown();
    if (result != titanium::NativeError::Ok) return static_cast<jint>(result);
    if (frameInteropResult != titanium::NativeError::Ok) return static_cast<jint>(frameInteropResult);
    if (interopResult != titanium::NativeError::Ok && interopResult != titanium::NativeError::AlreadyShutdown) {
        return static_cast<jint>(interopResult);
    }
    return static_cast<jint>(contextResult);
}
