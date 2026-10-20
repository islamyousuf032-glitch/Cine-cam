#include "cine_raw_engine.h"
#include "raw_layout.h"
#include "sync_timeline.h"
#include "ccraw_writer.h"
#include <cassert>
#include <filesystem>
#include <future>
#include <limits>
#include <thread>
#include <array>
#include <vector>

using namespace cinecam;
RawFrame pooled(MemoryPool& pool, int number) {
    RawFrame f;
    f.poolSlotIndex = pool.acquire();
    assert(f.poolSlotIndex >= 0);
    f.data = pool.getBuffer(f.poolSlotIndex);
    f.sizeBytes = 8;
    f.metadata.frameNumber = number;
    f.isValid = true;
    return f;
}
uint32_t referenceCrc(const uint8_t* data, size_t size) {
    static const auto table = [] {
        std::array<uint32_t, 256> t{};
        for (uint32_t i = 0; i < 256; ++i) {
            auto n = i;
            for (int b = 0; b < 8; ++b) n = (n >> 1) ^ ((n & 1) ? 0xedb88320U : 0);
            t[i] = n;
        }
        return t;
    }();
    uint32_t crc = 0xffffffffU;
    for (size_t i = 0; i < size; ++i) crc = table[(crc ^ data[i]) & 255] ^ (crc >> 8);
    return crc ^ 0xffffffffU;
}

int main() {
    {
        std::vector<uint8_t> data(262144 + 16);
        uint32_t seed = 7;
        for (auto& byte : data) { seed = seed * 1664525U + 1013904223U; byte = seed >> 24; }
        assert(ccrawCrc32(nullptr, 0) == 0);
        for (size_t offset = 0; offset < 16; ++offset) {
            for (size_t size = 0; size < 80; ++size)
                assert(ccrawCrc32(data.data() + offset, size) == referenceCrc(data.data() + offset, size));
            for (size_t size : {4095U, 65536U, 262144U})
                assert(ccrawCrc32(data.data() + offset, size) == referenceCrc(data.data() + offset, size));
        }
    }
    {
        bool rejected = false;
        try { RingBuffer q(0); } catch (const std::invalid_argument&) { rejected = true; }
        assert(rejected);
        MemoryPool pool(8, 4);
        {
            RingBuffer q(2, OverflowPolicy::DropOldest, &pool);
            assert(q.push(pooled(pool, 1)));
            assert(q.push(pooled(pool, 2)));
            assert(q.push(pooled(pool, 3))); // Reclaims #1.
            assert(pool.getAvailableCount() == 2);
            RawFrame peek;
            assert(q.peek(peek) && peek.data == nullptr && peek.poolSlotIndex == -1);
            assert(peek.metadata.frameNumber == 2);
            RawFrame out;
            assert(q.pop(out) && out.metadata.frameNumber == 2);
            pool.release(out.poolSlotIndex);
            q.clear(); q.clear();
            assert(pool.getAvailableCount() == 4);
            q.setOverflowPolicy(OverflowPolicy::DropNewest);
            assert(q.push(pooled(pool, 4)) && q.push(pooled(pool, 5)));
            auto incoming = pooled(pool, 6);
            const auto slot = incoming.poolSlotIndex;
            assert(!q.push(std::move(incoming)) && incoming.poolSlotIndex == slot);
            pool.release(slot); // Rejected frame still belongs to producer.
        }
        assert(pool.getAvailableCount() == 4); // Destructor reclaims queued slots.
        RingBuffer q(3, OverflowPolicy::DropNewest, &pool);
        std::thread producer([&] {
            for (int n = 0; n < 5000; ++n) {
                int slot;
                while ((slot = pool.acquire()) < 0) std::this_thread::yield();
                RawFrame f;
                f.poolSlotIndex = slot; f.data = pool.getBuffer(slot);
                f.metadata.frameNumber = n; f.isValid = true;
                while (!q.push(std::move(f))) std::this_thread::yield();
            }
        });
        for (int n = 0; n < 5000; ++n) {
            RawFrame f;
            assert(q.waitPop(f, 2000));
            assert(f.metadata.frameNumber == n);
            pool.release(f.poolSlotIndex);
        }
        producer.join();
        assert(pool.getAvailableCount() == 4);
    }
    {
        SyncTimeline sync;
        assert(!sync.videoPts(1));
        assert(sync.begin(1000000000, VideoClock::Boottime));
        assert(sync.videoPts(1000000000) == 0);
        assert(sync.videoPts(1033333333) == 33333333);
        assert(!sync.videoPts(1033333333)); // Duplicate and reversed timestamps rejected.
        assert(!sync.videoPts(999999999));
        assert(sync.setAudioAnchor(48000, 1000000000, 48000));
        assert(sync.audioPts(96000) == 1000000000);
        assert(sync.audioPts(48001) == 20833);
        assert(sync.audioPts(48000 + 48000LL * 7200) == 7200000000000LL);
        assert(sync.audioDriftNs(96000, 2000000500) == 500);
        assert(!sync.setAudioAnchor(0, 0, 48000)); // No silently discontinuous re-anchor.
        assert(sync.begin(1000, VideoClock::Unknown));
        assert(!sync.videoPts(2000)); // Never assume sensor UNKNOWN shares the audio clock.
        assert(sync.begin(1000, VideoClock::Unknown, 500));
        assert(sync.videoPts(600) == 100);
        assert(sync.begin(0, VideoClock::Unknown, std::numeric_limits<int64_t>::max()));
        assert(!sync.videoPts(1));
        assert(sync.setAudioAnchor(0, 0, 44100));
        assert(sync.audioPts(44100) == 1000000000);
        assert(!sync.audioPts(std::numeric_limits<int64_t>::max()));
    }
    {
        FrameMetadata m;
        m.width = 4; m.height = 2; m.stride = 12; m.bitDepth = 10;
        assert(validRaw16Layout(20, 32, m)); // Last row need not include padding.
        assert(!validRaw16Layout(19, 32, m));
        assert(!validRaw16Layout(33, 32, m));
        m.stride = 7; assert(!validRaw16Layout(20, 32, m));
    }
    {
        auto* singleton = GetGlobalEngine();
        ReleaseGlobalEngine();
        assert(GetGlobalEngine() == singleton);
        CineRawEngine engine;
        assert(engine.ingestionCapacity() == -1);
        assert(!engine.initialize(-1, 2, 10, 2));
        assert(!engine.getLastError().empty()); // Previously self-deadlocked.
        assert(!engine.initialize(4080, 3060, 10, 90)); // Bounded RAM, no 2GB allocation.
        assert(engine.initialize(4, 2, 10, 4));
        assert(engine.initialize(4, 2, 10, 4)); // Previously recursive-lock deadlock.
        FrameMetadata m; m.width = 4; m.height = 2; m.stride = 8; m.bitDepth = 10;
        uint8_t pixels[16] = {};
        assert(!engine.onRawFrameAvailable(pixels, sizeof(pixels), m)); // Idle cannot fill queue.
        RecordingConfig config;
        config.width = 4; config.height = 2; config.bitDepth = 10;
        config.outputPath = "build/native-tests/recording";
        std::filesystem::remove_all(config.outputPath);
        std::string error;
        config.enableAudio = true; config.enableZstd = true;
        assert(!engine.startRecording(config, error)); // Unimplemented audio/ZSTD rejected.
        config.enableAudio = false; config.enableZstd = false;
        config.outputPath = "/dev/null/not-a-directory";
        assert(!engine.startRecording(config, error));
        config.outputPath = "build/native-tests/recording";
        assert(engine.startRecording(config, error));
        assert(!engine.onRawFrameAvailable(nullptr, 16, m));
        assert(!engine.onRawFrameAvailable(pixels, 15, m));
        assert(!engine.onRawFrameAvailable(pixels, 5000, m));
        for (int n = 0; n < 32; ++n) {
            m.frameNumber = n;
            while (!engine.onRawFrameAvailable(pixels, sizeof(pixels), m)) std::this_thread::yield();
        }
        std::thread diagnosticReader([&] { for (int n = 0; n < 100; ++n) (void)engine.getStats(); });
        assert(engine.stopRecording());
        diagnosticReader.join();
        assert(engine.getStats().poolAvailable == 4);
        assert(engine.getStats().framesWritten == 32);
        const auto diagnostics = engine.getStats();
        assert(diagnostics.framesDropped >= 3);
        assert(diagnostics.invalidLayout == 3);
        assert(diagnostics.notRecording == 1);
        assert(diagnostics.poolPeakUsed > 0 && diagnostics.poolPeakUsed <= 4);
        assert(diagnostics.rowCopyNs > 0 && diagnostics.maxRowCopyNs <= diagnostics.rowCopyNs);
        assert(diagnostics.framesDropped == diagnostics.poolExhausted + diagnostics.controlBusy + diagnostics.invalidLayout +
            diagnostics.timestampRejected + diagnostics.queueRejected + diagnostics.notRecording + diagnostics.writerUnavailable);
        assert(std::filesystem::file_size(config.outputPath + "/capture.mcraw") > 32 * 16);
        engine.release(); engine.release();
        assert(!engine.isInitialized());
        // Simulate I/O failure without filling a disk: output file resolves to /dev/full.
        std::filesystem::remove_all(config.outputPath);
        std::filesystem::create_directories(config.outputPath);
        std::filesystem::create_symlink("/dev/full", config.outputPath + "/capture.mcraw");
        assert(engine.initialize(4, 2, 10, 4));
        assert(engine.startRecording(config, error));
        assert(engine.onRawFrameAvailable(pixels, sizeof(pixels), m));
        // stop joins/drains; stream failure is reported, not silently marked successful.
        assert(!engine.stopRecording());
        assert(!engine.getLastError().empty());
        assert(engine.getStats().writerStatus == WriterStatus::IO_ERROR);
        assert(engine.getStats().poolAvailable == 4);
        engine.release();
        std::filesystem::remove_all(config.outputPath);
    }
    {
        const uint8_t known[] = {'1','2','3','4','5','6','7','8','9'};
        assert(ccrawCrc32(known, 9) == 0xcbf43926U);
        const std::string folder = "build/native-tests/ccraw";
        std::filesystem::remove_all(folder);
        CineRawEngine engine;
        assert(engine.initialize(4, 2, 16, 3));
        RecordingConfig config;
        config.width = 4; config.height = 2; config.bitDepth = 16;
        config.container = "ccraw"; config.epochNs = 100; config.outputPath = folder;
        std::string error;
        assert(engine.startRecording(config, error));
        FrameMetadata metadata;
        metadata.width = 4; metadata.height = 2; metadata.stride = 12; metadata.bitDepth = 16;
        metadata.exposureTimeNs = 1000; metadata.iso = 200;
        uint8_t plane[20];
        for (int i = 0; i < 20; ++i) plane[i] = static_cast<uint8_t>(i);
        metadata.timestampNs = 200;
        assert(!engine.onRaw16PlaneAvailable(plane, 19, metadata));
        assert(engine.onRaw16PlaneAvailable(plane, 20, metadata));
        assert(!engine.onRaw16PlaneAvailable(plane, 20, metadata)); // Reject duplicate before it can poison writer.
        metadata.timestampNs = 300; metadata.frameNumber = 1;
        assert(engine.onRaw16PlaneAvailable(plane, 20, metadata));
        assert(engine.stopRecording());
        const auto stats = engine.getStats();
        assert(stats.framesWritten == 2);
        assert(stats.timestampRejected == 1);
        assert(stats.invalidLayout == 1);
        assert(stats.writerTimings.payloadBytes == 32);
        assert(stats.writerTimings.crcNs > 0 && stats.writerTimings.writeSyscallNs > 0);
        assert(stats.writerTimings.fsyncNs > 0 && stats.writerTimings.finalizeNs >= stats.writerTimings.fsyncNs);
        assert(stats.writerTimings.appendNs >= stats.writerTimings.maxAppendNs);
        assert(engine.ingestionCapacity() == -1);
        assert(engine.getStats().poolAvailable == 3);
        assert(std::filesystem::file_size(folder + "/capture.ccraw") == 320);
        assert(!std::filesystem::exists(folder + "/capture.ccraw.partial"));
        engine.release();
        CcrawWriter collision;
        assert(!collision.open(folder, 4, 2, 100)); // Existing output must not be overwritten.
        std::filesystem::create_directories(folder + "/invalid");
        CcrawWriter invalid;
        assert(invalid.open(folder + "/invalid", 4, 2, 100));
        RawFrame frame; frame.data = plane; frame.sizeBytes = 16; frame.isValid = true;
        frame.metadata = metadata; frame.metadata.stride = 8;
        frame.metadata.timestampNs = 200;
        assert(invalid.append(frame));
        assert(!invalid.append(frame)); // Duplicate timestamp poisons finalization.
        assert(!invalid.finish());
        assert(!std::filesystem::exists(folder + "/invalid/capture.ccraw"));
    }

}
