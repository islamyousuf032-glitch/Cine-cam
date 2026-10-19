#pragma once
#include <cstdint>
#include <mutex>
#include <optional>

namespace cinecam {
enum class VideoClock { Boottime, Unknown };

// Common clock: Android elapsedRealtimeNanos / AudioTimestamp TIMEBASE_BOOTTIME.
// UNKNOWN sensor clocks require an externally measured offset; callback arrival time
// is not a valid substitute. This maps PTS, it does not record, mux or resample audio.
class SyncTimeline {
public:
    bool begin(int64_t commonEpochNs, VideoClock clock,
               std::optional<int64_t> sensorToCommonOffsetNs = std::nullopt);
    std::optional<int64_t> videoPts(int64_t sensorTimestampNs);
    bool setAudioAnchor(int64_t framePosition, int64_t commonTimestampNs, int32_t sampleRate);
    std::optional<int64_t> audioPts(int64_t framePosition) const;
    // Difference between a measured AudioTimestamp and the initial sample clock model.
    std::optional<int64_t> audioDriftNs(int64_t framePosition, int64_t commonTimestampNs) const;
private:
    std::optional<int64_t> audioCommonTime(int64_t framePosition) const;
    mutable std::mutex mutex_;
    bool active_ = false;
    int64_t epoch_ = 0;
    std::optional<int64_t> videoOffset_;
    std::optional<int64_t> lastVideoPts_;
    bool audioAnchored_ = false;
    int64_t audioFrame_ = 0, audioTime_ = 0;
    int32_t sampleRate_ = 0;
};
}
