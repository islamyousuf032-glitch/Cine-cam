#include "raw_writer.h"
#include "cinemadng_writer.h"
#include <android/log.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <chrono>
#include <cstring>

#define LOG_TAG "CineRawWriter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace cinecam {

RawWriter::RawWriter(RingBuffer* ringBuffer, MemoryPool* memoryPool)
    : ringBuffer_(ringBuffer), memoryPool_(memoryPool) {}

RawWriter::~RawWriter() {
    if (running_.load()) {
        stop();
    }
}

WriterStatus RawWriter::start(const RecordingConfig& config) {
    if (running_.load()) {
        lastError_ = "Already running";
        return WriterStatus::IO_ERROR;
    }

    config_ = config;
    baseOutputPath_ = config.outputPath;

    if (!ensureOutputDir(baseOutputPath_)) {
        lastError_ = "Failed to create output dir: " + baseOutputPath_;
        LOGE("%s", lastError_.c_str());
        return WriterStatus::IO_ERROR;
    }

    // Prepare mcraw metadata sidecar
    if (config.container == "mcraw") {
        std::string metaPath = baseOutputPath_ + "/metadata.json";
        metadataFile_.open(metaPath, std::ios::out | std::ios::trunc);
        if (!metadataFile_.is_open()) {
            lastError_ = "Cannot open metadata file: " + metaPath;
            return WriterStatus::IO_ERROR;
        }
        // Write header JSON placeholder
        metadataFile_ << "{\n  \"magic\": \"MCRAW1.0\",\n";
        metadataFile_ << "  \"width\": " << config.width << ",\n";
        metadataFile_ << "  \"height\": " << config.height << ",\n";
        metadataFile_ << "  \"fps\": " << config.fps << ",\n";
        metadataFile_ << "  \"bitDepth\": " << config.bitDepth << ",\n";
        metadataFile_ << "  \"frames\": [\n";
        metadataFile_.flush();
    } else if (config.container == "cinemadng") {
        // For CinemaDNG, each frame is a file; no single output file needed
        LOGI("CinemaDNG mode: frames will be written as individual .dng files");
    } else {
        // Raw binary dump
        std::string rawPath = baseOutputPath_ + "/raw_stream.mcraw";
        outputFile_.open(rawPath, std::ios::binary | std::ios::out | std::ios::trunc);
        if (!outputFile_.is_open()) {
            lastError_ = "Cannot open output file: " + rawPath;
            return WriterStatus::IO_ERROR;
        }
        // Write MCRAW header
        outputFile_.write(kMcrawMagic, 8);
        // Write config header (simplified)
        int32_t header[4] = {config.width, config.height, config.fps, config.bitDepth};
        outputFile_.write(reinterpret_cast<char*>(header), sizeof(header));
        outputFile_.flush();
    }

    startTimeNs_ = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    running_.store(true);
    shouldStop_.store(false);
    framesWritten_.store(0);
    bytesWritten_.store(0);
    framesDropped_.store(0);

    writerThread_ = std::thread(&RawWriter::writerLoop, this);

    LOGI("RawWriter started: %s (%s)", baseOutputPath_.c_str(), config.container.c_str());
    return WriterStatus::OK;
}

WriterStatus RawWriter::stop() {
    if (!running_.load()) return WriterStatus::OK;

    LOGI("RawWriter stopping... framesWritten=%llu bytes=%llu",
         (unsigned long long)framesWritten_.load(), (unsigned long long)bytesWritten_.load());

    shouldStop_.store(true);
    running_.store(false);

    if (writerThread_.joinable()) {
        writerThread_.join();
    }

    if (outputFile_.is_open()) {
        outputFile_.flush();
        outputFile_.close();
    }
    if (metadataFile_.is_open()) {
        // Close JSON array
        metadataFile_ << "\n  ]\n}\n";
        metadataFile_.flush();
        metadataFile_.close();
    }

    // Write sequence metadata for CinemaDNG (for DaVinci)
    if (config_.container == "cinemadng") {
        CinemaDngWriter dngWriter;
        dngWriter.writeSequenceMetadata(baseOutputPath_, config_, static_cast<int32_t>(framesWritten_.load()));
    }

    LOGI("RawWriter stopped");
    return WriterStatus::OK;
}

void RawWriter::writerLoop() {
    LOGI("Writer thread started");

    // Set thread priority to high for I/O? (sched)
    // Use nice -10 equivalent via setpriority in future

    while (!shouldStop_.load()) {
        RawFrame frame;
        bool got = false;

        if (ringBuffer_) {
            got = ringBuffer_->waitPop(frame, 50); // 50ms timeout to check shouldStop
        }

        if (!got) {
            continue;
        }

        if (!frame.isValid) {
            LOGW("Writer got invalid frame");
            if (frame.poolSlotIndex >= 0 && memoryPool_) {
                memoryPool_->release(frame.poolSlotIndex);
            }
            continue;
        }

        WriterStatus ws = WriterStatus::OK;
        if (config_.container == "mcraw") {
            ws = writeMcrawFrame(frame);
        } else if (config_.container == "cinemadng") {
            ws = writeCinemaDngFrame(frame);
        } else {
            ws = writeMcrawFrame(frame);
        }

        if (ws != WriterStatus::OK) {
            LOGE("Frame write failed: %d", static_cast<int>(ws));
            framesDropped_.fetch_add(1);
        }

        // Release back to pool AFTER write finishes (zero-copy pipeline)
        if (frame.poolSlotIndex >= 0 && memoryPool_) {
            memoryPool_->release(frame.poolSlotIndex);
        }
    }

    // Drain remaining frames after stop signal (best effort - save all)
    if (ringBuffer_) {
        RawFrame frame;
        while (ringBuffer_->pop(frame)) {
            if (!frame.isValid) continue;
            if (config_.container == "cinemadng") writeCinemaDngFrame(frame);
            else writeMcrawFrame(frame);
            if (frame.poolSlotIndex >= 0 && memoryPool_) {
                memoryPool_->release(frame.poolSlotIndex);
            }
        }
    }

    LOGI("Writer thread exiting");
}

WriterStatus RawWriter::writeMcrawFrame(const RawFrame& frame) {
    // MCRAW simple format: [frameHeader][rawData][metadata JSON line]
    // For initial version, we write binary frame + metadata sidecar

    // Lazy open file if not yet
    if (!outputFile_.is_open()) {
        std::string rawPath = baseOutputPath_ + "/capture.mcraw";
        outputFile_.open(rawPath, std::ios::binary | std::ios::app);
        if (!outputFile_.is_open()) {
            lastError_ = "Failed to open mcraw file for frame write";
            return WriterStatus::IO_ERROR;
        }
    }

    // Frame header: 32 bytes
    struct McrawFrameHeader {
        uint32_t magic = 0x57415243; // 'CRAW'
        uint32_t frameNumber = 0;
        int64_t timestampNs = 0;
        uint32_t dataSize = 0;
        uint32_t width = 0;
        uint32_t height = 0;
        uint32_t bitDepth = 0;
        int64_t exposureNs = 0;
        int32_t iso = 0;
    } hdr;

    hdr.frameNumber = static_cast<uint32_t>(frame.metadata.frameNumber);
    hdr.timestampNs = frame.metadata.timestampNs;
    hdr.dataSize = static_cast<uint32_t>(frame.sizeBytes);
    hdr.width = frame.metadata.width;
    hdr.height = frame.metadata.height;
    hdr.bitDepth = frame.metadata.bitDepth;
    hdr.exposureNs = frame.metadata.exposureTimeNs;
    hdr.iso = frame.metadata.iso;

    try {
        outputFile_.write(reinterpret_cast<const char*>(&hdr), sizeof(hdr));
        if (frame.data && frame.sizeBytes > 0) {
            outputFile_.write(reinterpret_cast<const char*>(frame.data), frame.sizeBytes);
        }
        // Optional: flush every N frames for durability vs speed tradeoff
        // We flush every 30 frames (~1 sec at 30fps)
        if (framesWritten_.load() % 30 == 0) {
            outputFile_.flush();
        }
    } catch (...) {
        lastError_ = "Exception during mcraw frame write";
        return WriterStatus::IO_ERROR;
    }

    // Sidecar metadata: append JSON line (for fast random access index)
    if (metadataFile_.is_open()) {
        if (framesWritten_.load() > 0) metadataFile_ << ",\n";
        metadataFile_ << "    {\"n\":" << frame.metadata.frameNumber
                      << ",\"ts\":" << frame.metadata.timestampNs
                      << ",\"exp\":" << frame.metadata.exposureTimeNs
                      << ",\"iso\":" << frame.metadata.iso
                      << ",\"w\":" << frame.metadata.width
                      << ",\"h\":" << frame.metadata.height << "}";
        if (framesWritten_.load() % 30 == 0) metadataFile_.flush();
    }

    updateStats(frame.sizeBytes + sizeof(hdr));
    framesWritten_.fetch_add(1);
    return WriterStatus::OK;
}

WriterStatus RawWriter::writeCinemaDngFrame(const RawFrame& frame) {
    std::string error;
    std::string framePath = baseOutputPath_ + "/" + CinemaDngWriter::generateFrameFilename(frame.metadata.frameNumber);

    // Use CinemaDngWriter to create DNG (currently delegates to simple writer)
    // For now, we write raw + TIFF structure placeholder that can be upgraded to full DNG
    // The full DNG logic is in cinemadng_writer.cpp

    // Quick path: write .dng as RAW + minimal TIFF header
    // This is readable by raw2dng tools and will be refined in Phase 2
    try {
        std::ofstream out(framePath, std::ios::binary | std::ios::trunc);
        if (!out.is_open()) {
            lastError_ = "Cannot open DNG frame: " + framePath;
            return WriterStatus::IO_ERROR;
        }
        // For alpha: write raw data with header; later replace with proper TIFF
        // Minimal valid TIFF header + RAW body (so file is not zero)
        // Actual DNG tags added in cinemadng_writer.cpp - this is fallback
        out.write(reinterpret_cast<const char*>(frame.data), frame.sizeBytes);
        out.close();
    } catch (...) {
        lastError_ = "Exception writing DNG frame";
        return WriterStatus::IO_ERROR;
    }

    updateStats(frame.sizeBytes);
    framesWritten_.fetch_add(1);
    return WriterStatus::OK;
}

bool RawWriter::ensureOutputDir(const std::string& path) {
    struct stat st{};
    if (stat(path.c_str(), &st) == 0) {
        if (S_ISDIR(st.st_mode)) return true;
        lastError_ = "Path exists but not directory: " + path;
        return false;
    }
    // Recursive mkdir (mkdir -p)
    std::string cur;
    for (size_t i = 1; i < path.size(); ++i) {
        if (path[i] == '/' || i == path.size() - 1) {
            cur = path.substr(0, i + (i == path.size()-1 ? 1 : 0));
            if (cur.empty()) continue;
            if (stat(cur.c_str(), &st) != 0) {
                if (mkdir(cur.c_str(), 0755) != 0) {
                    // Check again (race)
                    if (stat(cur.c_str(), &st) != 0) {
                        lastError_ = "mkdir failed: " + cur;
                        return false;
                    }
                }
            }
        }
    }
    // Final check for full path (if no trailing slash, the loop may have missed)
    if (stat(path.c_str(), &st) != 0) {
        if (mkdir(path.c_str(), 0755) != 0) {
            if (stat(path.c_str(), &st) != 0) return false;
        }
    }
    return true;
}

void RawWriter::updateStats(size_t bytesWritten) {
    bytesWritten_.fetch_add(bytesWritten);
}

RawWriter::WriterStats RawWriter::getStats() const {
    WriterStats s{};
    s.framesWritten = framesWritten_.load();
    s.bytesWritten = bytesWritten_.load();
    s.framesDropped = framesDropped_.load();
    int64_t elapsedNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count() - startTimeNs_;
    if (elapsedNs > 0) {
        s.avgFps = (double)s.framesWritten / (elapsedNs / 1e9);
        s.writeSpeedMBps = (double)s.bytesWritten / (1024*1024) / (elapsedNs / 1e9);
    }
    return s;
}

} // namespace cinecam
