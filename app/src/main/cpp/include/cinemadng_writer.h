#pragma once
/**
 * CineCam CinemaDNG Writer
 * Implements Adobe DNG 1.4 + CinemaDNG Sequence
 *
 * Each frame -> standalone .dng file in session folder:
 *   session_001/
 *     frame_000001.dng
 *     frame_000002.dng
 *     ...
 *     metadata.json
 *
 * DNG Tags we write:
 * - DNGVersion, DNGBackwardVersion
 * - UniqueCameraModel, Make
 * - ImageWidth, ImageLength, BitsPerSample
 * - CFAPlaneColor, CFAPattern (Bayer)
 * - BlackLevel, WhiteLevel
 * - ColorMatrix1/2, AsShotNeutral
 * - ExposureTime, ISOSpeedRatings
 * - FocalLength, Aperture, FocusDistance
 */

#include "cine_types.h"
#include <string>
#include <vector>
#include <cstdint>

namespace cinecam {

class CinemaDngWriter {
public:
    CinemaDngWriter() = default;
    ~CinemaDngWriter() = default;

    // Write single DNG frame
    bool writeFrame(
        const RawFrame& frame,
        const std::string& outputPath,
        const FrameMetadata& metadata,
        std::string& outError
    );

    // Write sequence metadata (for Resolve/Premiere)
    bool writeSequenceMetadata(
        const std::string& sessionDir,
        const RecordingConfig& config,
        int32_t totalFrames
    );

    static std::string generateFrameFilename(int32_t frameNumber);
    static bool ensureDirectory(const std::string& path);

private:
    // Low-level TIFF/DNG writer helpers
    struct TiffTag {
        uint16_t tagId;
        uint16_t type;
        uint32_t count;
        uint32_t valueOffset; // or inline value if <=4 bytes
    };

    bool writeTiffHeader(std::vector<uint8_t>& buffer, bool littleEndian = true);
    void appendTag(std::vector<uint8_t>& ifd, const TiffTag& tag);
    void writeUint16(std::vector<uint8_t>& buf, uint16_t v, bool le);
    void writeUint32(std::vector<uint8_t>& buf, uint32_t v, bool le);
    void writeRational(std::vector<uint8_t>& buf, uint32_t num, uint32_t den, bool le);

    // DNG specific
    std::vector<uint8_t> buildDngIfd(const FrameMetadata& meta);
};

} // namespace cinecam
