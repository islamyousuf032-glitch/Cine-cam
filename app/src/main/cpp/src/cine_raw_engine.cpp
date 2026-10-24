#include "cine_raw_engine.h"
#include "cine_log.h"
#include <cstring>
#include "raw_layout.h"
#include "pipeline_timing.h"
#include <limits>

#define LOG_TAG "CineRawEngine"
#define LOGI(...) CINE_LOGI(LOG_TAG, __VA_ARGS__)
#define LOGE(...) CINE_LOGE(LOG_TAG, __VA_ARGS__)
#define LOGW(...) CINE_LOGW(LOG_TAG, __VA_ARGS__)

namespace cinecam {

CineRawEngine::CineRawEngine() {
    LOGI("CineRawEngine constructed");
}

CineRawEngine::~CineRawEngine() {
    release();
}

bool CineRawEngine::initialize(int32_t width, int32_t height, int32_t bitDepth, int32_t poolSize, bool preTouch) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (initialized_.load()) {
        LOGW("Already initialized, releasing first");
        releaseLocked();
    }

    if (width <= 0 || height <= 0 || poolSize <= 0 || poolSize > kMaxRingBufferSize ||
        (bitDepth != 10 && bitDepth != 12 && bitDepth != 14 && bitDepth != 16)) {
        setError("Invalid RAW engine dimensions, precision or pool count");
        return false;
    }
    const size_t w = static_cast<size_t>(width), h = static_cast<size_t>(height);
    if (w > (std::numeric_limits<size_t>::max() - 4095) / 2 / h) {
        setError("RAW frame size overflow"); return false;
    }
    size_t frameBytes = ((w * h * 2 + 4095) / 4096) * 4096;
    if (frameBytes > kMaxPoolBudgetBytes / static_cast<size_t>(poolSize)) {
        setError("RAW pool exceeds 256 MiB memory budget"); return false;
    }

    LOGI("Initializing engine: %dx%d @%d-bit, pool=%d, frameBytes=%zu", width, height, bitDepth, poolSize, frameBytes);

    try {
        memoryPool_ = std::make_unique<MemoryPool>(frameBytes, poolSize, preTouch);
        ringBuffer_ = std::make_unique<RingBuffer>(poolSize, OverflowPolicy::DropNewest, memoryPool_.get());
        // RawWriter depends on ring+pool, created on startRecording
    } catch (const std::exception& e) {
        ringBuffer_.reset(); memoryPool_.reset();
        setError(std::string("Init failed: ") + e.what());
        LOGE("%s", lastError_.c_str());
        return false;
    }

    if (memoryPool_->getNumBuffers() == 0) {
        setError("MemoryPool allocation failed");
        return false;
    }

    width_ = width; height_ = height; bitDepth_ = bitDepth;
    lastError_.clear();
    lastFramesWritten_ = 0; lastWriterStatus_ = WriterStatus::OK;
    initialized_.store(true);
    state_.store(EngineState::IDLE);
    framesPushed_.store(0);
    framesDropped_.store(0);
    poolExhausted_.store(0); controlBusy_.store(0); invalidLayout_.store(0); timestampRejected_.store(0);
    queueRejected_.store(0); notRecording_.store(0); writerUnavailable_.store(0);
    rowCopyNs_.store(0); maxRowCopyNs_.store(0); lastWriterTimings_ = {};

    LOGI("Engine initialized successfully");
    return true;
}

void CineRawEngine::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    releaseLocked();
}

void CineRawEngine::releaseLocked() {
    LOGI("Releasing engine...");

    if (rawWriter_) {
        rawWriter_->stop();
    }
    rawWriter_.reset();
    ringBuffer_.reset();
    memoryPool_.reset();

    initialized_.store(false);
    state_.store(EngineState::IDLE);
    LOGI("Engine released");
}

bool CineRawEngine::startRecording(const RecordingConfig& config, std::string& outError) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_.load()) {
        outError = "Engine not initialized";
        setError(outError);
        return false;
    }
    if (state_.load() == EngineState::RECORDING) {
        outError = "Already recording";
        return false;
    }

    if (config.width != width_ || config.height != height_ || config.bitDepth != bitDepth_ || config.fps <= 0 ||
        config.format != RawFormat::RAW_SENSOR || (config.container != "mcraw" && config.container != "ccraw") || config.enableZstd || config.enableAudio) {
        outError = "Only experimental uncompressed RAW_SENSOR dump is implemented; no CinemaDNG/ZSTD/audio yet";
        setError(outError); return false;
    }

    LOGI("Starting recording: %dx%d %dfps -> %s [%s] parts=%zu",
         config.width, config.height, config.fps,
         config.outputFd >= 0 ? "provider fd" : (config.segmentFds.empty() ? config.outputPath.c_str() : "provider fds"),
         config.container.c_str(), config.segmentFds.size());

    WriterStatus ws;
    try {
        rawWriter_ = std::make_unique<RawWriter>(ringBuffer_.get(), memoryPool_.get());
        ws = rawWriter_->start(config);
    }
    catch (const std::exception& error) {
        outError = std::string("Writer start exception: ") + error.what();
        setError(outError); rawWriter_.reset(); return false;
    }
    if (ws != WriterStatus::OK) {
        outError = "Writer start failed: " + rawWriter_->getLastError();
        setError(outError);
        LOGE("%s", outError.c_str());
        rawWriter_.reset();
        return false;
    }

    lastFramesWritten_ = 0; lastWriterStatus_ = WriterStatus::OK;
    strictTimestamps_ = config.container == "ccraw";
    lastAcceptedTimestamp_ = -1; recordingEpoch_ = config.epochNs;
    state_.store(EngineState::RECORDING);
    LOGI("Recording started");
    return true;
}

bool CineRawEngine::stopRecording() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_.load() != EngineState::RECORDING) {
        LOGW("stopRecording called while not recording");
        return false;
    }

    LOGI("Stopping recording...");

    bool success = true;
    if (rawWriter_) {
        WriterStatus ws = rawWriter_->stop();
        success = ws == WriterStatus::OK;
        lastFramesWritten_ = rawWriter_->getStats().framesWritten;
        lastWriterStatus_ = ws;
        lastWriterTimings_ = rawWriter_->getStats().timings;
        if (!success) setError(rawWriter_->getLastError());
        if (ws != WriterStatus::OK) {
            LOGW("Writer stop returned %d: %s", static_cast<int>(ws), rawWriter_->getLastError().c_str());
        }
        rawWriter_.reset();
    }

    if (ringBuffer_) ringBuffer_->clear();

    state_.store(EngineState::IDLE);
    LOGI("Recording stopped, framesPushed=%llu dropped=%llu",
         (unsigned long long)framesPushed_.load(), (unsigned long long)framesDropped_.load());
    return success;
}

bool CineRawEngine::onRawFrameAvailable(const uint8_t* data, size_t size, const FrameMetadata& metadata) {
    return ingest(data, size, metadata, false);
}
bool CineRawEngine::onRaw16PlaneAvailable(const uint8_t* data, size_t size, const FrameMetadata& metadata) {
    return ingest(data, size, metadata, true);
}
bool CineRawEngine::ingest(const uint8_t* data, size_t size, const FrameMetadata& metadata, bool packRows) {
    // Never wait for control-path stop/drain or reinitialization on the capture callback.
    std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
    if (!lock.owns_lock()) return reject(controlBusy_);
    if (!initialized_.load() || !memoryPool_ || !ringBuffer_ || state_.load() != EngineState::RECORDING)
        return reject(notRecording_);
    if (!rawWriter_ || !rawWriter_->isRunning()) return reject(writerUnavailable_);
    if (!data || metadata.width != width_ || metadata.height != height_ || metadata.bitDepth != bitDepth_ ||
        !validRaw16Layout(size, packRows ? size : memoryPool_->getBufferSize(), metadata)) {
        return reject(invalidLayout_);
    }

    if (strictTimestamps_ && (metadata.timestampNs < recordingEpoch_ || metadata.timestampNs <= lastAcceptedTimestamp_)) {
        return reject(timestampRejected_);
    }
    // Acquire pooled buffer
    int32_t slot = memoryPool_->acquire();
    if (slot < 0) {
        if (logLevel_ <= 3) LOGW("Pool exhausted, dropping frame #%d", metadata.frameNumber);
        return reject(poolExhausted_);
    }

    uint8_t* dest = memoryPool_->getBuffer(slot);
    if (!dest) {
        memoryPool_->release(slot);
        return reject(invalidLayout_);
    }

    // Copy (could be zero-copy if AHardwareBuffer path is used; here we copy for safety)
    const auto copyBegin = pipelineNowNs();
    size_t copySize = size;
    if (packRows) {
        const size_t row = static_cast<size_t>(width_) * 2;
        copySize = row * static_cast<size_t>(height_);
        for (int32_t y = 0; y < height_; ++y)
            memcpy(dest + static_cast<size_t>(y) * row, data + static_cast<size_t>(y) * metadata.stride, row);
    } else memcpy(dest, data, copySize);
    const auto copyElapsed = pipelineNowNs() - copyBegin;
    rowCopyNs_.fetch_add(copyElapsed);
    if (copyElapsed > maxRowCopyNs_.load()) maxRowCopyNs_.store(copyElapsed);

    RawFrame frame;
    frame.data = dest;
    frame.sizeBytes = copySize;
    frame.metadata = metadata;
    if (packRows) frame.metadata.stride = width_ * 2;
    frame.poolSlotIndex = slot;
    frame.isValid = true;

    bool pushed = ringBuffer_->push(std::move(frame));
    if (!pushed) {
        // Return to pool if ring rejected (DropNewest)
        memoryPool_->release(slot);
        return reject(queueRejected_);
    }

    lastAcceptedTimestamp_ = metadata.timestampNs;
    framesPushed_.fetch_add(1);
    return true;
}

bool CineRawEngine::pushPooledFrame(int32_t poolIndex, size_t size, const FrameMetadata& metadata) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_.load() || !ringBuffer_ || !memoryPool_) return false;
    if (state_.load() != EngineState::RECORDING || !rawWriter_ || !rawWriter_->isRunning() || !memoryPool_->isValidIndex(poolIndex) ||
        metadata.width != width_ || metadata.height != height_ || metadata.bitDepth != bitDepth_ ||
        !validRaw16Layout(size, memoryPool_->getBufferSize(), metadata)) return false;

    RawFrame frame;
    frame.data = memoryPool_->getBuffer(poolIndex);
    frame.sizeBytes = size;
    frame.metadata = metadata;
    frame.poolSlotIndex = poolIndex;
    frame.isValid = true;

    bool pushed = ringBuffer_->push(std::move(frame));
    if (!pushed) return reject(queueRejected_);
    framesPushed_.fetch_add(1);
    return true;
}

int32_t CineRawEngine::ingestionCapacity() const {
    std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
    if (!lock.owns_lock()) return 0;
    if (!initialized_.load() || state_.load() != EngineState::RECORDING || !memoryPool_ || !rawWriter_ ||
        !rawWriter_->isRunning() || rawWriter_->status() != WriterStatus::OK) return -1;
    return memoryPool_->getAvailableCount();
}

CineRawEngine::EngineStats CineRawEngine::getStats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    EngineStats s{};
    s.framesWritten = lastFramesWritten_;
    s.writerStatus = lastWriterStatus_;
    s.writerTimings = lastWriterTimings_;
    s.poolExhausted = poolExhausted_.load(); s.controlBusy = controlBusy_.load();
    s.invalidLayout = invalidLayout_.load(); s.timestampRejected = timestampRejected_.load();
    s.queueRejected = queueRejected_.load(); s.notRecording = notRecording_.load(); s.writerUnavailable = writerUnavailable_.load();
    s.rowCopyNs = rowCopyNs_.load(); s.maxRowCopyNs = maxRowCopyNs_.load();
    if (memoryPool_) {
        s.poolAvailable = memoryPool_->getAvailableCount();
        s.poolTotal = memoryPool_->getNumBuffers();
        s.poolPreTouchNs = memoryPool_->preTouchNs();
        s.poolPeakUsed = memoryPool_->getStats().peakUsed;
    }
    if (ringBuffer_) {
        s.ringSize = ringBuffer_->size();
        s.ringCapacity = ringBuffer_->capacity();
        s.ringFillRatio = ringBuffer_->fillRatio();

    }
    s.framesDropped = framesDropped_.load();
    s.framesPushed = framesPushed_.load();
    if (rawWriter_) {
        auto ws = rawWriter_->getStats();
        s.framesWritten = ws.framesWritten;
        s.writerStatus = rawWriter_->status();
        s.writerTimings = ws.timings;
    }
    return s;
}

bool CineRawEngine::reject(std::atomic<uint64_t>& reason) {
    reason.fetch_add(1); framesDropped_.fetch_add(1); return false;
}

std::string CineRawEngine::getLastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
}

void CineRawEngine::setError(const std::string& err) {
    lastError_ = err;
}

// Stable singleton address: concurrent JNI callers cannot retain a freed engine pointer.
CineRawEngine* GetGlobalEngine() {
    static CineRawEngine engine;
    return &engine;
}

void ReleaseGlobalEngine() { GetGlobalEngine()->release(); }

} // namespace cinecam
