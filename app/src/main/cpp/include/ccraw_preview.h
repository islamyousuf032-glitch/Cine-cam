#pragma once
#include "dng_preview.h"
#include <array>
#include <cstdint>
#include <string>

namespace cinecam {

/** Preview one frame of CineCam's own verified CCRAW001 burst container.
 *  Used when a DNG's private TIFF layout is unsupported by the DNG reader;
 *  the RAW payload bytes are identical to the exported frame. */
struct CcrawPreviewResult {
    DngPreview image;
    int frameIndex = 0;
    std::array<double, 3> gain{{1, 1, 1}};
};

CcrawPreviewResult renderCcrawFrame(int fd, int frameIndex, int maxDimension, int orientation);

/** Last human-readable explanation from either preview path (thread local). */


}  // namespace cinecam
