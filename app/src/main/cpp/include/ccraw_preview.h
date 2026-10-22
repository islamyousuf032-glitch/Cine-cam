#pragma once
#include "dng_preview.h"
#include <array>
#include <cstdint>

namespace cinecam {

/** Rendering inputs for the CCRAW fallback. Camera2 CFA enum 0..3, per-channel
 *  black levels, white level and the DNG ColorMatrix1 (camera->XYZ) if known. */
struct CcrawCalibration {
    int cfa = 0;
    double neutral[3] = {1, 1, 1};
    double black[4] = {0, 0, 0, 0};
    double white = 65535;
    const double* cameraToXyz = nullptr;  // 9 row-major values or null
    bool valid = false;
};

struct CcrawPreviewResult {
    DngPreview image;
    int frameIndex = 0;
    std::array<double, 3> gain{{1, 1, 1}};
};

/** Preview one frame of CineCam's own verified CCRAW001 burst container.
 *  Only sampled rows are read, so no full-frame payload allocation is needed. */
CcrawPreviewResult renderCcrawFrame(int fd, int frameIndex, int maxDimension, int orientation,
                                   const CcrawCalibration& calibration);

}  // namespace cinecam
