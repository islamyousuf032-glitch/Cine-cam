#pragma once
#include "cine_types.h"
#include <cstdint>
#include <string>
#include <atomic>

namespace cinecam {
// Versioned experimental container; NOT MotionCam MCRAW or DNG.
class CcrawWriter {
public:
    ~CcrawWriter();
    bool open(const std::string& directory, uint32_t width, uint32_t height, int64_t epochNs);
    bool append(const RawFrame& frame);
    bool finish();
    struct Timings {
        uint64_t crcNs = 0, writeSyscallNs = 0, fsyncNs = 0;
        uint64_t appendNs = 0, maxAppendNs = 0, finalizeNs = 0, payloadBytes = 0;
    };
    Timings timings() const;
    const std::string& error() const { return error_; }
private:
    bool writeAll(const void* bytes, size_t size);
    int fd_ = -1;
    std::string partial_, final_, error_;
    uint32_t width_ = 0, height_ = 0;
    uint64_t count_ = 0, payload_ = 0;
    int64_t epoch_ = 0, lastTimestamp_ = -1;
    bool failed_ = false;
    std::atomic<uint64_t> crcNs_{0}, writeNs_{0}, fsyncNs_{0};
    std::atomic<uint64_t> appendNs_{0}, maxAppendNs_{0}, finalizeNs_{0}, payloadBytes_{0};
};
uint32_t ccrawCrc32(const uint8_t* bytes, size_t size);
// Portable oracle/fallback, also used to self-test runtime-selected acceleration.
uint32_t ccrawCrc32Portable(const uint8_t* bytes, size_t size);
const char* ccrawCrcBackend();
const char* ccrawHardwareCrcStatus();
}
