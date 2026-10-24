#pragma once
/**
 * CineCam RAW Writer - High-speed storage pipeline
 *
 * Experimental uncompressed dump only; not MotionCam-compatible.
 * CinemaDNG, ZSTD and audio are rejected until implemented and validated.
 */

#include "cine_types.h"
#include "ccraw_writer.h"
#include "ring_buffer.h"
#include "memory_pool.h"
#include <thread>
#include <atomic>
#include <string>
#include <fstream>
#include <mutex>
#include <memory>

namespace cinecam {

class RawWriter {
public:
    RawWriter(RingBuffer* ringBuffer, MemoryPool* memoryPool);
    ~RawWriter();

    RawWriter(const RawWriter&) = delete;
    RawWriter& operator=(const RawWriter&) = delete;

    // Lifecycle
    WriterStatus start(const RecordingConfig& config);
    WriterStatus stop();
    bool isRunning() const { return running_.load(); }

    // Called by engine to enqueue next frame timestamp (writer pulls from ring)
    WriterStatus status() const { return status_.load(); }

    // Stats
    struct WriterStats {
        uint64_t framesWritten = 0;
        uint64_t bytesWritten = 0;
        uint64_t framesDropped = 0;
        double avgFps = 0.0;
        double writeSpeedMBps = 0.0;
        int64_t currentFileSize = 0;
        CcrawWriter::Timings timings;
        std::vector<uint64_t> segmentBytes;
    };
    WriterStats getStats() const;

    const std::string& getOutputPath() const { return config_.outputPath; }
    const std::string& getLastError() const { return lastError_; }

private:
    void writerLoop();
    void consume(RawFrame& frame);

    // Container-specific writers
    WriterStatus writeMcrawFrame(const RawFrame& frame);
    WriterStatus writeCinemaDngFrame(const RawFrame& frame);

    bool ensureOutputDir(const std::string& path);
    void updateStats(size_t bytesWritten);

    RingBuffer* ringBuffer_ = nullptr;
    MemoryPool* memoryPool_ = nullptr;
    RecordingConfig config_;
    CcrawWriter ccraw_;
    // Multi-part sessions: every part is a complete CCRAW and the session keeps one epoch.
    std::unique_ptr<CcrawSegmentWriter> segments_;

    std::thread writerThread_;
    std::atomic<WriterStatus> status_{WriterStatus::OK};
    std::atomic<bool> running_{false};
    std::atomic<bool> shouldStop_{false};

    // File handling
    std::ofstream outputFile_;
    std::ofstream metadataFile_; // JSON sidecar for .mcraw
    std::string baseOutputPath_;

    // Stats
    std::atomic<uint64_t> framesWritten_{0};
    std::atomic<uint64_t> bytesWritten_{0};
    std::atomic<uint64_t> framesDropped_{0};
    int64_t startTimeNs_ = 0;
    std::string lastError_;

    mutable std::mutex statsMutex_;
};

} // namespace cinecam
