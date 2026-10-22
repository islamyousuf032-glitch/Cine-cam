#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace cinecam {

/** Small, allocation-bounded preview of an uncompressed 16-bit CFA DNG.
 *  The descriptor is borrowed (never closed). Pixels are 0xAARRGGBB in the
 *  stored image orientation; callers apply EXIF orientation. */
struct DngPreview {
    bool ok = false;
    int width = 0;
    int height = 0;
    int orientation = 1;
    std::vector<uint32_t> argb;
    std::string error;
};

DngPreview renderDngPreview(int fd, int maxDimension);
DngPreview renderDngPreview(const uint8_t* data, size_t size, int maxDimension);

/** Last human-readable explanation shared by both preview paths (thread local). */
const char* lastPreviewError();
void setPreviewError(std::string message);

}  // namespace cinecam
