#include "cine_raw_engine.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "CineRawEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace cinecam {

CineRawEngine::CineRawEngine() {
    LOGI("CineRawEngine constructed");
}

CineRawEngine::~CineRawEngine() {
    release();
}

bool CineRawEngine::initialize(int32_t width, int32_t height, int32_t bitDepth, int32_t poolSize) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (initialized_.load()) {
        LOGW("Already initialized, releasing first");
        release();
    }

    size_t frameBytes = static_cast<size_t>(width) * height * 2; // worst 16-bit per pixel
    // Align up
    frameBytes = ((frameBytes + 4095) / 4096) * 4096;

    LOGI("Initializing engine: %dx%d @%d-bit, pool=%d, frameBytes=%zu", width, height, bitDepth, poolSize, frameBytes);

    try {
        memoryPool_ = std::make_unique<MemoryPool>(frameBytes, poolSize);
        ringBuffer_ = std::make_unique<RingBuffer>(poolSize, OverflowPolicy::DropNewest);
        // RawWriter depends on ring+pool, created on startRecording
    } catch (const std::exception& e) {
        setError(std::string("Init failed: ") + e.what());
        LOGE("%s", lastError_.c_str());
        return false;
    }

    if (memoryPool_->getNumBuffers() == 0) {
        setError("MemoryPool allocation failed");
        return false;
    }

    initialized_.store(true);
    state_.store(EngineState::IDLE);
    framesPushed_.store(0);
    framesDropped_.store(0);

    LOGI("Engine initialized successfully");
    return true;
}

void CineRawEngine::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_.load()) return;

    LOGI("Releasing engine...");

    if (rawWriter_ && rawWriter_->isRunning()) {
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

    LOGI("Starting recording: %dx%d %dfps -> %s [%s]",
         config.width, config.height, config.fps, config.outputPath.c_str(), config.container.c_str());

    rawWriter_ = std::make_unique<RawWriter>(ringBuffer_.get(), memoryPool_.get());
    WriterStatus ws = rawWriter_->start(config);
    if (ws != WriterStatus::OK) {
        outError = "Writer start failed: " + rawWriter_->getLastError();
        setError(outError);
        LOGE("%s", outError.c_str());
        rawWriter_.reset();
        return false;
    }

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

    if (rawWriter_) {
        WriterStatus ws = rawWriter_->stop();
        if (ws != WriterStatus::OK) {
            LOGW("Writer stop returned %d: %s", static_cast<int>(ws), rawWriter_->getLastError().c_str());
        }
        rawWriter_.reset();
    }

    // Clear ring after stop to free pooled buffers
    if (ringBuffer_) {
        RawFrame tmp;
        while (ringBuffer_->pop(tmp)) {
            if (tmp.poolSlotIndex >= 0 && memoryPool_) {
                memoryPool_->release(tmp.poolSlotIndex);
            }
        }
    }

    state_.store(EngineState::IDLE);
    LOGI("Recording stopped, framesPushed=%llu dropped=%llu",
         (unsigned long long)framesPushed_.load(), (unsigned long long)framesDropped_.load());
    return true;
}

bool CineRawEngine::onRawFrameAvailable(const uint8_t* data, size_t size, const FrameMetadata& metadata) {
    if (!initialized_.load()) return false;
    if (!memoryPool_ || !ringBuffer_) return false;

    // Acquire pooled buffer
    int32_t slot = memoryPool_->acquire();
    if (slot < 0) {
        framesDropped_.fetch_add(1);
        if (logLevel_ <= 3) LOGW("Pool exhausted, dropping frame #%d", metadata.frameNumber);
        return false;
    }

    uint8_t* dest = memoryPool_->getBuffer(slot);
    if (!dest) {
        memoryPool_->release(slot);
        framesDropped_.fetch_add(1);
        return false;
    }

    // Copy (could be zero-copy if AHardwareBuffer path is used; here we copy for safety)
    size_t copySize = std::min(size, memoryPool_->getBufferSize());
    memcpy(dest, data, copySize);

    RawFrame frame;
    frame.data = dest;
    frame.sizeBytes = copySize;
    frame.metadata = metadata;
    frame.poolSlotIndex = slot;
    frame.isValid = true;

    bool pushed = ringBuffer_->push(std::move(frame));
    if (!pushed) {
        // Return to pool if ring rejected (DropNewest)
        memoryPool_->release(slot);
        framesDropped_.fetch_add(1);
        return false;
    }

    framesPushed_.fetch_add(1);
    return true;
}

bool CineRawEngine::pushPooledFrame(int32_t poolIndex, size_t size, const FrameMetadata& metadata) {
    if (!initialized_.load() || !ringBuffer_ || !memoryPool_) return false;
    if (!memoryPool_->isValidIndex(poolIndex)) return false;

    RawFrame frame;
    frame.data = memoryPool_->getBuffer(poolIndex);
    frame.sizeBytes = size;
    frame.metadata = metadata;
    frame.poolSlotIndex = poolIndex;
    frame.isValid = true;

    bool pushed = ringBuffer_->push(std::move(frame));
    if (!pushed) {
        framesDropped_.fetch_add(1);
        return false;
    }
    framesPushed_.fetch_add(1);
    return true;
}

CineRawEngine::EngineStats CineRawEngine::getStats() const {
    EngineStats s{};
    if (memoryPool_) {
        s.poolAvailable = memoryPool_->getAvailableCount();
        s.poolTotal = memoryPool_->getNumBuffers();
    }
    if (ringBuffer_) {
        s.ringSize = ringBuffer_->size();
        s.ringCapacity = ringBuffer_->capacity();
        s.ringFillRatio = ringBuffer_->fillRatio();
        auto rs = ringBuffer_->getStats();
        s.framesDropped = rs.totalDropped;
    }
    s.framesPushed = framesPushed_.load();
    if (rawWriter_) {
        auto ws = rawWriter_->getStats();
        s.framesWritten = ws.framesWritten;
    }
    return s;
}

std::string CineRawEngine::getLastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
}

void CineRawEngine::setError(const std::string& err) {
    std::lock_guard<std::mutex> lock(mutex_);
    lastError_ = err;
}

// Singleton
static std::unique_ptr<CineRawEngine> g_engine;
static std::mutex g_engineMutex;

CineRawEngine* GetGlobalEngine() {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (!g_engine) {
        g_engine = std::make_unique<CineRawEngine>();
    }
    return g_engine.get();
}

void ReleaseGlobalEngine() {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_engine) {
        g_engine->release();
        g_engine.reset();
    }
}

} // namespace cinecam
