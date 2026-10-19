#include "memory_pool.h"
#include <cstdlib>
#include <android/log.h>
#include <unistd.h>

#define LOG_TAG "CineMemPool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace cinecam {

MemoryPool::MemoryPool(size_t bufferSizeBytes, int32_t numBuffers)
    : bufferSizeBytes_(bufferSizeBytes), numBuffers_(numBuffers) {
    size_t aligned = ((bufferSizeBytes + 4095) / 4096) * 4096;
    bufferSizeBytes_ = aligned;
    allocateBuffers();
    LOGI("MemoryPool created: %d buffers x %zu bytes (aligned)", numBuffers_, bufferSizeBytes_);
}

MemoryPool::~MemoryPool() { freeBuffers(); }

void MemoryPool::allocateBuffers() {
    std::lock_guard<std::mutex> lock(mutex_);
    buffers_.reserve(numBuffers_);
    freeStack_.reserve(numBuffers_);
    for (int32_t i = 0; i < numBuffers_; ++i) {
        void* ptr = nullptr;
        if (posix_memalign(&ptr, 4096, bufferSizeBytes_) != 0) {
            LOGE("posix_memalign failed for slot %d", i);
            continue;
        }
        buffers_.push_back(static_cast<uint8_t*>(ptr));
        freeStack_.push_back(i);
    }
    availableCount_.store(static_cast<int32_t>(freeStack_.size()));
}

void MemoryPool::freeBuffers() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto* buf : buffers_) free(buf);
    buffers_.clear();
    freeStack_.clear();
}

int32_t MemoryPool::acquire() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (freeStack_.empty()) { acquireFailures_.fetch_add(1); return -1; }
    int32_t idx = freeStack_.back();
    freeStack_.pop_back();
    availableCount_.store(static_cast<int32_t>(freeStack_.size()));
    totalAcquires_.fetch_add(1);
    int32_t used = numBuffers_ - static_cast<int32_t>(freeStack_.size());
    int32_t prevPeak = peakUsed_.load();
    while (used > prevPeak && !peakUsed_.compare_exchange_weak(prevPeak, used)) {}
    return idx;
}

void MemoryPool::release(int32_t index) {
    if (!isValidIndex(index)) { LOGE("release: invalid index %d", index); return; }
    std::lock_guard<std::mutex> lock(mutex_);
    freeStack_.push_back(index);
    availableCount_.store(static_cast<int32_t>(freeStack_.size()));
    totalReleases_.fetch_add(1);
}

uint8_t* MemoryPool::getBuffer(int32_t index) {
    if (!isValidIndex(index)) return nullptr;
    return buffers_[index];
}
const uint8_t* MemoryPool::getBuffer(int32_t index) const {
    if (!isValidIndex(index)) return nullptr;
    return buffers_[index];
}
bool MemoryPool::isValidIndex(int32_t index) const {
    return index >= 0 && index < static_cast<int32_t>(buffers_.size());
}
int32_t MemoryPool::getAvailableCount() const { return availableCount_.load(); }
int32_t MemoryPool::getUsedCount() const { return numBuffers_ - availableCount_.load(); }
MemoryPool::Stats MemoryPool::getStats() const {
    Stats s;
    s.totalBuffers = numBuffers_;
    s.available = availableCount_.load();
    s.peakUsed = peakUsed_.load();
    s.totalAcquires = totalAcquires_.load();
    s.totalReleases = totalReleases_.load();
    s.acquireFailures = acquireFailures_.load();
    return s;
}

} // namespace cinecam
