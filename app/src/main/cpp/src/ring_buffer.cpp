#include "ring_buffer.h"
#include "cine_log.h"
#include <chrono>
#include <stdexcept>

#define LOG_TAG "CineRingBuffer"
#define LOGI(...) CINE_LOGI(LOG_TAG, __VA_ARGS__)
#define LOGW(...) CINE_LOGW(LOG_TAG, __VA_ARGS__)

namespace cinecam {

static int32_t checkedCapacity(int32_t capacity) {
    if (capacity <= 0 || capacity > kMaxRingBufferSize) throw std::invalid_argument("Invalid ring capacity");
    return capacity;
}

RingBuffer::RingBuffer(int32_t capacity, OverflowPolicy policy, MemoryPool* pool)
    : pool_(pool), capacity_(checkedCapacity(capacity)), buffer_(capacity_), policy_(policy) {
    LOGI("RingBuffer created: capacity=%d policy=%d", capacity, static_cast<int>(policy));
}

RingBuffer::~RingBuffer() {
    clear();
}

bool RingBuffer::push(RawFrame&& frame) {
    std::unique_lock<std::mutex> lock(mutex_);

    // Rejected frames remain the producer's responsibility.
    if (frame.poolSlotIndex >= 0 && (!pool_ || !pool_->isValidIndex(frame.poolSlotIndex))) return false;
    if (count_.load() >= capacity_) {
        if (policy_ == OverflowPolicy::DropOldest) {
            recycle(buffer_[tail_.load()].frame);
            // Overwrite oldest: advance tail
            tail_.store((tail_.load() + 1) % capacity_);
            count_.fetch_sub(1);
            totalDropped_.fetch_add(1);
        } else {
            // Drop newest
            totalDropped_.fetch_add(1);
            return false;
        }
    }

    int32_t head = head_.load();
    buffer_[head].frame = std::move(frame);
    buffer_[head].occupied = true;

    head_.store((head + 1) % capacity_);
    count_.fetch_add(1);
    totalPushed_.fetch_add(1);

    int32_t sz = count_.load();
    int32_t peak = peakSize_.load();
    while (sz > peak && !peakSize_.compare_exchange_weak(peak, sz)) {}

    lock.unlock();
    notEmptyCv_.notify_one();
    return true;
}

bool RingBuffer::pop(RawFrame& outFrame) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (count_.load() == 0) return false;

    int32_t tail = tail_.load();
    if (!buffer_[tail].occupied) return false;

    outFrame = std::move(buffer_[tail].frame);
    buffer_[tail].occupied = false;
    tail_.store((tail + 1) % capacity_);
    count_.fetch_sub(1);
    totalPopped_.fetch_add(1);

    notFullCv_.notify_one();
    return true;
}

bool RingBuffer::peek(RawFrame& outFrame) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (count_.load() == 0) return false;
    int32_t tail = tail_.load();
    if (!buffer_[tail].occupied) return false;
    outFrame = RawFrame{};
    outFrame.metadata = buffer_[tail].frame.metadata;
    outFrame.sizeBytes = buffer_[tail].frame.sizeBytes;
    return true;
}

bool RingBuffer::waitPop(RawFrame& outFrame, int32_t timeoutMs) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (count_.load() == 0) {
        auto status = notEmptyCv_.wait_for(lock, std::chrono::milliseconds(timeoutMs),
            [this]{ return count_.load() > 0; });
        if (!status) return false;
    }
    if (count_.load() == 0) return false;
    int32_t tail = tail_.load();
    outFrame = std::move(buffer_[tail].frame);
    buffer_[tail].occupied = false;
    tail_.store((tail + 1) % capacity_);
    count_.fetch_sub(1);
    totalPopped_.fetch_add(1);
    lock.unlock();
    notFullCv_.notify_one();
    return true;
}

void RingBuffer::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& slot : buffer_) {
        if (slot.occupied) recycle(slot.frame);
        slot.occupied = false;
    }
    head_.store(0);
    tail_.store(0);
    count_.store(0);
}

void RingBuffer::recycle(RawFrame& frame) {
    if (pool_ && frame.poolSlotIndex >= 0) pool_->release(frame.poolSlotIndex);
    frame = RawFrame{};
}

void RingBuffer::setOverflowPolicy(OverflowPolicy policy) {
    std::lock_guard<std::mutex> lock(mutex_);
    policy_ = policy;
}

int32_t RingBuffer::size() const {
    return count_.load();
}

RingBuffer::Stats RingBuffer::getStats() const {
    Stats s;
    s.totalPushed = totalPushed_.load();
    s.totalPopped = totalPopped_.load();
    s.totalDropped = totalDropped_.load();
    s.currentSize = count_.load();
    s.peakSize = peakSize_.load();
    return s;
}

} // namespace cinecam
