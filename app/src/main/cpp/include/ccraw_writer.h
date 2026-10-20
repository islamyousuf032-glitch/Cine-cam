#pragma once
#include "cine_types.h"
#include <cstdint>
#include <string>

namespace cinecam {
// Versioned experimental container; NOT MotionCam MCRAW or DNG.
class CcrawWriter {
public:
    ~CcrawWriter();
    bool open(const std::string& directory, uint32_t width, uint32_t height, int64_t epochNs);
    bool append(const RawFrame& frame);
    bool finish();
    const std::string& error() const { return error_; }
private:
    bool writeAll(const void* bytes, size_t size);
    int fd_ = -1;
    std::string partial_, final_, error_;
    uint32_t width_ = 0, height_ = 0;
    uint64_t count_ = 0, payload_ = 0;
    int64_t epoch_ = 0, lastTimestamp_ = -1;
    bool failed_ = false;
};
uint32_t ccrawCrc32(const uint8_t* bytes, size_t size);
}
