#pragma once
#include <chrono>
#include <cstdint>

namespace cinecam {
inline uint64_t pipelineNowNs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}
}
