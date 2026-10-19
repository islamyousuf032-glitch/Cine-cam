#include "sync_timeline.h"
#include <limits>

namespace cinecam {
namespace {
std::optional<int64_t> add(int64_t a, int64_t b) {
    if ((b > 0 && a > std::numeric_limits<int64_t>::max() - b) ||
        (b < 0 && a < std::numeric_limits<int64_t>::min() - b)) return std::nullopt;
    return a + b;
}
}

bool SyncTimeline::begin(int64_t epoch, VideoClock clock, std::optional<int64_t> offset) {
    std::lock_guard<std::mutex> lock(mutex_);
    active_ = false;
    audioAnchored_ = false;
    lastVideoPts_.reset();
    if (epoch < 0) return false;
    epoch_ = epoch;
    videoOffset_ = clock == VideoClock::Boottime ? std::optional<int64_t>(0) : offset;
    active_ = true;
    return true;
}

std::optional<int64_t> SyncTimeline::videoPts(int64_t timestamp) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!active_ || !videoOffset_ || timestamp < 0) return std::nullopt;
    auto common = add(timestamp, *videoOffset_);
    if (!common || *common < epoch_) return std::nullopt;
    const int64_t pts = *common - epoch_;
    if (lastVideoPts_ && pts <= *lastVideoPts_) return std::nullopt;
    lastVideoPts_ = pts;
    return pts;
}

bool SyncTimeline::setAudioAnchor(int64_t frame, int64_t timestamp, int32_t rate) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!active_ || audioAnchored_ || frame < 0 || timestamp < 0 || rate < 8000 || rate > 384000) return false;
    audioFrame_ = frame;
    audioTime_ = timestamp;
    sampleRate_ = rate;
    audioAnchored_ = true;
    return true;
}

std::optional<int64_t> SyncTimeline::audioCommonTime(int64_t frame) const {
    if (!active_ || !audioAnchored_ || frame < 0) return std::nullopt;
    // Both positions are non-negative int64; their difference cannot overflow.
    const int64_t delta = frame - audioFrame_;
    const int64_t seconds = delta / sampleRate_;
    const int64_t remainder = delta % sampleRate_;
    constexpr int64_t billion = 1000000000;
    if (seconds > std::numeric_limits<int64_t>::max() / billion ||
        seconds < std::numeric_limits<int64_t>::min() / billion) return std::nullopt;
    auto duration = add(seconds * billion, remainder * billion / sampleRate_);
    return duration ? add(audioTime_, *duration) : std::nullopt;
}

std::optional<int64_t> SyncTimeline::audioPts(int64_t frame) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto time = audioCommonTime(frame);
    if (!time || *time < epoch_) return std::nullopt;
    return *time - epoch_;
}

std::optional<int64_t> SyncTimeline::audioDriftNs(int64_t frame, int64_t measured) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto predicted = audioCommonTime(frame);
    if (!predicted || *predicted < 0 || measured < 0) return std::nullopt;
    return measured - *predicted;
}
}
