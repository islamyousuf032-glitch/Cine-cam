#pragma once
#include "cine_types.h"
#include <cstdint>
#include <string>
#include <atomic>

namespace cinecam {
// Versioned experimental container; NOT MotionCam MCRAW or DNG.
class CcrawWriter {
public:
    using AllocationFn = int (*)(int fd, int64_t bytes); // errno-style result; injectable in tests
    explicit CcrawWriter(AllocationFn allocator = nullptr) : allocator_(allocator) {}
    ~CcrawWriter();
    // maxFrames/maxBytes bound one session. Both are explicit so a planned long session
    // can exceed the original 8-frame burst without any unbounded growth.
    bool open(const std::string& directory, uint32_t width, uint32_t height, int64_t epochNs,
              bool preallocate = false, uint32_t maxFrames = 8,
              uint64_t maxBytes = 256ULL * 1024 * 1024);
    bool append(const RawFrame& frame);
    bool finish();
    struct Timings {
        uint64_t crcNs = 0, writeSyscallNs = 0, fsyncNs = 0;
        uint64_t appendNs = 0, maxAppendNs = 0, finalizeNs = 0, payloadBytes = 0;
        uint64_t allocationNs = 0, reservedBytes = 0, truncateNs = 0, closeNs = 0, renameNs = 0;
        uint64_t writeCalls = 0, maxWriteNs = 0;
        int allocationError = 0;
    };
    Timings timings() const;
    const std::string& error() const { return error_; }
private:
    bool writeAll(const void* bytes, size_t size);
    AllocationFn allocator_;
    uint64_t fileOffset_ = 0;
    bool preallocated_ = false;
    int fd_ = -1;
    std::string partial_, final_, error_;
    uint32_t width_ = 0, height_ = 0;
    uint64_t count_ = 0, payload_ = 0;
    uint32_t maxFrames_ = 8;
    uint64_t maxPayload_ = 0;
    int64_t epoch_ = 0, lastTimestamp_ = -1;
    bool failed_ = false;
    std::atomic<uint64_t> allocationNs_{0}, reservedBytes_{0}, truncateNs_{0}, closeNs_{0}, renameNs_{0};
    std::atomic<uint64_t> writeCalls_{0}, maxWriteNs_{0};
    std::atomic<int> allocationError_{0};
    std::atomic<uint64_t> crcNs_{0}, writeNs_{0}, fsyncNs_{0};
    std::atomic<uint64_t> appendNs_{0}, maxAppendNs_{0}, finalizeNs_{0}, payloadBytes_{0};
};
uint32_t ccrawCrc32(const uint8_t* bytes, size_t size);
// Portable oracle/fallback, also used to self-test runtime-selected acceleration.
uint32_t ccrawCrc32Portable(const uint8_t* bytes, size_t size);
const char* ccrawCrcBackend();
const char* ccrawHardwareCrcStatus();
}
