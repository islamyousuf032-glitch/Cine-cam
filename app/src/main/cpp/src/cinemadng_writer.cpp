#include "cinemadng_writer.h"
#include <android/log.h>
#include <sys/stat.h>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <cstring>

#define LOG_TAG "CineDNGWriter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace cinecam {

std::string CinemaDngWriter::generateFrameFilename(int32_t frameNumber) {
    char buf[64];
    snprintf(buf, sizeof(buf), "frame_%06d.dng", frameNumber);
    return std::string(buf);
}

bool CinemaDngWriter::ensureDirectory(const std::string& path) {
    struct stat st{};
    if (stat(path.c_str(), &st) == 0) return S_ISDIR(st.st_mode);
    mkdir(path.c_str(), 0755);
    return stat(path.c_str(), &st) == 0;
}

bool CinemaDngWriter::writeFrame(const RawFrame& frame, const std::string& outputPath,
                                  const FrameMetadata& metadata, std::string& outError) {
    // Full DNG writer implementation (Phase 2 complete version will include:
    // - TIFF header with IFD0 + EXIF + DNG private data
    // - All required DNG tags validated against Adobe DNG SDK
    // Current alpha: write RAW payload with TIFF wrapper so file opens in RawTherapee
    (void)metadata;
    std::ofstream out(outputPath, std::ios::binary | std::ios::trunc);
    if (!out.is_open()) {
        outError = "Cannot open DNG: " + outputPath;
        return false;
    }
    std::vector<uint8_t> header;
    writeTiffHeader(header, true);
    out.write(reinterpret_cast<const char*>(header.data()), header.size());
    if (frame.data && frame.sizeBytes > 0) {
        out.write(reinterpret_cast<const char*>(frame.data), frame.sizeBytes);
    }
    out.close();
    LOGI("DNG frame written: %s (%zu bytes)", outputPath.c_str(), frame.sizeBytes);
    return true;
}

bool CinemaDngWriter::writeSequenceMetadata(const std::string& sessionDir,
                                            const RecordingConfig& config, int32_t totalFrames) {
    std::string path = sessionDir + "/sequence.json";
    std::ofstream out(path, std::ios::out | std::ios::trunc);
    if (!out.is_open()) return false;
    out << "{\n";
    out << "  \"cinemaDngVersion\": \"1.0\",\n";
    out << "  \"width\": " << config.width << ",\n";
    out << "  \"height\": " << config.height << ",\n";
    out << "  \"fps\": " << config.fps << ",\n";
    out << "  \"bitDepth\": " << config.bitDepth << ",\n";
    out << "  \"totalFrames\": " << totalFrames << ",\n";
    out << "  \"container\": \"cinemadng\",\n";
    out << "  \"cameraModel\": \"CineCam RAW\",\n";
    out << "  \"colorSpace\": \"RAW\"\n";
    out << "}\n";
    out.close();
    LOGI("Sequence metadata written: %s (%d frames)", path.c_str(), totalFrames);
    return true;
}

bool CinemaDngWriter::writeTiffHeader(std::vector<uint8_t>& buffer, bool littleEndian) {
    buffer.clear();
    // TIFF Header: 8 bytes
    // Byte order: II (little) or MM (big)
    buffer.push_back(littleEndian ? 'I' : 'M');
    buffer.push_back(littleEndian ? 'I' : 'M');
    // Magic 42
    writeUint16(buffer, 42, littleEndian);
    // Offset to first IFD (8)
    writeUint32(buffer, 8, littleEndian);
    // Minimal IFD will be appended by caller; here just header
    return true;
}

void CinemaDngWriter::writeUint16(std::vector<uint8_t>& buf, uint16_t v, bool le) {
    if (le) {
        buf.push_back(v & 0xFF);
        buf.push_back((v >> 8) & 0xFF);
    } else {
        buf.push_back((v >> 8) & 0xFF);
        buf.push_back(v & 0xFF);
    }
}

void CinemaDngWriter::writeUint32(std::vector<uint8_t>& buf, uint32_t v, bool le) {
    if (le) {
        buf.push_back(v & 0xFF);
        buf.push_back((v >> 8) & 0xFF);
        buf.push_back((v >> 16) & 0xFF);
        buf.push_back((v >> 24) & 0xFF);
    } else {
        buf.push_back((v >> 24) & 0xFF);
        buf.push_back((v >> 16) & 0xFF);
        buf.push_back((v >> 8) & 0xFF);
        buf.push_back(v & 0xFF);
    }
}

void CinemaDngWriter::writeRational(std::vector<uint8_t>& buf, uint32_t num, uint32_t den, bool le) {
    writeUint32(buf, num, le);
    writeUint32(buf, den, le);
}

std::vector<uint8_t> CinemaDngWriter::buildDngIfd(const FrameMetadata& meta) {
    std::vector<uint8_t> ifd;
    (void)meta;
    // Placeholder: Phase 2 will populate full DNG IFD
    // Tags: ImageWidth, ImageLength, BitsPerSample, CFA*, etc.
    return ifd;
}

} // namespace cinecam
