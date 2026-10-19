#pragma once
/**
 * CineCam Memory Pool - Zero-copy RAW frame buffer management
 * Inspired by MotionCam's lock-free pooling
 *
 * Design:
 * - Pre-allocated fixed-size buffers (page-aligned, 4096)
 * - Lock-free acquire/release via atomic stack
 * - No malloc in hot path (capture @ 60fps)
 */

#include "cine_types.h"
#include <atomic>
#include <vector>
#include <memory>
#include <mutex>
#include <cstdint>

namespace cinecam {

class MemoryPool {
public:
    MemoryPool(size_t bufferSizeBytes, int32_t numBuffers);
    ~MemoryPool();

    MemoryPool(const MemoryPool&) = delete;
    MemoryPool& operator=(const MemoryPool&) = delete;

    // Acquire a buffer slot. Returns -1 if pool exhausted (caller must drop frame)
    int32_t acquire();

    // Release slot back to pool
    void release(int32_t index);

    // Raw pointer access
    uint8_t* getBuffer(int32_t index);
    const uint8_t* getBuffer(int32_t index) const;

    size_t getBufferSize() const { return bufferSizeBytes_; }
    int32_t getNumBuffers() const { return numBuffers_; }
    int32_t getAvailableCount() const;
    int32_t getUsedCount() const;

    bool isValidIndex(int32_t index) const;

    // Stats
    struct Stats {
        int32_t totalBuffers = 0;
        int32_t available = 0;
        int32_t peakUsed = 0;
        uint64_t totalAcquires = 0;
        uint64_t totalReleases = 0;
        uint64_t acquireFailures = 0;
    };
    Stats getStats() const;

private:
    size_t bufferSizeBytes_;
    int32_t numBuffers_;

    // Aligned buffers
    std::vector<uint8_t*> buffers_;
    // Free stack (LIFO)
    std::vector<int32_t> freeStack_;
    mutable std::mutex mutex_;

    // Stats (atomic for lock-free reads)
    std::atomic<int32_t> availableCount_{0};
    std::atomic<int32_t> peakUsed_{0};
    std::atomic<uint64_t> totalAcquires_{0};
    std::atomic<uint64_t> totalReleases_{0};
    std::atomic<uint64_t> acquireFailures_{0};

    void allocateBuffers();
    void freeBuffers();
};

} // namespace cinecam
