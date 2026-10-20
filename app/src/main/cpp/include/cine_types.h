#pragma once
/**
 * CineCam - Professional RAW Camera Engine
 * Core Types & Constants - MotionCam-inspired Architecture
 * Author: Senior Android NDK Architect
 */

#include <cstdint>
#include <cstddef>
#include <array>
#include <string>
#include <utility>

namespace cinecam {

// ---------------------------------------------------------------------------
// RAW Format Definitions
// ---------------------------------------------------------------------------

enum class RawFormat : int32_t {
    RAW_SENSOR = 0x20,      // RAW_SENSOR, 16-bit unpacked storage
    RAW10 = 0x25,
    RAW12 = 0x26,
    YUV_420_888 = 0x23,
    JPEG = 0x100,
    PRIVATE = 0x22
};

enum class BayerPattern : uint8_t {
    RGGB = 0,
    GRBG = 1,
    GBRG = 2,
    BGGR = 3,
    UNKNOWN = 255
};

// ---------------------------------------------------------------------------
// Frame Metadata (per-frame - critical for CinemaDNG)
// ---------------------------------------------------------------------------

struct FrameMetadata {
    int64_t timestampNs = 0;          // SENSOR_TIMESTAMP
    int64_t exposureTimeNs = 0;       // SENSOR_EXPOSURE_TIME
    int32_t iso = 100;                // SENSOR_SENSITIVITY
    int32_t width = 0;
    int32_t height = 0;
    int32_t stride = 0;               // Row stride in bytes
    int32_t bitDepth = 10;            // 10/12/14
    float focalLength = 0.f;          // LENS_FOCAL_LENGTH
    float aperture = 0.f;             // LENS_APERTURE
    float focusDistance = 0.f;        // LENS_FOCUS_DISTANCE
    int32_t colorTempK = 5500;        // White balance approx
    float sensorTempC = 0.f;
    int32_t frameNumber = 0;          // Sequence number
    BayerPattern bayerPattern = BayerPattern::RGGB;
    float neutralWhitePoint[3] = {1.0f, 1.0f, 1.0f}; // DNG AsShotNeutral
    float colorMatrix1[9] = {};       // ColorMatrix1 for DNG
    float colorMatrix2[9] = {};
    int32_t blackLevel = 64;
    int32_t whiteLevel = 1023;        // (1 << bitDepth) - 1

    // MotionCam .mcraw extensions
    float lensShadingMap[4] = {1.f, 1.f, 1.f, 1.f};
};

// ---------------------------------------------------------------------------
// RAW Frame Container - Zero-copy design
// ---------------------------------------------------------------------------

struct RawFrame {
    uint8_t* data = nullptr;          // Pointer into MemoryPool (not owned)
    size_t sizeBytes = 0;
    FrameMetadata metadata;
    int32_t poolSlotIndex = -1;       // For returning to pool
    bool isValid = false;

    RawFrame() = default;
    RawFrame(const RawFrame&) = delete;
    RawFrame& operator=(const RawFrame&) = delete;
    RawFrame(RawFrame&& other) noexcept {
        *this = std::move(other);
    }
    // Non-owning view: the pipeline must release any previous pool slot first.
    RawFrame& operator=(RawFrame&& other) noexcept {
        if (this == &other) return *this;
        data = other.data;
        sizeBytes = other.sizeBytes;
        metadata = other.metadata;
        poolSlotIndex = other.poolSlotIndex;
        isValid = other.isValid;
        other.data = nullptr;
        other.sizeBytes = 0;
        other.poolSlotIndex = -1;
        other.isValid = false;
        return *this;
    }
};

// ---------------------------------------------------------------------------
// Recording Session Config
// ---------------------------------------------------------------------------

struct RecordingConfig {
    int32_t width = 4032;
    int32_t height = 3024;
    int32_t fps = 24;
    int64_t epochNs = 0;
    int32_t bitDepth = 10;
    RawFormat format = RawFormat::RAW_SENSOR;
    std::string outputPath;           // e.g., /storage/emulated/0/DCIM/CineCam/session_001
    std::string container = "mcraw";  // "mcraw" | "cinemadng"
    bool preallocateCcraw = false;
    bool enableZstd = false;
    int32_t zstdLevel = 3;            // 1-22, 3 is good tradeoff
    bool enableAudio = false;
    int32_t audioSampleRate = 48000;
};

// ---------------------------------------------------------------------------
// Engine States
// ---------------------------------------------------------------------------

enum class EngineState {
    IDLE,
    PREVIEWING,
    RECORDING,
    PAUSED,
    ERROR
};

enum class WriterStatus {
    OK = 0,
    IO_ERROR = 1,
    NO_SPACE = 2,
    QUEUE_FULL = 3,
    ENCODING_ERROR = 4
};

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

constexpr int32_t kDefaultRingBufferSize = 8;       // bounded; ~200 MB for 12MP RAW16
constexpr size_t kMaxPoolBudgetBytes = 256ULL * 1024 * 1024;
constexpr int32_t kMaxRingBufferSize = 300;         // count ceiling; actual allocation also constrained by byte budget
constexpr size_t kMaxFrameSize_12MP_12bit = 4032 * 3024 * 2; // ~24MB worst
constexpr int32_t kZstdDefaultLevel = 3;
constexpr const char* kMcrawMagic = "MCRAW1.0";

} // namespace cinecam
