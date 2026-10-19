#pragma once
/**
 * CineCam Ring Buffer - Mutex-Protected Circular Queue for RAW frames
 *
 * MotionCam Architecture:
 * - Single Producer (Camera2 callback thread) -> Single Consumer (Writer thread)
 * - Mutex-protected push/pop; condition-variable wait for consumer
 * - Zero-copy: only indices/metadata are copied, pixel data stays in MemoryPool
 * - Overflow policy: drop oldest (configurable) or drop newest
 */

#include "cine_types.h"
#include <atomic>
#include <vector>
#include <cstdint>
#include <mutex>
#include <condition_variable>

namespace cinecam {

enum class OverflowPolicy {
    DropOldest,   // Overwrite oldest frame (keep newest) - for preview
    DropNewest    // Drop incoming if full (keep oldest) - for recording
};

class RingBuffer {
public:
    explicit RingBuffer(int32_t capacity, OverflowPolicy policy = OverflowPolicy::DropNewest);
    ~RingBuffer();

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    // Producer API (called from Camera thread)
    // Returns true if enqueued, false if dropped
    bool push(RawFrame&& frame);

    // Consumer API (called from Writer thread)
    // Returns true if dequeued, false if empty
    bool pop(RawFrame& outFrame);

    // Non-blocking peek
    bool peek(RawFrame& outFrame) const;

    // Blocking pop with timeout (for writer thread)
    bool waitPop(RawFrame& outFrame, int32_t timeoutMs = 100);

    void clear();
    int32_t size() const;
    int32_t capacity() const { return capacity_; }
    bool empty() const { return size() == 0; }
    bool full() const { return size() == capacity_; }

    float fillRatio() const { return static_cast<float>(size()) / capacity_; }

    // Stats
    struct Stats {
        uint64_t totalPushed = 0;
        uint64_t totalPopped = 0;
        uint64_t totalDropped = 0;
        int32_t currentSize = 0;
        int32_t peakSize = 0;
    };
    Stats getStats() const;

    void setOverflowPolicy(OverflowPolicy p) { policy_ = p; }

private:
    struct Slot {
        RawFrame frame;
        std::atomic<uint64_t> sequence{0}; // Reserved; not used for synchronization
        bool occupied = false;
    };

    int32_t capacity_;
    std::vector<Slot> buffer_;

    // SPSC indices
    std::atomic<int32_t> head_{0}; // write index (producer)
    std::atomic<int32_t> tail_{0}; // read index (consumer)
    std::atomic<int32_t> count_{0};

    OverflowPolicy policy_;

    mutable std::mutex mutex_;
    std::condition_variable notEmptyCv_;
    std::condition_variable notFullCv_;

    // Stats
    std::atomic<uint64_t> totalPushed_{0};
    std::atomic<uint64_t> totalPopped_{0};
    std::atomic<uint64_t> totalDropped_{0};
    std::atomic<int32_t> peakSize_{0};
};

} // namespace cinecam
