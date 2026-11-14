#pragma once

#include <d3d11.h>
#include <wrl/client.h>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace titanium {

/** Opt-in timestamp diagnostics; no resources, commands or polling when disabled. */
class NgxGpuTiming {
public:
    bool configure(ID3D11Device* device, bool enabled) {
        clear();
        if (!enabled) return true;
        if (!device) return false;
        // 計測を有効にした場合だけ、周波数検証と開始・終了時刻のクエリーをリング分確保する。
        for (auto& slot : slots_) {
            D3D11_QUERY_DESC desc{D3D11_QUERY_TIMESTAMP_DISJOINT, 0};
            if (FAILED(device->CreateQuery(&desc, &slot.disjoint))) { clear(); return false; }
            desc.Query = D3D11_QUERY_TIMESTAMP;
            if (FAILED(device->CreateQuery(&desc, &slot.start))
                    || FAILED(device->CreateQuery(&desc, &slot.end))) { clear(); return false; }
        }
        enabled_ = true;
        return true;
    }

    bool arm(std::uint64_t sequence) {
        if (!enabled_ || !sequence || armedSequence_ || active_) return false;
        armedSequence_ = sequence;
        return true;
    }

    void begin(ID3D11DeviceContext* context, std::uint64_t sequence) {
        if (!enabled_ || armedSequence_ != sequence) return;
        armedSequence_ = 0;
        // 指定された評価番号にだけ空きスロットを割り当て、未完了の計測を上書きしない。
        for (auto& slot : slots_) {
            if (slot.pending) continue;
            slot.sequence = sequence;
            slot.pending = true;
            active_ = &slot;
            ++submitted_;
            context->Begin(slot.disjoint.Get());
            context->End(slot.start.Get());
            return;
        }
        ++dropped_;
    }

    void end(ID3D11DeviceContext* context) {
        if (!active_) return;
        context->End(active_->end.Get());
        context->End(active_->disjoint.Get());
        active_ = nullptr;
    }

    // Header: version/submitted/completed/dropped/pending/invalid/errors, then (sequence, ns).
    std::vector<std::int64_t> poll(ID3D11DeviceContext* context) {
        std::vector<std::int64_t> rows;
        if (enabled_ && context) for (auto& slot : slots_) {
            if (!slot.pending || &slot == active_) continue;
            D3D11_QUERY_DATA_TIMESTAMP_DISJOINT frequency{};
            UINT64 start{}, end{};
            // DONOTFLUSHで完了を問い合わせ、診断の取得自体がGPU送信や待機を追加することを避ける。
            const auto flags = D3D11_ASYNC_GETDATA_DONOTFLUSH;
            HRESULT status = context->GetData(slot.disjoint.Get(), &frequency, sizeof(frequency), flags);
            if (status == S_FALSE) continue;
            if (FAILED(status)) { slot.pending = false; ++errors_; continue; }
            status = context->GetData(slot.start.Get(), &start, sizeof(start), flags);
            if (status == S_FALSE) continue;
            if (FAILED(status)) { slot.pending = false; ++errors_; continue; }
            status = context->GetData(slot.end.Get(), &end, sizeof(end), flags);
            if (status == S_FALSE) continue;
            if (FAILED(status)) { slot.pending = false; ++errors_; continue; }
            slot.pending = false;
            ++completed_;
            auto ns = nanoseconds(start, end, frequency.Frequency, frequency.Disjoint != FALSE);
            if (!ns) { ++invalid_; continue; }
            rows.push_back(static_cast<std::int64_t>(slot.sequence));
            rows.push_back(ns);
        }
        std::int64_t pending{};
        for (const auto& slot : slots_) if (slot.pending) ++pending;
        std::vector<std::int64_t> result{1, submitted_, completed_, dropped_, pending, invalid_, errors_};
        result.insert(result.end(), rows.begin(), rows.end());
        return result;
    }

    static std::int64_t nanoseconds(std::uint64_t start, std::uint64_t end,
            std::uint64_t frequency, bool disjoint) {
        if (disjoint || !frequency || end <= start) return 0;
        // 周波数が安定した区間のタイムスタンプ差だけをナノ秒へ換算する。
        long double ns = static_cast<long double>(end - start) * 1'000'000'000.0L / frequency;
        if (!std::isfinite(ns) || ns < 1 || ns >= std::numeric_limits<std::int64_t>::max()) return 0;
        return static_cast<std::int64_t>(ns);
    }

    void clear() {
        for (auto& slot : slots_) slot = Slot{};
        enabled_ = false;
        active_ = nullptr;
        armedSequence_ = 0;
        submitted_ = completed_ = dropped_ = invalid_ = errors_ = 0;
    }

private:
    struct Slot {
        Microsoft::WRL::ComPtr<ID3D11Query> disjoint, start, end;
        std::uint64_t sequence{};
        bool pending{};
    };
    std::array<Slot, 32> slots_{};
    Slot* active_{};
    bool enabled_{};
    std::uint64_t armedSequence_{};
    std::int64_t submitted_{}, completed_{}, dropped_{}, invalid_{}, errors_{};
};
}
