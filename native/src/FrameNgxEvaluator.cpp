#include "FrameNgxEvaluator.hpp"
#ifdef TITANIUM_TEST_SHUTDOWN_TRACE
#include <cstdio>
#endif
#include "WglDxInterop.hpp"
#include "NgxGpuTiming.hpp"

#include <GL/gl.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <sstream>
#include <thread>
#include <wrl/client.h>
#ifdef TITANIUM_TEST_NGX_LOGGING
#include <fstream>
#include <mutex>
#endif

#if defined(TITANIUM_HAS_NGX)
#include <nvsdk_ngx.h>
#include <nvsdk_ngx_helpers.h>
#endif

namespace titanium {
namespace {
constexpr const char* ProjectId = "4c83510a-b053-46fa-82c0-c9318971ca15";
constexpr auto DrainTimeout = std::chrono::seconds(10);
constexpr unsigned ValidationExtent = 256;
constexpr std::size_t ValidationSlotCount = 4;
constexpr std::uint64_t RequiredResetValidations = 7;
constexpr std::uint64_t RequiredStaticValidations = 7;
constexpr std::uint64_t RequiredMotionValidations = 1;
constexpr std::uint64_t RequiredCoverageValidations = 3;

#if defined(TITANIUM_HAS_NGX)
#ifdef TITANIUM_TEST_NGX_LOGGING
std::mutex researchLogMutex;
std::ofstream researchLog;
void NVSDK_CONV researchLogCallback(const char* message, NVSDK_NGX_Logging_Level level,
        NVSDK_NGX_Feature source) {
    std::lock_guard<std::mutex> lock(researchLogMutex);
    if (researchLog) researchLog << static_cast<int>(level) << ',' << static_cast<int>(source)
            << ',' << (message ? message : "") << '\n';
}
#endif
NVSDK_NGX_PerfQuality_Value quality(NgxQualityMode mode) {
    switch (mode) {
        case NgxQualityMode::Performance:
            return NVSDK_NGX_PerfQuality_Value_MaxPerf;
        case NgxQualityMode::Quality:
            return NVSDK_NGX_PerfQuality_Value_MaxQuality;
        case NgxQualityMode::UltraQuality:
            return NVSDK_NGX_PerfQuality_Value_UltraQuality;
        case NgxQualityMode::Dlaa:
            return NVSDK_NGX_PerfQuality_Value_DLAA;
        case NgxQualityMode::Balanced:
            return NVSDK_NGX_PerfQuality_Value_Balanced;
        case NgxQualityMode::UltraPerformance:
            return NVSDK_NGX_PerfQuality_Value_UltraPerformance;
    }
    return NVSDK_NGX_PerfQuality_Value_MaxQuality;
}
#endif
}

struct FrameNgxEvaluator::Impl {
#if defined(TITANIUM_HAS_NGX)
    struct ValidationSlot {
        Microsoft::WRL::ComPtr<ID3D11Texture2D> staging;
        Microsoft::WRL::ComPtr<ID3D11Query> query;
        std::uint64_t sequence{};
        int kind{};
        int region{};
        bool pending{};
    };
    ID3D11Device* device{};
    ID3D11DeviceContext* context{};
    NgxGpuTiming timing;
    NVSDK_NGX_Parameter* parameters{};
    NVSDK_NGX_Handle* feature{};
    Microsoft::WRL::ComPtr<ID3D11Texture2D> output;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> unitExposure;
    WglDxInterop* outputInterop{};
    HANDLE outputObject{};
    std::array<ValidationSlot, ValidationSlotCount> validationSlots{};
    bool validationEnabled{};
    bool ngxInitialized{};
#ifdef TITANIUM_TEST_NGX_HISTORY
    bool borrowedRuntime{};
    NgxQualityMode historyMode{NgxQualityMode::Quality};
    bool historyDepthInverted{};
#endif
    bool forceReset{};
    bool outputLocked{};
    unsigned inputWidth{};
    unsigned inputHeight{};
    unsigned outputWidth{};
    unsigned outputHeight{};
    unsigned sampleWidth{};
    unsigned sampleHeight{};
    NgxRenderPreset preset{NgxRenderPreset::SdkDefault};
    bool autoExposure{true};
    FrameColorEncoding colorEncoding{FrameColorEncoding::LdrUnorm};
    std::uint64_t lastSequence{};
    std::uint64_t lockedOutputSequence{};
    std::uint64_t returnedOutputSequence{};
    std::uint64_t evaluates{};
    std::uint64_t resetEvaluates{};
    std::uint64_t validationSubmitted{};
    std::uint64_t validationCompleted{};
    std::uint64_t validationCancelled{};
    std::uint64_t validationSkipped{};
    std::array<std::uint64_t, 4> validationKinds{};
    std::array<std::uint64_t, 5> validationRegions{};
    unsigned featureCreates{};
    unsigned featureReleases{};
    unsigned outputRegistrations{};
    unsigned outputUnregistrations{};
    std::uint64_t outputLocks{};
    std::uint64_t outputUnlocks{};
    unsigned drains{};
    unsigned drainPolls{};
    long long drainMilliseconds{};
    std::string failure;
    std::string outputInteropDiagnostics;
#endif
};

FrameNgxEvaluator::FrameNgxEvaluator() : impl_(std::make_unique<Impl>()) { }
FrameNgxEvaluator::~FrameNgxEvaluator() { shutdown(); }

bool FrameNgxEvaluator::compiledWithNgx() const {
#if defined(TITANIUM_HAS_NGX)
    return true;
#else
    return false;
#endif
}

NativeError FrameNgxEvaluator::initialize(ID3D11Device* device, ID3D11DeviceContext* context,
        const std::wstring& logDirectory, const std::wstring& featureDirectory) {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::NgxUnavailable;
#else
    if (!device || !context || logDirectory.empty() || featureDirectory.empty() || impl_->ngxInitialized) {
        return NativeError::NgxInvalidState;
    }
    Microsoft::WRL::ComPtr<ID3D11Device> contextDevice;
    context->GetDevice(&contextDevice);
    if (contextDevice.Get() != device) return NativeError::NgxInvalidState;
    std::error_code directoryError;
    std::filesystem::create_directories(logDirectory, directoryError);
    if (directoryError) {
        impl_->failure = "logDirectory=" + directoryError.message();
        return NativeError::NgxInitializeFailed;
    }
    // 読み込み先として検証済みのDLSSディレクトリを指定し、同じD3D11デバイスでNGXを初期化する。
    const wchar_t* featurePaths[]{featureDirectory.c_str()};
    NVSDK_NGX_FeatureCommonInfo featureInfo{};
    featureInfo.PathListInfo.Path = featurePaths;
    featureInfo.PathListInfo.Length = 1;
#ifdef TITANIUM_TEST_NGX_LOGGING
    researchLog.open(std::filesystem::path(logDirectory) / "ngx-runtime-audit.log", std::ios::out);
    if (!researchLog) return NativeError::NgxInitializeFailed;
    featureInfo.LoggingInfo.LoggingCallback = researchLogCallback;
    featureInfo.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_VERBOSE;
    featureInfo.LoggingInfo.DisableOtherLoggingSinks = true;
#endif
    auto result = NVSDK_NGX_D3D11_Init_with_ProjectID(ProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM,
            "Titanium-P9-1", logDirectory.c_str(), device, &featureInfo);
    if (NVSDK_NGX_FAILED(result)) {
        impl_->failure = "init=" + std::to_string(static_cast<unsigned>(result));
        return NativeError::NgxInitializeFailed;
    }
    impl_->ngxInitialized = true;
    impl_->device = device;
    impl_->context = context;
    result = NVSDK_NGX_D3D11_GetCapabilityParameters(&impl_->parameters);
    if (NVSDK_NGX_FAILED(result) || !impl_->parameters) {
        impl_->failure = "capabilities=" + std::to_string(static_cast<unsigned>(result));
        shutdown();
        return NativeError::NgxInitializeFailed;
    }
    // NGX初期化成功後もDLSSの利用可否を確認し、非対応時はドライバー条件を診断へ残す。
    int available{};
    result = NVSDK_NGX_Parameter_GetI(impl_->parameters,
            NVSDK_NGX_Parameter_SuperSampling_Available, &available);
    if (NVSDK_NGX_FAILED(result) || !available) {
        auto parameter = [this](const char* name, int& value) {
            value = -1;
            return NVSDK_NGX_Parameter_GetI(impl_->parameters, name, &value);
        };
        int needsUpdatedDriver{};
        int minimumDriverMajor{};
        int minimumDriverMinor{};
        int featureInitResult{};
        const auto needsResult = parameter(
                NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, needsUpdatedDriver);
        const auto majorResult = parameter(
                NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, minimumDriverMajor);
        const auto minorResult = parameter(
                NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMinor, minimumDriverMinor);
        const auto featureResult = parameter(
                NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, featureInitResult);
        impl_->failure = "dlssAvailable=" + std::to_string(available)
                + ",result=" + std::to_string(static_cast<unsigned>(result))
                + ",needsDriver=" + std::to_string(needsUpdatedDriver)
                + "(" + std::to_string(static_cast<unsigned>(needsResult)) + ")"
                + ",minDriver=" + std::to_string(minimumDriverMajor) + "."
                + std::to_string(minimumDriverMinor)
                + "(" + std::to_string(static_cast<unsigned>(majorResult)) + ","
                + std::to_string(static_cast<unsigned>(minorResult)) + ")"
                + ",featureInit=" + std::to_string(featureInitResult)
                + "(" + std::to_string(static_cast<unsigned>(featureResult)) + ")";
        shutdown();
        return NativeError::NgxUnavailable;
    }
    return NativeError::Ok;
#endif
}

NativeError FrameNgxEvaluator::queryOptimalSettings(unsigned outputWidth, unsigned outputHeight,
        NgxQualityMode mode, NgxOptimalSettings& settings) {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::NgxUnavailable;
#else
    if (!impl_->ngxInitialized || !impl_->parameters || !outputWidth || !outputHeight) {
        return NativeError::NgxInvalidState;
    }
    float sharpness{};
    auto result = NGX_DLSS_GET_OPTIMAL_SETTINGS(impl_->parameters, outputWidth, outputHeight, quality(mode),
            &settings.width, &settings.height, &settings.maximumWidth, &settings.maximumHeight,
            &settings.minimumWidth, &settings.minimumHeight, &sharpness);
    if (NVSDK_NGX_FAILED(result) || !settings.width || !settings.height) {
        impl_->failure = "optimalSettings=" + std::to_string(static_cast<unsigned>(result));
        return NativeError::NgxOptimalSettingsFailed;
    }
    return NativeError::Ok;
#endif
}

NativeError FrameNgxEvaluator::createFeature(unsigned inputWidth, unsigned inputHeight,
        unsigned outputWidth, unsigned outputHeight, NgxQualityMode mode, bool depthInverted,
        bool validationEnabled, NgxRenderPreset preset, bool autoExposure, FrameColorEncoding colorEncoding) {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::NgxUnavailable;
#else
    if (!impl_->ngxInitialized || !impl_->parameters || impl_->feature || impl_->output
            || impl_->outputObject || impl_->outputLocked
            || !inputWidth || !inputHeight || !outputWidth || !outputHeight
            || (colorEncoding != FrameColorEncoding::LdrUnorm && colorEncoding != FrameColorEncoding::LinearHdrFloat)
            // バイト形式の診断リングでは浮動小数点出力を検証できないため、別の読み戻しを使用する。
            || (colorEncoding == FrameColorEncoding::LinearHdrFloat && validationEnabled)
            || (!autoExposure && preset != NgxRenderPreset::K)
            || (preset != NgxRenderPreset::SdkDefault && preset != NgxRenderPreset::J
                && preset != NgxRenderPreset::K && preset != NgxRenderPreset::L
                && preset != NgxRenderPreset::M)) {
        return NativeError::NgxInvalidState;
    }
    // SDK推奨の入力寸法と比較し、整数丸めによる1ピクセル差だけを許容する。
    NgxOptimalSettings optimal;
    auto optimalResult = queryOptimalSettings(outputWidth, outputHeight, mode, optimal);
    if (optimalResult != NativeError::Ok) return optimalResult;
    auto difference = [](unsigned left, unsigned right) {
        return std::llabs(static_cast<long long>(left) - static_cast<long long>(right));
    };
    if (difference(inputWidth, optimal.width) > 1 || difference(inputHeight, optimal.height) > 1) {
        impl_->failure = "actualInput=" + std::to_string(inputWidth) + "x" + std::to_string(inputHeight)
                + ",optimal=" + std::to_string(optimal.width) + "x" + std::to_string(optimal.height);
        return NativeError::NgxOptimalSettingsFailed;
    }
    // 出力はFeatureが所有するD3D11テクスチャとし、後からWGL経由でGLへ共有する。
    D3D11_TEXTURE2D_DESC outputDescription{};
    outputDescription.Width = outputWidth;
    outputDescription.Height = outputHeight;
    outputDescription.MipLevels = 1;
    outputDescription.ArraySize = 1;
    outputDescription.Format = colorEncoding == FrameColorEncoding::LinearHdrFloat
            ? DXGI_FORMAT_R16G16B16A16_FLOAT : DXGI_FORMAT_R8G8B8A8_UNORM;
    outputDescription.SampleDesc.Count = 1;
    outputDescription.Usage = D3D11_USAGE_DEFAULT;
    outputDescription.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_UNORDERED_ACCESS
            | D3D11_BIND_RENDER_TARGET;
    if (FAILED(impl_->device->CreateTexture2D(&outputDescription, nullptr, &impl_->output))) {
        impl_->failure = "outputTextureCreate";
        return NativeError::NgxFeatureCreateFailed;
    }
    impl_->validationEnabled = validationEnabled;
    if (validationEnabled) {
        impl_->sampleWidth = std::min(outputWidth, ValidationExtent);
        impl_->sampleHeight = std::min(outputHeight, ValidationExtent);
        D3D11_TEXTURE2D_DESC stagingDescription{};
        stagingDescription.Width = impl_->sampleWidth;
        stagingDescription.Height = impl_->sampleHeight;
        stagingDescription.MipLevels = 1;
        stagingDescription.ArraySize = 1;
        stagingDescription.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
        stagingDescription.SampleDesc.Count = 1;
        stagingDescription.Usage = D3D11_USAGE_STAGING;
        stagingDescription.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        D3D11_QUERY_DESC queryDescription{};
        queryDescription.Query = D3D11_QUERY_EVENT;
        for (auto& slot : impl_->validationSlots) {
            if (FAILED(impl_->device->CreateTexture2D(&stagingDescription, nullptr, &slot.staging))
                    || FAILED(impl_->device->CreateQuery(&queryDescription, &slot.query))) {
                impl_->failure = "outputValidationResources";
                for (auto& cleanup : impl_->validationSlots) {
                    cleanup.staging.Reset();
                    cleanup.query.Reset();
                }
                impl_->output.Reset();
                impl_->validationEnabled = false;
                return NativeError::NgxFeatureCreateFailed;
            }
        }
    }
    if (!autoExposure) {
        D3D11_TEXTURE2D_DESC description{};
        description.Width = description.Height = description.MipLevels = description.ArraySize = 1;
        description.SampleDesc.Count = 1;
        description.Format = DXGI_FORMAT_R32_FLOAT;
        description.Usage = D3D11_USAGE_IMMUTABLE;
        description.BindFlags = D3D11_BIND_SHADER_RESOURCE;
        const float exposure = 1.0f;
        D3D11_SUBRESOURCE_DATA initial{&exposure, sizeof(exposure), 0};
        if (FAILED(impl_->device->CreateTexture2D(&description, &initial, &impl_->unitExposure))) {
            impl_->failure = "researchUnitExposureCreate";
            impl_->output.Reset();
            return NativeError::NgxFeatureCreateFailed;
        }
    }
    // 入力サイズ・出力サイズ・深度の向き・露出・低解像度MVを、Feature生成時の契約として固定する。
    NVSDK_NGX_DLSS_Create_Params create{};
    create.Feature.InWidth = inputWidth;
    create.Feature.InHeight = inputHeight;
    create.Feature.InTargetWidth = outputWidth;
    create.Feature.InTargetHeight = outputHeight;
    create.Feature.InPerfQualityValue = quality(mode);
    create.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes;
    if (colorEncoding == FrameColorEncoding::LinearHdrFloat)
        create.InFeatureCreateFlags |= NVSDK_NGX_DLSS_Feature_Flags_IsHDR;
    if (autoExposure) create.InFeatureCreateFlags |= NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    if (depthInverted) create.InFeatureCreateFlags |= NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
    // プリセットは公開SDKのHintへ設定する。実際に選択されたモデルの読み戻し値としては扱わない。
    const auto presetValue = static_cast<unsigned>(preset);
    NVSDK_NGX_Parameter_SetUI(impl_->parameters,
            NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, presetValue);
    NVSDK_NGX_Parameter_SetUI(impl_->parameters,
            NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance, presetValue);
    NVSDK_NGX_Parameter_SetUI(impl_->parameters,
            NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, presetValue);
    NVSDK_NGX_Parameter_SetUI(impl_->parameters,
            NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, presetValue);
    NVSDK_NGX_Parameter_SetUI(impl_->parameters,
            NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, presetValue);
    auto result = NGX_D3D11_CREATE_DLSS_EXT(impl_->context, &impl_->feature, impl_->parameters, &create);
    if (NVSDK_NGX_FAILED(result) || !impl_->feature) {
        impl_->failure = "featureCreate=" + std::to_string(static_cast<unsigned>(result));
        for (auto& slot : impl_->validationSlots) {
            slot.query.Reset();
            slot.staging.Reset();
        }
        impl_->sampleWidth = 0;
        impl_->sampleHeight = 0;
        impl_->validationEnabled = false;
        impl_->output.Reset();
        impl_->unitExposure.Reset();
        return NativeError::NgxFeatureCreateFailed;
    }
    impl_->inputWidth = inputWidth;
    impl_->inputHeight = inputHeight;
    impl_->outputWidth = outputWidth;
    impl_->outputHeight = outputHeight;
    impl_->preset = preset;
    impl_->autoExposure = autoExposure;
    impl_->colorEncoding = colorEncoding;
#ifdef TITANIUM_TEST_NGX_HISTORY
    impl_->historyMode = mode;
    impl_->historyDepthInverted = depthInverted;
#endif
    // フレーム番号はFeatureの世代内で管理する。ワールドやセッションの再生成後は1から再開できる。
    impl_->lastSequence = 0;
    impl_->lockedOutputSequence = 0;
    impl_->returnedOutputSequence = 0;
    impl_->forceReset = true;
    impl_->featureCreates++;
    return NativeError::Ok;
#endif
}

NativeError FrameNgxEvaluator::evaluate(ID3D11Texture2D* color, ID3D11Texture2D* depth,
        ID3D11Texture2D* motion, std::uint64_t sequence, bool reset, float jitterX, float jitterY,
        float frameTimeMilliseconds, int validationKind
#if defined(TITANIUM_TEST_NGX_BIAS_MASK)
        , ID3D11Texture2D* biasCurrentColor
#endif
        ) {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::NgxUnavailable;
#else
    if (!featureReady() || impl_->outputLocked || !color || !depth || !motion
            || !sequence || sequence <= impl_->lastSequence
            || !std::isfinite(jitterX) || !std::isfinite(jitterY)
            || jitterX < -0.5f || jitterX >= 0.5f || jitterY < -0.5f || jitterY >= 0.5f
            || !std::isfinite(frameTimeMilliseconds) || frameTimeMilliseconds <= 0.0f
            || frameTimeMilliseconds > 1000.0f || validationKind < 0 || validationKind > 3) {
        impl_->failure = "invalidEvaluateMetadata";
        return NativeError::NgxInvalidState;
    }
    if (!impl_->validationEnabled && validationKind != 0) return NativeError::NgxInvalidState;
    // 実テクスチャの寸法・形式・サンプル数をFeature生成時の入力契約と照合する。
    auto matches = [this](ID3D11Texture2D* texture, DXGI_FORMAT format) {
        D3D11_TEXTURE2D_DESC description{};
        texture->GetDesc(&description);
        return description.Width == impl_->inputWidth && description.Height == impl_->inputHeight
                && description.Format == format && description.SampleDesc.Count == 1;
    };
    const auto colorFormat = impl_->colorEncoding == FrameColorEncoding::LinearHdrFloat
            ? DXGI_FORMAT_R16G16B16A16_FLOAT : DXGI_FORMAT_R8G8B8A8_UNORM;
    if (!matches(color, colorFormat) || !matches(depth, DXGI_FORMAT_R32_FLOAT)
            || !matches(motion, DXGI_FORMAT_R32G32_FLOAT)) {
        impl_->failure = "inputTextureContract";
        return NativeError::NgxInvalidState;
    }
    NVSDK_NGX_D3D11_DLSS_Eval_Params evaluate{};
    evaluate.Feature.pInColor = color;
    evaluate.Feature.pInOutput = impl_->output.Get();
    evaluate.pInDepth = depth;
    evaluate.pInMotionVectors = motion;
    evaluate.pInExposureTexture = impl_->unitExposure.Get();
#if defined(TITANIUM_TEST_NGX_BIAS_MASK)
    // Standalone source-texel experiment only; the product ABI has no mask input.
    if (biasCurrentColor) {
        D3D11_TEXTURE2D_DESC description{};
        biasCurrentColor->GetDesc(&description);
        if (!matches(biasCurrentColor, DXGI_FORMAT_R8_UNORM)
                || description.MipLevels != 1 || description.ArraySize != 1
                || !(description.BindFlags & D3D11_BIND_SHADER_RESOURCE)) {
            impl_->failure = "biasMaskTextureContract";
            return NativeError::NgxInvalidState;
        }
    }
    evaluate.pInBiasCurrentColorMask = biasCurrentColor;
#endif
    evaluate.InRenderSubrectDimensions = {impl_->inputWidth, impl_->inputHeight};
    const bool effectiveReset = reset || impl_->forceReset;
    evaluate.InReset = effectiveReset ? 1 : 0;
    evaluate.InJitterOffsetX = jitterX;
    evaluate.InJitterOffsetY = jitterY;
    evaluate.InMVScaleX = 1.0f;
    evaluate.InMVScaleY = 1.0f;
    evaluate.InPreExposure = 1.0f;
    evaluate.InExposureScale = 1.0f;
    evaluate.InFrameTimeDeltaInMsec = frameTimeMilliseconds;
    // 診断が有効な場合だけ評価区間をタイムスタンプで囲み、評価成功後にシーケンスを進める。
    impl_->timing.begin(impl_->context, sequence);
    auto result = NGX_D3D11_EVALUATE_DLSS_EXT(
            impl_->context, impl_->feature, impl_->parameters, &evaluate);
    impl_->timing.end(impl_->context);
    if (NVSDK_NGX_FAILED(result)) {
        impl_->failure = "evaluate=" + std::to_string(static_cast<unsigned>(result));
        return NativeError::NgxEvaluateFailed;
    }
    impl_->forceReset = false;
    impl_->lastSequence = sequence;
    impl_->evaluates++;
    if (effectiveReset) impl_->resetEvaluates++;
    if (validationKind == 0) return NativeError::Ok;
    auto slot = std::find_if(impl_->validationSlots.begin(), impl_->validationSlots.end(),
            [](const Impl::ValidationSlot& candidate) { return !candidate.pending; });
    if (slot == impl_->validationSlots.end()) {
        impl_->validationSkipped++;
        impl_->failure = "outputValidationRingFull";
        return NativeError::NgxOutputValidationFailed;
    }
    const int validationRegion = static_cast<int>(impl_->validationSubmitted % 5);
    D3D11_BOX region{};
    region.left = validationRegion == 0 ? (impl_->outputWidth - impl_->sampleWidth) / 2
            : validationRegion == 2 || validationRegion == 4
                    ? impl_->outputWidth - impl_->sampleWidth : 0;
    region.top = validationRegion == 0 ? (impl_->outputHeight - impl_->sampleHeight) / 2
            : validationRegion == 3 || validationRegion == 4
                    ? impl_->outputHeight - impl_->sampleHeight : 0;
    region.right = region.left + impl_->sampleWidth;
    region.bottom = region.top + impl_->sampleHeight;
    region.front = 0;
    region.back = 1;
    impl_->context->CopySubresourceRegion(slot->staging.Get(), 0, 0, 0, 0,
            impl_->output.Get(), 0, &region);
    impl_->context->End(slot->query.Get());
    slot->sequence = sequence;
    slot->kind = validationKind;
    slot->region = validationRegion;
    slot->pending = true;
    impl_->validationSubmitted++;
    return NativeError::Ok;
#endif
}

NativeError FrameNgxEvaluator::configureTiming(bool enabled) {
#if !defined(TITANIUM_HAS_NGX)
    return enabled ? NativeError::NgxUnavailable : NativeError::Ok;
#else
    return impl_->timing.configure(impl_->device, enabled) ? NativeError::Ok : NativeError::NgxInvalidState;
#endif
}

NativeError FrameNgxEvaluator::armTiming(std::uint64_t sequence) {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::NgxUnavailable;
#else
    return impl_->timing.arm(sequence) ? NativeError::Ok : NativeError::NgxInvalidState;
#endif
}

std::vector<std::int64_t> FrameNgxEvaluator::pollTiming() {
#if !defined(TITANIUM_HAS_NGX)
    return {1, 0, 0, 0, 0, 0, 0};
#else
    return impl_->timing.poll(impl_->context);
#endif
}

NativeError FrameNgxEvaluator::registerOutput(WglDxInterop& interop, unsigned glName) {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::NgxUnavailable;
#else
    if (!featureReady() || !interop.initialized() || !glName || impl_->outputObject
            || impl_->outputLocked || impl_->outputInterop) {
        return NativeError::NgxInvalidState;
    }
    impl_->outputObject = interop.registerTexture(impl_->output.Get(), glName, GL_TEXTURE_2D,
            WglDxInterop::AccessReadOnly, impl_->outputInteropDiagnostics);
    if (!impl_->outputObject) {
        impl_->failure = "outputRegister;" + impl_->outputInteropDiagnostics;
        return NativeError::WglRegisterFailed;
    }
    impl_->outputInterop = &interop;
    impl_->outputRegistrations++;
    return NativeError::Ok;
#endif
}

NativeError FrameNgxEvaluator::lockOutput(std::uint64_t sequence) {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::NgxUnavailable;
#else
    if (!featureReady() || !impl_->outputInterop || !impl_->outputObject || impl_->outputLocked
            || !sequence || sequence != impl_->lastSequence || sequence <= impl_->returnedOutputSequence) {
        return NativeError::NgxInvalidState;
    }
    auto result = impl_->outputInterop->lock(impl_->outputObject, impl_->outputInteropDiagnostics);
    if (result != NativeError::Ok) {
        impl_->failure = "outputLock;" + impl_->outputInteropDiagnostics;
        return result;
    }
    impl_->outputLocked = true;
    // GLへ渡した評価番号を記録し、別シーケンスの解除や同じ出力の重複返却を防ぐ。
    impl_->lockedOutputSequence = sequence;
    impl_->outputLocks++;
    return NativeError::Ok;
#endif
}

NativeError FrameNgxEvaluator::unlockOutput(std::uint64_t sequence) {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::NgxUnavailable;
#else
    if (!impl_->outputInterop || !impl_->outputObject || !impl_->outputLocked
            || !sequence || sequence != impl_->lockedOutputSequence) {
        return NativeError::NgxInvalidState;
    }
    auto result = impl_->outputInterop->unlock(impl_->outputObject, impl_->outputInteropDiagnostics);
    if (result != NativeError::Ok) {
        impl_->failure = "outputUnlock;" + impl_->outputInteropDiagnostics;
        return result;
    }
    impl_->outputLocked = false;
    impl_->lockedOutputSequence = 0;
    impl_->returnedOutputSequence = sequence;
    impl_->outputUnlocks++;
    return NativeError::Ok;
#endif
}

bool FrameNgxEvaluator::readReturnedOutputRoi(std::uint64_t sequence, unsigned x, unsigned y,
        unsigned size, std::vector<unsigned char>& output) {
    output.clear();
#if !defined(TITANIUM_HAS_NGX)
    return false;
#else
    if (impl_->colorEncoding != FrameColorEncoding::LdrUnorm
            || !featureReady() || !impl_->device || !impl_->context || impl_->outputLocked
            || !impl_->outputObject || !sequence || sequence != impl_->lastSequence
            || sequence != impl_->returnedOutputSequence || size == 0 || size > 64
            || size > impl_->outputWidth || size > impl_->outputHeight
            || x > impl_->outputWidth - size || y > impl_->outputHeight - size) return false;
    D3D11_TEXTURE2D_DESC description{};
    impl_->output->GetDesc(&description);
    description.Width = size;
    description.Height = size;
    description.Usage = D3D11_USAGE_STAGING;
    description.BindFlags = 0;
    description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    description.MiscFlags = 0;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> staging;
    if (FAILED(impl_->device->CreateTexture2D(&description, nullptr, &staging))) return false;
    std::vector<unsigned char> data(size * size * 4);
    D3D11_BOX box{x, y, 0, x + size, y + size, 1};
    impl_->context->CopySubresourceRegion(staging.Get(), 0, 0, 0, 0, impl_->output.Get(), 0, &box);
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(impl_->context->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;
    for (unsigned row = 0; row < size; ++row)
        std::memcpy(data.data() + row * size * 4,
                static_cast<const unsigned char*>(mapped.pData) + row * mapped.RowPitch, size * 4);
    impl_->context->Unmap(staging.Get(), 0);
    output.swap(data);
    return true;
#endif
}

NativeError FrameNgxEvaluator::pollValidation() {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::Ok;
#else
    if (!impl_->ngxInitialized || !impl_->validationEnabled) return NativeError::NgxInvalidState;
    if (!impl_->failure.empty()) return NativeError::NgxOutputValidationFailed;
    for (auto& slot : impl_->validationSlots) {
        if (!slot.pending) continue;
        HRESULT result = impl_->context->GetData(slot.query.Get(), nullptr, 0,
                D3D11_ASYNC_GETDATA_DONOTFLUSH);
        if (result == S_FALSE) continue;
        if (FAILED(result)) {
            impl_->failure = "outputValidationGetData=" + std::to_string(static_cast<unsigned>(result));
            return NativeError::NgxOutputValidationFailed;
        }
        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (FAILED(impl_->context->Map(slot.staging.Get(), 0, D3D11_MAP_READ, 0, &mapped))) {
            impl_->failure = "outputValidationMap";
            return NativeError::NgxOutputValidationFailed;
        }
        const auto* first = static_cast<const unsigned char*>(mapped.pData);
        bool nonzero{};
        bool nonuniform{};
        for (unsigned y = 0; y < impl_->sampleHeight; ++y) {
            const auto* row = static_cast<const unsigned char*>(mapped.pData) + y * mapped.RowPitch;
            for (unsigned x = 0; x < impl_->sampleWidth; ++x) {
                const auto* pixel = row + x * 4;
                nonzero = nonzero || pixel[0] != 0 || pixel[1] != 0 || pixel[2] != 0;
                nonuniform = nonuniform || std::memcmp(pixel, first, 4) != 0;
            }
        }
        impl_->context->Unmap(slot.staging.Get(), 0);
        slot.pending = false;
        if (!nonzero || slot.region == 0 && !nonuniform) {
            impl_->failure = "outputContent;sequence=" + std::to_string(slot.sequence)
                    + ",kind=" + std::to_string(slot.kind) + ",region=" + std::to_string(slot.region)
                    + ",nonzero=" + std::to_string(nonzero)
                    + ",nonuniform=" + std::to_string(nonuniform);
            return NativeError::NgxOutputValidationFailed;
        }
        impl_->validationCompleted++;
        impl_->validationKinds[slot.kind]++;
        impl_->validationRegions[slot.region]++;
    }
    return NativeError::Ok;
#endif
}

NativeError FrameNgxEvaluator::releaseFeature() {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::Ok;
#else
    if (impl_->outputLocked) {
        auto unlockResult = impl_->outputInterop->unlock(
                impl_->outputObject, impl_->outputInteropDiagnostics);
        if (unlockResult != NativeError::Ok) {
            impl_->failure = "outputUnlock(release);" + impl_->outputInteropDiagnostics;
            return unlockResult;
        }
        impl_->outputLocked = false;
        impl_->lockedOutputSequence = 0;
        impl_->outputUnlocks++;
    }
    if (!impl_->feature) {
        if (impl_->outputObject || impl_->outputInterop) return NativeError::NgxInvalidState;
        impl_->output.Reset();
        impl_->unitExposure.Reset();
        return NativeError::Ok;
    }
    D3D11_QUERY_DESC queryDescription{};
    queryDescription.Query = D3D11_QUERY_EVENT;
    Microsoft::WRL::ComPtr<ID3D11Query> completion;
    if (FAILED(impl_->device->CreateQuery(&queryDescription, &completion))) {
        impl_->failure = "drainQueryCreate";
        return NativeError::NgxReleaseFailed;
    }
    impl_->context->End(completion.Get());
    impl_->context->Flush();
    impl_->drains++;
    const auto started = std::chrono::steady_clock::now();
    const auto deadline = started + DrainTimeout;
    for (;;) {
        impl_->drainPolls++;
        HRESULT status = impl_->context->GetData(completion.Get(), nullptr, 0,
                D3D11_ASYNC_GETDATA_DONOTFLUSH);
        if (status == S_OK) break;
        if (status != S_FALSE || std::chrono::steady_clock::now() >= deadline) {
            impl_->failure = status == S_FALSE ? "drainTimeout"
                    : "drainGetData=" + std::to_string(static_cast<unsigned>(status));
            return NativeError::NgxReleaseFailed;
        }
        std::this_thread::yield();
    }
    impl_->drainMilliseconds += std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count();
    if (impl_->validationEnabled && impl_->failure.empty()) pollValidation();
    if (impl_->outputObject) {
        auto unregisterResult = impl_->outputInterop->unregisterObject(
                impl_->outputObject, impl_->outputInteropDiagnostics);
        if (unregisterResult != NativeError::Ok) {
            impl_->failure = "outputUnregister;" + impl_->outputInteropDiagnostics;
            return unregisterResult;
        }
        impl_->outputObject = nullptr;
        impl_->outputInterop = nullptr;
        impl_->outputUnregistrations++;
    }
    auto result = NVSDK_NGX_D3D11_ReleaseFeature(impl_->feature);
    if (NVSDK_NGX_FAILED(result)) {
        impl_->failure = "featureRelease=" + std::to_string(static_cast<unsigned>(result));
        return NativeError::NgxReleaseFailed;
    }
    impl_->feature = nullptr;
    impl_->output.Reset();
    impl_->unitExposure.Reset();
    for (auto& slot : impl_->validationSlots) {
        if (slot.pending) impl_->validationCancelled++;
        slot.pending = false;
        slot.sequence = 0;
        slot.kind = 0;
        slot.query.Reset();
        slot.staging.Reset();
    }
    impl_->sampleWidth = 0;
    impl_->sampleHeight = 0;
    impl_->inputWidth = 0;
    impl_->inputHeight = 0;
    impl_->outputWidth = 0;
    impl_->outputHeight = 0;
    impl_->lockedOutputSequence = 0;
    impl_->featureReleases++;
    return NativeError::Ok;
#endif
}

#ifdef TITANIUM_TEST_NGX_HISTORY
NativeError FrameNgxEvaluator::createHistoryPeer(const FrameNgxEvaluator& primary) {
    const auto& source = *primary.impl_;
    if (&primary == this || !primary.featureReady() || source.borrowedRuntime
            || source.historyMode != NgxQualityMode::Performance || source.historyDepthInverted
            || source.preset != NgxRenderPreset::SdkDefault || !source.autoExposure
            || source.colorEncoding != FrameColorEncoding::LdrUnorm
            || impl_->feature || (impl_->ngxInitialized && !impl_->borrowedRuntime))
        return NativeError::NgxInvalidState;
    if (!impl_->ngxInitialized) {
        NVSDK_NGX_Parameter* parameters{};
        auto result = NVSDK_NGX_D3D11_GetCapabilityParameters(&parameters);
        if (NVSDK_NGX_FAILED(result) || !parameters) return NativeError::NgxUnavailable;
        // A shared parameter owner would invalidate this experiment and its cleanup.
        if (parameters == source.parameters) return NativeError::NgxInvalidState;
        impl_->parameters = parameters;
        impl_->device = source.device;
        impl_->context = source.context;
        impl_->borrowedRuntime = true;
        impl_->ngxInitialized = true;
    } else if (impl_->device != source.device || impl_->context != source.context) {
        return NativeError::NgxInvalidState;
    }
    return createFeature(source.inputWidth, source.inputHeight, source.outputWidth, source.outputHeight,
            source.historyMode, source.historyDepthInverted, false, source.preset,
            source.autoExposure, source.colorEncoding);
}
#endif

NativeError FrameNgxEvaluator::shutdown() {
#if !defined(TITANIUM_HAS_NGX)
    return NativeError::Ok;
#else
    impl_->timing.clear();
    auto releaseResult = releaseFeature();
    if (releaseResult != NativeError::Ok) return releaseResult;
    if (impl_->parameters) {
#ifdef TITANIUM_TEST_SHUTDOWN_TRACE
        std::fprintf(stderr, "NGX DestroyParameters:start\n"); std::fflush(stderr);
#endif
        auto result = NVSDK_NGX_D3D11_DestroyParameters(impl_->parameters);
#ifdef TITANIUM_TEST_SHUTDOWN_TRACE
        std::fprintf(stderr, "NGX DestroyParameters:return\n"); std::fflush(stderr);
#endif
        if (NVSDK_NGX_FAILED(result)) {
            impl_->failure = "parametersDestroy=" + std::to_string(static_cast<unsigned>(result));
            return NativeError::NgxReleaseFailed;
        }
        impl_->parameters = nullptr;
    }
    if (impl_->ngxInitialized
#ifdef TITANIUM_TEST_NGX_HISTORY
            && !impl_->borrowedRuntime
#endif
            ) {
#ifdef TITANIUM_TEST_SHUTDOWN_TRACE
        std::fprintf(stderr, "NGX Shutdown1:start\n"); std::fflush(stderr);
#endif
        auto result = NVSDK_NGX_D3D11_Shutdown1(impl_->device);
#ifdef TITANIUM_TEST_SHUTDOWN_TRACE
        std::fprintf(stderr, "NGX Shutdown1:return\n"); std::fflush(stderr);
#endif
        if (NVSDK_NGX_FAILED(result)) {
            impl_->failure = "shutdown=" + std::to_string(static_cast<unsigned>(result));
            return NativeError::NgxReleaseFailed;
        }
        impl_->ngxInitialized = false;
    }
    impl_->device = nullptr;
    impl_->context = nullptr;
#ifdef TITANIUM_TEST_NGX_HISTORY
    impl_->ngxInitialized = false;
    impl_->borrowedRuntime = false;
#endif
    return NativeError::Ok;
#endif
}

bool FrameNgxEvaluator::initialized() const {
#if defined(TITANIUM_HAS_NGX)
    return impl_->ngxInitialized;
#else
    return false;
#endif
}

bool FrameNgxEvaluator::featureReady() const {
#if defined(TITANIUM_HAS_NGX)
    return impl_->feature != nullptr && impl_->output != nullptr;
#else
    return false;
#endif
}

bool FrameNgxEvaluator::validationPassed() const {
#if !defined(TITANIUM_HAS_NGX)
    return false;
#else
    if (!impl_->validationEnabled) return false;
    bool pending = std::any_of(impl_->validationSlots.begin(), impl_->validationSlots.end(),
            [](const Impl::ValidationSlot& slot) { return slot.pending; });
    return !pending && impl_->failure.empty() && impl_->validationCancelled == 0
            && impl_->validationSkipped == 0
            && impl_->validationKinds[1] >= RequiredResetValidations
            && impl_->validationKinds[2] >= RequiredStaticValidations
            && impl_->validationKinds[3] >= RequiredMotionValidations
            && std::all_of(impl_->validationRegions.begin(), impl_->validationRegions.end(),
                    [](std::uint64_t count) { return count >= RequiredCoverageValidations; });
#endif
}

bool FrameNgxEvaluator::outputInteropPassed() const {
#if !defined(TITANIUM_HAS_NGX)
    return false;
#else
    const bool registrationBalanced = impl_->outputObject
            ? impl_->outputRegistrations == impl_->outputUnregistrations + 1
            : impl_->outputRegistrations == impl_->outputUnregistrations;
    return impl_->outputRegistrations > 0 && registrationBalanced && !impl_->outputLocked
            && impl_->outputLocks == impl_->evaluates && impl_->outputUnlocks == impl_->outputLocks
            && impl_->returnedOutputSequence == impl_->lastSequence;
#endif
}

ID3D11Texture2D* FrameNgxEvaluator::output() const {
#if defined(TITANIUM_HAS_NGX)
    return impl_->output.Get();
#else
    return nullptr;
#endif
}

std::string FrameNgxEvaluator::diagnostics() const {
#if !defined(TITANIUM_HAS_NGX)
    return "P9 Direct NGX unavailable in product native build";
#else
    std::ostringstream output;
    const auto pending = std::count_if(impl_->validationSlots.begin(), impl_->validationSlots.end(),
            [](const Impl::ValidationSlot& slot) { return slot.pending; });
    const auto validationResources = std::count_if(impl_->validationSlots.begin(),
            impl_->validationSlots.end(), [](const Impl::ValidationSlot& slot) {
                return slot.staging != nullptr;
            }) + std::count_if(impl_->validationSlots.begin(), impl_->validationSlots.end(),
            [](const Impl::ValidationSlot& slot) { return slot.query != nullptr; });
    output << "compiled=true,initialized=" << initialized() << ",featureReady=" << featureReady()
           << ",preset=" << static_cast<int>(impl_->preset)
           << ",exposure=" << (impl_->autoExposure ? "AUTO" : "UNIT_1")
           << ",colorEncoding=" << (impl_->colorEncoding == FrameColorEncoding::LinearHdrFloat ? "LINEAR_HDR_FLOAT" : "LDR_UNORM")
           << ",exposureResource=" << (impl_->unitExposure ? 1 : 0)
           << ",creates=" << impl_->featureCreates << ",releases=" << impl_->featureReleases
           << ",outputInterop={registered=" << (impl_->outputObject != nullptr)
           << ",locked=" << impl_->outputLocked
           << ",registrations=" << impl_->outputRegistrations
           << ",unregistrations=" << impl_->outputUnregistrations
           << ",locks=" << impl_->outputLocks << ",unlocks=" << impl_->outputUnlocks
           << ",returnedSequence=" << impl_->returnedOutputSequence << "}"
           << ",evaluates=" << impl_->evaluates << ",resets=" << impl_->resetEvaluates
           << ",drains=" << impl_->drains << ",drainPolls=" << impl_->drainPolls
           << ",drainMs=" << impl_->drainMilliseconds << ",validation={enabled="
           << impl_->validationEnabled << ",resources=" << validationResources << ",submitted="
           << impl_->validationSubmitted << ",completed=" << impl_->validationCompleted
           << ",pending=" << pending << ",cancelled=" << impl_->validationCancelled
           << ",skipped=" << impl_->validationSkipped << ",reset=" << impl_->validationKinds[1]
           << ",static=" << impl_->validationKinds[2] << ",motion=" << impl_->validationKinds[3]
           << ",regions=" << impl_->validationRegions[0] << '/' << impl_->validationRegions[1]
           << '/' << impl_->validationRegions[2] << '/' << impl_->validationRegions[3]
           << '/' << impl_->validationRegions[4] << "}"
           << ",failure=" << (impl_->failure.empty() ? "none" : impl_->failure);
    return output.str();
#endif
}

}
