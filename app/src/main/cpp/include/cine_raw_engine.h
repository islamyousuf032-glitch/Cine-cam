#pragma once
/**
 * CineCam Raw Engine - Central C++ Pipeline Controller
 *
 * This is the heart of MotionCam architecture:
 * JNI Bridge <-> RawEngine <-> [MemoryPool + RingBuffer + RawWriter + GL Renderer]
 *
 * Thread Model:
 * - Main (UI) thread: start/stop/configure via JNI
 * - Camera thread (ACameraCaptureSession): onImageAvailable -> push to RingBuffer
 * - Writer thread: drain RingBuffer -> disk (via RawWriter)
 * - GL thread: preview rendering (borrow from pool, no copy)
 */

#include "cine_types.h"
#include "memory_pool.h"
#include "ring_buffer.h"
#include "raw_writer.h"
#include <memory>
#include <atomic>
#include <mutex>
#include <string>

namespace cinecam {

class CineRawEngine {
public:
    CineRawEngine();
    ~CineRawEngine();

    CineRawEngine(const CineRawEngine&) = delete;
    CineRawEngine& operator=(const CineRawEngine&) = delete;

    // Lifecycle
    bool initialize(int32_t width, int32_t height, int32_t bitDepth, int32_t poolSize = kDefaultRingBufferSize);
    void release();
    bool isInitialized() const { return initialized_.load(); }

    // Recording Control
    bool startRecording(const RecordingConfig& config, std::string& outError);
    bool stopRecording();
    bool isRecording() const { return state_.load() == EngineState::RECORDING; }

    EngineState getState() const { return state_.load(); }

    // Frame ingestion (called from JNI / NDK AImageReader callback)
    // data points to AHardwareBuffer or raw pointer; we copy into pooled buffer
    bool onRawFrameAvailable(const uint8_t* data, size_t size, const FrameMetadata& metadata);

    bool onRaw16PlaneAvailable(const uint8_t* data, size_t size, const FrameMetadata& metadata);

    // Direct pooled write (zero-copy path when caller already has pooled buffer)
    bool pushPooledFrame(int32_t poolIndex, size_t size, const FrameMetadata& metadata);

    // Stats & diagnostics
    struct EngineStats {
        int32_t poolAvailable = 0;
        int32_t poolTotal = 0;
        int32_t ringSize = 0;
        int32_t ringCapacity = 0;
        float ringFillRatio = 0.f;
        uint64_t framesPushed = 0;
        uint64_t framesDropped = 0;
        uint64_t framesWritten = 0;
        WriterStatus writerStatus = WriterStatus::OK;
    };
    EngineStats getStats() const;

    std::string getLastError() const;
    void setLogLevel(int32_t level) { logLevel_ = level; }

private:
    std::atomic<bool> initialized_{false};
    std::atomic<EngineState> state_{EngineState::IDLE};

    std::unique_ptr<MemoryPool> memoryPool_;
    std::unique_ptr<RingBuffer> ringBuffer_;
    std::unique_ptr<RawWriter> rawWriter_;

    mutable std::mutex mutex_;
    std::string lastError_;
    std::atomic<int32_t> logLevel_{2};
    int32_t width_ = 0, height_ = 0, bitDepth_ = 0;
    bool strictTimestamps_ = false;
    int64_t lastAcceptedTimestamp_ = -1, recordingEpoch_ = 0;
    uint64_t lastFramesWritten_ = 0;
    WriterStatus lastWriterStatus_ = WriterStatus::OK; // 0=verbose, 1=debug, 2=info, 3=warn, 4=error

    // For stats
    std::atomic<uint64_t> framesPushed_{0};
    std::atomic<uint64_t> framesDropped_{0};

    bool ingest(const uint8_t* data, size_t size, const FrameMetadata& metadata, bool packRows);
    void releaseLocked();
    void setError(const std::string& err); // caller holds mutex_
};

// Singleton access for JNI (simplifies lifecycle)
CineRawEngine* GetGlobalEngine();
void ReleaseGlobalEngine();

} // namespace cinecam
