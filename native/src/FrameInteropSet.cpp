#include "FrameInteropSet.hpp"
#include <GL/gl.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>
#include <vector>

namespace titanium {
bool FrameInteropSet::readRoi(ID3D11Device* device, ID3D11DeviceContext* context,
        std::int64_t sequence, unsigned x, unsigned y, unsigned size,
        std::vector<unsigned char>& output) {
    output.clear();
    if (colorEncoding_ != FrameColorEncoding::LdrUnorm
            || !device || !context || state_ != State::D3DReady || sequence <= 0
            || static_cast<std::uint64_t>(sequence) != lastSequence_
            || size == 0 || size > 64 || size > width_ || size > height_
            || x > width_ - size || y > height_ - size) return false;
    std::vector<unsigned char> data(size * size * 16);
    const unsigned bytes[]{4,4,8}, offsets[]{0,4,8};
    D3D11_BOX box{x,y,0,x+size,y+size,1};
    for (std::size_t i=0;i<3;++i) {
        D3D11_TEXTURE2D_DESC d{}; textures_[i]->GetDesc(&d);
        d.Width=size; d.Height=size; d.Usage=D3D11_USAGE_STAGING;
        d.BindFlags=0; d.CPUAccessFlags=D3D11_CPU_ACCESS_READ; d.MiscFlags=0;
        Microsoft::WRL::ComPtr<ID3D11Texture2D> staging;
        if (FAILED(device->CreateTexture2D(&d,nullptr,&staging))) return false;
        context->CopySubresourceRegion(staging.Get(),0,0,0,0,textures_[i].Get(),0,&box);
        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (FAILED(context->Map(staging.Get(),0,D3D11_MAP_READ,0,&mapped))) return false;
        for(unsigned row=0;row<size;++row) for(unsigned col=0;col<size;++col)
            std::memcpy(data.data()+(row*size+col)*16+offsets[i],
                    static_cast<const unsigned char*>(mapped.pData)+row*mapped.RowPitch+col*bytes[i],bytes[i]);
        context->Unmap(staging.Get(),0);
    }
    output.swap(data); return true;
}
namespace {
// LDR共有入力はカラーRGBA8、深度R32F、MVのRG32Fを同じ添字で対応させる。
constexpr std::array<DXGI_FORMAT, 3> Formats{
    DXGI_FORMAT_R8G8B8A8_UNORM, DXGI_FORMAT_R32_FLOAT, DXGI_FORMAT_R32G32_FLOAT
};
constexpr unsigned ValidationExtent = 256;
constexpr std::uint64_t RequiredResetValidations = 7;
constexpr std::uint64_t RequiredStaticValidations = 7;
constexpr std::uint64_t RequiredMotionValidations = 1;
constexpr float DepthEpsilon = 1.0e-5f;
constexpr float StaticMotionLimit = 0.01f;
constexpr float MotionNonZero = 1.0e-4f;

bool validJitter(double x, double y, int phase, int phaseCount, bool enabled, bool reset) {
    if (!std::isfinite(x) || !std::isfinite(y) || x < -0.5 || x >= 0.5 || y < -0.5 || y >= 0.5
            || phaseCount < 1 || phase < 0 || phase >= phaseCount || (reset && phase != 0)) return false;
    return enabled || (x == 0.0 && y == 0.0 && phase == 0 && phaseCount == 1);
}
}

NativeError FrameInteropSet::create(ID3D11Device* device, WglDxInterop& interop,
        const std::array<unsigned, 3>& glNames, unsigned width, unsigned height,
        bool validationEnabled, std::string& diagnostics, FrameColorEncoding colorEncoding) {
    if (state_ != State::Empty || !device || !interop.initialized() || width == 0 || height == 0
            || (colorEncoding != FrameColorEncoding::LdrUnorm && colorEncoding != FrameColorEncoding::LinearHdrFloat)
            || (colorEncoding == FrameColorEncoding::LinearHdrFloat && validationEnabled)
            || std::any_of(glNames.begin(), glNames.end(), [](unsigned name) { return name == 0; })
            || glNames[0] == glNames[1] || glNames[0] == glNames[2] || glNames[1] == glNames[2]) {
        diagnostics += "Invalid frame interop create state;";
        return NativeError::FrameInteropInvalidState;
    }
    D3D11_TEXTURE2D_DESC description{};
    description.Width = width;
    description.Height = height;
    description.MipLevels = 1;
    description.ArraySize = 1;
    description.SampleDesc.Count = 1;
    description.Usage = D3D11_USAGE_DEFAULT;
    description.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    // D3D11所有の入力を生成し、Javaで確保したGL名へ登録する。3入力は同じ解像度で扱う。
    for (std::size_t index = 0; index < textures_.size(); ++index) {
        description.Format = index == 0 && colorEncoding == FrameColorEncoding::LinearHdrFloat
                ? DXGI_FORMAT_R16G16B16A16_FLOAT : Formats[index];
        if (FAILED(device->CreateTexture2D(&description, nullptr, &textures_[index]))) {
            diagnostics += "CreateTexture2D(frame interop) failed;";
            state_ = State::Failed;
            auto cleanup = release(interop, diagnostics);
            return cleanup == NativeError::Ok ? NativeError::FrameInteropCreateFailed : cleanup;
        }
        objects_[index] = interop.registerTexture(textures_[index].Get(), glNames[index], GL_TEXTURE_2D,
                WglDxInterop::AccessReadWrite, diagnostics);
        if (!objects_[index]) {
            state_ = State::Failed;
            auto cleanup = release(interop, diagnostics);
            return cleanup == NativeError::Ok ? NativeError::WglRegisterFailed : cleanup;
        }
    }
    width_ = width;
    height_ = height;
    lastSequence_ = 0;
    validationEnabled_ = validationEnabled;
    colorEncoding_ = colorEncoding;
    if (validationEnabled_) {
        auto validationResult = createValidationResources(device, diagnostics);
        if (validationResult != NativeError::Ok) {
            state_ = State::Failed;
            auto cleanup = release(interop, diagnostics);
            return cleanup == NativeError::Ok ? validationResult : cleanup;
        }
    }
    state_ = State::D3DReady;
    diagnostics += colorEncoding == FrameColorEncoding::LinearHdrFloat
            ? "Frame interop RGBA16F/R32F/RG32F registered;validation disabled;"
            : validationEnabled_ ? "Frame interop RGBA8/R32F/RG32F registered;"
                                 : "Frame interop RGBA8/R32F/RG32F registered;validation disabled;";
    return NativeError::Ok;
}

NativeError FrameInteropSet::lock(WglDxInterop& interop, std::string& diagnostics) {
    if (state_ != State::D3DReady) {
        diagnostics += "Frame interop is not D3D-ready;";
        return NativeError::FrameInteropInvalidState;
    }
    std::vector<HANDLE> objects(objects_.begin(), objects_.end());
    auto result = interop.lock(objects, diagnostics);
    state_ = result == NativeError::Ok ? State::GlLocked : State::Failed;
    return result;
}

NativeError FrameInteropSet::unlock(ID3D11DeviceContext* context, WglDxInterop& interop,
        std::int64_t sequence, bool reset,
        double jitterX, double jitterY, int phase, int phaseCount, bool jitterEnabled,
        int validationKind, std::string& diagnostics) {
    if (state_ != State::GlLocked) {
        diagnostics += "Frame interop is not GL-locked;";
        return NativeError::FrameInteropInvalidState;
    }
    std::vector<HANDLE> objects(objects_.begin(), objects_.end());
    auto result = interop.unlock(objects, diagnostics);
    if (result != NativeError::Ok) {
        state_ = State::Failed;
        return result;
    }
    if (!context || validationKind < 0 || validationKind > 3 || sequence <= 0
            || static_cast<std::uint64_t>(sequence) <= lastSequence_
            || !validJitter(jitterX, jitterY, phase, phaseCount, jitterEnabled, reset)) {
        diagnostics += "Invalid frame interop sequence or jitter metadata;";
        state_ = State::Failed;
        return NativeError::FrameInteropSequenceInvalid;
    }
    // GLから所有権が返り、入力メタデータも確認できた時点でD3D11評価可能なフレームとして確定する。
    lastSequence_ = static_cast<std::uint64_t>(sequence);
    state_ = State::D3DReady;
    if (validationKind == 0) return NativeError::Ok;
    if (!validationEnabled_) {
        diagnostics += "Frame validation is disabled;";
        return NativeError::FrameInteropInvalidState;
    }
    return submitValidation(context, validationKind, diagnostics);
}

NativeError FrameInteropSet::createValidationResources(ID3D11Device* device, std::string& diagnostics) {
    sampleWidth_ = std::min(width_, ValidationExtent);
    sampleHeight_ = std::min(height_, ValidationExtent);
    for (auto& slot : validationSlots_) {
        D3D11_TEXTURE2D_DESC description{};
        description.Width = sampleWidth_;
        description.Height = sampleHeight_;
        description.MipLevels = 1;
        description.ArraySize = 1;
        description.SampleDesc.Count = 1;
        description.Usage = D3D11_USAGE_STAGING;
        description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        for (std::size_t index = 0; index < slot.staging.size(); ++index) {
            description.Format = Formats[index];
            if (FAILED(device->CreateTexture2D(&description, nullptr, &slot.staging[index]))) {
                diagnostics += "CreateTexture2D(frame validation staging) failed;";
                releaseValidationResources();
                return NativeError::FrameInteropCreateFailed;
            }
        }
        D3D11_QUERY_DESC queryDescription{};
        queryDescription.Query = D3D11_QUERY_EVENT;
        if (FAILED(device->CreateQuery(&queryDescription, &slot.query))) {
            diagnostics += "CreateQuery(frame validation) failed;";
            releaseValidationResources();
            return NativeError::FrameInteropCreateFailed;
        }
    }
    return NativeError::Ok;
}

NativeError FrameInteropSet::submitValidation(ID3D11DeviceContext* context, int kind,
        std::string& diagnostics) {
    if (!validationEnabled_) {
        diagnostics += "Frame validation submission is disabled;";
        return NativeError::FrameInteropInvalidState;
    }
    // 診断が有効な場合だけ、空きリングへ読み戻しを予約する。未完了のスロットを上書きしない。
    auto slot = std::find_if(validationSlots_.begin(), validationSlots_.end(),
            [](const ValidationSlot& candidate) { return !candidate.pending; });
    if (slot == validationSlots_.end()) {
        validationSkipped_++;
        diagnostics += "Frame validation ring full;";
        return NativeError::FrameInteropValidationFailed;
    }
    D3D11_BOX region{};
    region.left = (width_ - sampleWidth_) / 2;
    region.top = (height_ - sampleHeight_) / 2;
    region.right = region.left + sampleWidth_;
    region.bottom = region.top + sampleHeight_;
    region.front = 0;
    region.back = 1;
    for (std::size_t index = 0; index < textures_.size(); ++index) {
        context->CopySubresourceRegion(slot->staging[index].Get(), 0, 0, 0, 0,
                textures_[index].Get(), 0, &region);
    }
    context->End(slot->query.Get());
    slot->sequence = lastSequence_;
    slot->kind = kind;
    slot->pending = true;
    validationSubmitted_++;
    return NativeError::Ok;
}

NativeError FrameInteropSet::pollValidation(ID3D11DeviceContext* context, std::string& diagnostics) {
    if (!validationEnabled_) {
        diagnostics += "Frame validation poll is disabled;";
        return NativeError::FrameInteropInvalidState;
    }
    if (!context || (state_ != State::D3DReady && state_ != State::GlLocked)) {
        diagnostics += "Invalid frame validation poll state;";
        return NativeError::FrameInteropInvalidState;
    }
    for (auto& slot : validationSlots_) {
        if (!slot.pending) continue;
        HRESULT result = context->GetData(slot.query.Get(), nullptr, 0, D3D11_ASYNC_GETDATA_DONOTFLUSH);
        if (result == S_FALSE) continue;
        if (FAILED(result)) {
            validationFailure_ = "event query GetData failed";
            state_ = State::Failed;
            return NativeError::FrameInteropValidationFailed;
        }
        auto analyzed = analyzeValidation(context, slot, diagnostics);
        slot.pending = false;
        if (analyzed != NativeError::Ok) {
            state_ = State::Failed;
            return analyzed;
        }
    }
    return NativeError::Ok;
}

NativeError FrameInteropSet::analyzeValidation(ID3D11DeviceContext* context, ValidationSlot& slot,
        std::string& diagnostics) {
    std::array<D3D11_MAPPED_SUBRESOURCE, 3> mapped{};
    std::size_t mappedCount = 0;
    for (; mappedCount < slot.staging.size(); ++mappedCount) {
        if (FAILED(context->Map(slot.staging[mappedCount].Get(), 0, D3D11_MAP_READ, 0,
                &mapped[mappedCount]))) break;
    }
    if (mappedCount != slot.staging.size()) {
        while (mappedCount > 0) context->Unmap(slot.staging[--mappedCount].Get(), 0);
        validationFailure_ = "staging Map failed";
        diagnostics += "Frame validation staging Map failed;";
        return NativeError::FrameInteropValidationFailed;
    }

    bool colorDifferent = false;
    std::array<unsigned char, 4> firstColor{};
    std::memcpy(firstColor.data(), mapped[0].pData, firstColor.size());
    bool depthValid = true;
    std::uint64_t depthInterior = 0;
    bool motionFinite = true;
    std::uint64_t motionNonZero = 0;
    float maximumMotion = 0.0f;
    for (unsigned y = 0; y < sampleHeight_; ++y) {
        const auto* color = static_cast<const unsigned char*>(mapped[0].pData) + y * mapped[0].RowPitch;
        const auto* depth = reinterpret_cast<const float*>(
                static_cast<const unsigned char*>(mapped[1].pData) + y * mapped[1].RowPitch);
        const auto* motion = reinterpret_cast<const float*>(
                static_cast<const unsigned char*>(mapped[2].pData) + y * mapped[2].RowPitch);
        for (unsigned x = 0; x < sampleWidth_; ++x) {
            colorDifferent = colorDifferent || std::memcmp(color + x * 4, firstColor.data(), 4) != 0;
            float depthValue = depth[x];
            depthValid = depthValid && std::isfinite(depthValue)
                    && depthValue >= -DepthEpsilon && depthValue <= 1.0f + DepthEpsilon;
            if (depthValue > DepthEpsilon && depthValue < 1.0f - DepthEpsilon) depthInterior++;
            float motionX = motion[x * 2];
            float motionY = motion[x * 2 + 1];
            motionFinite = motionFinite && std::isfinite(motionX) && std::isfinite(motionY);
            float magnitude = std::max(std::abs(motionX), std::abs(motionY));
            maximumMotion = std::max(maximumMotion, magnitude);
            if (magnitude > MotionNonZero) motionNonZero++;
        }
    }
    for (std::size_t index = 0; index < slot.staging.size(); ++index) {
        context->Unmap(slot.staging[index].Get(), 0);
    }

    bool motionValid = slot.kind == 1 ? maximumMotion == 0.0f
            : slot.kind == 2 ? maximumMotion <= StaticMotionLimit
            : slot.kind == 3 && motionNonZero > 0;
    if (!colorDifferent || !depthValid || depthInterior == 0 || !motionFinite || !motionValid) {
        std::ostringstream failure;
        failure << "sequence=" << slot.sequence << ",kind=" << slot.kind
                << ",colorDifferent=" << colorDifferent << ",depthValid=" << depthValid
                << ",depthInterior=" << depthInterior << ",motionFinite=" << motionFinite
                << ",motionNonZero=" << motionNonZero << ",maximumMotion=" << maximumMotion;
        validationFailure_ = failure.str();
        diagnostics += "Frame validation data mismatch;";
        return NativeError::FrameInteropValidationFailed;
    }
    validationCompleted_++;
    validationKinds_[slot.kind]++;
    if (slot.kind == 2) maximumStaticMotion_ = std::max(maximumStaticMotion_, maximumMotion);
    if (slot.kind == 3) maximumCameraMotion_ = std::max(maximumCameraMotion_, maximumMotion);
    return NativeError::Ok;
}

bool FrameInteropSet::validationPassed() const {
    if (!validationEnabled_) return false;
    bool pending = std::any_of(validationSlots_.begin(), validationSlots_.end(),
            [](const ValidationSlot& slot) { return slot.pending; });
    return !pending && validationFailure_.empty() && validationCancelled_ == 0 && validationSkipped_ == 0
            && validationKinds_[1] >= RequiredResetValidations
            && validationKinds_[2] >= RequiredStaticValidations
            && validationKinds_[3] >= RequiredMotionValidations;
}

std::string FrameInteropSet::validationDiagnostics() const {
    std::uint64_t pending = std::count_if(validationSlots_.begin(), validationSlots_.end(),
            [](const ValidationSlot& slot) { return slot.pending; });
    std::ostringstream output;
    output << "validation={enabled=" << validationEnabled_
           << ",resources=" << validationResourceCount()
           << ",submitted=" << validationSubmitted_ << ",completed=" << validationCompleted_
           << ",pending=" << pending << ",cancelled=" << validationCancelled_
           << ",skipped=" << validationSkipped_ << ",reset=" << validationKinds_[1]
           << ",static=" << validationKinds_[2] << ",motion=" << validationKinds_[3]
           << ",staticMax=" << maximumStaticMotion_ << ",motionMax=" << maximumCameraMotion_
           << ",failure=" << (validationFailure_.empty() ? "none" : validationFailure_) << "};";
    return output.str();
}

std::size_t FrameInteropSet::validationResourceCount() const {
    std::size_t resources = 0;
    for (const auto& slot : validationSlots_) {
        if (slot.query) resources++;
        resources += std::count_if(slot.staging.begin(), slot.staging.end(),
                [](const auto& texture) { return static_cast<bool>(texture); });
    }
    return resources;
}

void FrameInteropSet::releaseValidationResources() {
    for (auto& slot : validationSlots_) {
        if (slot.pending) validationCancelled_++;
        slot.pending = false;
        slot.sequence = 0;
        slot.kind = 0;
        slot.query.Reset();
        for (auto& texture : slot.staging) texture.Reset();
    }
    sampleWidth_ = 0;
    sampleHeight_ = 0;
}

NativeError FrameInteropSet::release(WglDxInterop& interop, std::string& diagnostics) {
    NativeError firstError = NativeError::Ok;
    releaseValidationResources();
    if (state_ == State::GlLocked) {
        std::vector<HANDLE> objects(objects_.begin(), objects_.end());
        firstError = interop.unlock(objects, diagnostics);
    }
    // 登録の逆順で解除する。解除に失敗した共有オブジェクトは保持し、D3D資源を先に解放しない。
    for (std::size_t reverse = objects_.size(); reverse > 0; --reverse) {
        std::size_t index = reverse - 1;
        if (!objects_[index]) continue;
        auto result = interop.unregisterObject(objects_[index], diagnostics);
        if (result == NativeError::Ok) {
            objects_[index] = nullptr;
            textures_[index].Reset();
        } else if (firstError == NativeError::Ok) {
            firstError = result;
        }
    }
    if (std::all_of(objects_.begin(), objects_.end(), [](HANDLE object) { return !object; })) {
        for (auto& texture : textures_) texture.Reset();
        width_ = 0;
        height_ = 0;
        lastSequence_ = 0;
        validationEnabled_ = false;
        colorEncoding_ = FrameColorEncoding::LdrUnorm;
        state_ = State::Empty;
    } else {
        state_ = State::Failed;
    }
    return firstError;
}
}
