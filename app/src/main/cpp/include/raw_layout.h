#pragma once
#include "cine_types.h"
#include <limits>

namespace cinecam {
// RAW_SENSOR: 16-bit unpacked storage. RAW10/RAW12 packed input is not accepted here.
inline bool validRaw16Layout(size_t size, size_t capacity, const FrameMetadata& meta) {
    if (meta.width <= 0 || meta.height <= 0 || meta.stride <= 0 ||
        meta.timestampNs < 0 || size > capacity) return false;
    const size_t row = static_cast<size_t>(meta.width) * 2;
    const size_t stride = static_cast<size_t>(meta.stride);
    if (stride < row || stride % 2 != 0) return false;
    const size_t rowsBeforeLast = static_cast<size_t>(meta.height - 1);
    if (rowsBeforeLast > (std::numeric_limits<size_t>::max() - row) / stride) return false;
    return size >= rowsBeforeLast * stride + row;
}
}
