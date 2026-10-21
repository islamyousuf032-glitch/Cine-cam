#include "cine_raw_engine.h"
#include "raw_layout.h"
#include "sync_timeline.h"
#include "ccraw_writer.h"
#include "raw16_codec.h"
#include "bp16_archive.h"
#include <cassert>
#include <filesystem>
#include <future>
#include <limits>
#include <thread>
#include <array>
#include <vector>
#include <cstring>
#include <cstdio>
#include <cerrno>
#include <fstream>
#include <iterator>
#include <fcntl.h>
#if defined(CINECAM_ARM64_CRC) && defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif

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
        std::vector<uint8_t> raw(kRaw16BlockBytes + 16), encoded(kRaw16BlockBytes + kRaw16BlockHeader), decoded(kRaw16BlockBytes);
        uint32_t seed = 71;
        for (unsigned bits = 0; bits <= 16; ++bits) {
            const uint32_t mask = bits ? (1U << bits) - 1 : 0;
            for (size_t offset = 0; offset < 4; ++offset) {
                for (size_t size : {2U, 6U, 14U, 128U, 65536U}) {
                    for (size_t n = 0; n < size; n += 2) {
                        seed = seed * 1664525U + 1013904223U;
                        const uint32_t value = (seed >> 8) & mask;
                        raw[offset + n] = value; raw[offset + n + 1] = value >> 8;
                    }
                    size_t packed = 0, restored = 0;
                    assert(packRaw16Block(raw.data() + offset, size, encoded.data(), encoded.size(), packed));
                    assert(unpackRaw16Block(encoded.data(), packed, decoded.data(), decoded.size(), restored));
                    assert(restored == size && std::memcmp(raw.data() + offset, decoded.data(), size) == 0);
                    assert(!unpackRaw16Block(encoded.data(), packed - 1, decoded.data(), decoded.size(), restored));
                }
            }
        }
        size_t written = 99;
        assert(!packRaw16Block(raw.data(), 1, encoded.data(), encoded.size(), written) && written == 0);
        assert(!packRaw16Block(raw.data(), 65538, encoded.data(), encoded.size(), written));
        assert(!packRaw16Block(raw.data(), 2, encoded.data(), 7, written));
        raw[0] = 1; raw[1] = 0;
        assert(packRaw16Block(raw.data(), 2, encoded.data(), encoded.size(), written));
        const auto size = written;
        encoded[size - 1] |= 0x80;
        assert(!unpackRaw16Block(encoded.data(), size, decoded.data(), decoded.size(), written));
        encoded[4] = 17;
        assert(!unpackRaw16Block(encoded.data(), size, decoded.data(), decoded.size(), written));
        const std::string source = "build/native-tests/codec-probe.bin";
        { std::ofstream out(source, std::ios::binary); out.write(reinterpret_cast<const char*>(raw.data()), raw.size()); }
        const auto before = std::filesystem::file_size(source);
        const auto probe = probeRaw16File(source.c_str(), 2, 65536);
        assert(probe.ok && probe.inputBytes == 65536 && probe.blocks == 1 && probe.packNs > 0 && probe.unpackNs > 0);
        assert(std::filesystem::file_size(source) == before);
        assert(!probeRaw16File(source.c_str(), -1, 2).ok);
        assert(!probeRaw16File(source.c_str(), 0, 1).ok);
        assert(!probeRaw16File(source.c_str(), before, 2).ok);
        assert(!probeRaw16File("build/native-tests/missing-codec-file", 0, 2).ok);
    }

    std::printf("CRC backend: %s; hardware self-test: %s\n", ccrawCrcBackend(), ccrawHardwareCrcStatus());
#ifdef CINECAM_FORCE_PORTABLE_CRC
    assert(std::strcmp(ccrawCrcBackend(), "slicing-by-8-forced") == 0);
#elif defined(CINECAM_ARM64_CRC) && defined(__aarch64__) && defined(__linux__)
    if (getauxval(AT_HWCAP) & HWCAP_CRC32) {
#ifdef CINECAM_CRC_TEST_FAIL_SELFTEST
        assert(std::strcmp(ccrawCrcBackend(), "slicing-by-8-self-test-fallback") == 0);
        assert(std::strcmp(ccrawHardwareCrcStatus(), "failed") == 0);
#else
        assert(std::strcmp(ccrawCrcBackend(), "arm64-crc32") == 0);
        assert(std::strcmp(ccrawHardwareCrcStatus(), "passed") == 0);
#endif
    } else assert(std::strcmp(ccrawCrcBackend(), "slicing-by-8") == 0);
#else
    assert(std::strcmp(ccrawCrcBackend(), "slicing-by-8") == 0);
#endif
    {
        std::vector<uint8_t> data(262144 + 16);
        uint32_t seed = 7;
        for (auto& byte : data) { seed = seed * 1664525U + 1013904223U; byte = seed >> 24; }
        assert(ccrawCrc32(nullptr, 0) == 0);
        for (size_t offset = 0; offset < 16; ++offset) {
            for (size_t size = 0; size < 80; ++size) {
                assert(ccrawCrc32Portable(data.data() + offset, size) == referenceCrc(data.data() + offset, size));
                assert(ccrawCrc32(data.data() + offset, size) == referenceCrc(data.data() + offset, size));
            }
            for (size_t size : {4095U, 65536U, 262144U}) {
                assert(ccrawCrc32Portable(data.data() + offset, size) == referenceCrc(data.data() + offset, size));
                assert(ccrawCrc32(data.data() + offset, size) == referenceCrc(data.data() + offset, size));
            }
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
        config.preallocateCcraw = true;
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
        assert(stats.writerTimings.writeCalls >= 6 && stats.writerTimings.maxWriteNs <= stats.writerTimings.writeSyscallNs);
        assert(stats.writerTimings.allocationNs > 0 && stats.writerTimings.closeNs > 0 && stats.writerTimings.renameNs > 0);
        if (stats.writerTimings.allocationError == 0) {
            assert(stats.writerTimings.reservedBytes == 992);
            assert(stats.writerTimings.truncateNs > 0);
        }
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

    {
        const std::string root = "build/native-tests/allocation";
        std::filesystem::remove_all(root);
        auto folder = [&](const char* name) { auto path = root + "/" + name; std::filesystem::create_directories(path); return path; };
        uint8_t data[16]{};
        RawFrame frame; frame.data = data; frame.sizeBytes = 16; frame.isValid = true;
        frame.metadata.width = 4; frame.metadata.height = 2; frame.metadata.stride = 8;
        frame.metadata.bitDepth = 16; frame.metadata.timestampNs = 200;
        CcrawWriter plain;
        const auto plainDir = folder("plain");
        assert(plain.open(plainDir, 4, 2, 100, false));
        assert(plain.append(frame)); assert(plain.finish());
        assert(plain.timings().reservedBytes == 0 && plain.timings().allocationNs == 0);
        CcrawWriter reserved;
        const auto reservedDir = folder("reserved");
        assert(reserved.open(reservedDir, 4, 2, 100, true));
        if (reserved.timings().reservedBytes > 0)
            assert(std::filesystem::file_size(reservedDir + "/capture.ccraw.partial") == 992);
        assert(reserved.append(frame)); assert(reserved.finish());
        auto bytes = [](const std::string& dir) {
            std::ifstream in(dir + "/capture.ccraw", std::ios::binary);
            return std::vector<char>(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
        };
        assert(bytes(plainDir) == bytes(reservedDir)); // No preallocated zero tail; exact same format/checksums.
        assert(bytes(reservedDir).size() == 208);
        CcrawWriter unsupported([](int, int64_t) { return EOPNOTSUPP; });
        assert(unsupported.open(folder("unsupported"), 4, 2, 100, true));
        assert(unsupported.timings().allocationError == EOPNOTSUPP && unsupported.timings().reservedBytes == 0);
        assert(unsupported.append(frame)); assert(unsupported.finish());
        CcrawWriter noSpace([](int, int64_t) { return ENOSPC; });
        assert(!noSpace.open(folder("full"), 4, 2, 100, true));
        assert(noSpace.timings().allocationError == ENOSPC);
        assert(!std::filesystem::exists(root + "/full/capture.ccraw"));
        CcrawWriter interrupted([](int fd, int64_t size) {
            static int count = 0;
            if (count++ == 0) return EINTR;
            return ::posix_fallocate(fd, 0, static_cast<off_t>(size));
        });
        assert(interrupted.open(folder("interrupted"), 4, 2, 100, true));
        assert(interrupted.append(frame)); assert(interrupted.finish());
        CcrawWriter empty;
        assert(empty.open(folder("empty"), 4, 2, 100, true));
        assert(empty.finish());
        assert(std::filesystem::file_size(root + "/empty/capture.ccraw") == 96);
    }

    {
        const char* source = "build/native-tests/ccraw/capture.ccraw";
        const char* archive = "build/native-tests/roundtrip.ccraw.bp16";
        std::filesystem::remove(archive);
        const auto result = createBp16Archive(source, archive);
        assert(result.ok && result.sourceBytes == 320 && result.archiveBytes > 24);
        assert(verifyBp16Archive(archive, source));
        assert(!createBp16Archive(source, archive).ok); // no overwrite
        assert(!createBp16Archive(source, source).ok);
        assert(verifyBp16Archive(archive, source));
        std::filesystem::copy_file(archive, "build/native-tests/bad.bp16", std::filesystem::copy_options::overwrite_existing);
        { std::fstream bad("build/native-tests/bad.bp16", std::ios::binary | std::ios::in | std::ios::out);
          bad.seekp(40); char byte = 0xff; bad.write(&byte, 1); }
        assert(!verifyBp16Archive("build/native-tests/bad.bp16", source));
        std::filesystem::copy_file(archive, "build/native-tests/trailing.bp16", std::filesystem::copy_options::overwrite_existing);
        { std::ofstream trailing("build/native-tests/trailing.bp16", std::ios::binary | std::ios::app); trailing.put(1); }
        assert(!verifyBp16Archive("build/native-tests/trailing.bp16", source));
        const char* multi = "build/native-tests/codec-probe.bin";
        std::filesystem::remove("build/native-tests/multi.bp16");
        assert(createBp16Archive(multi, "build/native-tests/multi.bp16").ok);
        assert(verifyBp16Archive("build/native-tests/multi.bp16", multi));
        assert(!verifyBp16Archive(archive, multi));
        std::filesystem::resize_file("build/native-tests/trailing.bp16", 23);
        assert(!verifyBp16Archive("build/native-tests/trailing.bp16", source));
        std::filesystem::copy_file(archive, "build/native-tests/bad-header.bp16", std::filesystem::copy_options::overwrite_existing);
        { std::fstream bad("build/native-tests/bad-header.bp16", std::ios::binary | std::ios::in | std::ios::out);
          bad.put(0); }
        assert(!verifyBp16Archive("build/native-tests/bad-header.bp16", source));
        // Cross-language fixture spans every actual 0..16-bit width and a 5-bit tail.
        { std::ofstream fixture("build/native-tests/all-bits.bin", std::ios::binary);
          for (unsigned bits = 0; bits <= 16; ++bits) {
              const unsigned mask = (1u << bits) - 1;
              for (unsigned i = 0; i < 32768; ++i) {
                  const unsigned value = i == 0 ? mask : ((i * 1103515245ULL + 12345) >> (i % 7)) & mask;
                  fixture.put(static_cast<char>(value)); fixture.put(static_cast<char>(value >> 8));
              }
          }
          const char tail[] = {31, 0, 7, 0, 1, 0}; fixture.write(tail, sizeof(tail)); }
        std::filesystem::remove("build/native-tests/all-bits.bp16");
        assert(createBp16Archive("build/native-tests/all-bits.bin", "build/native-tests/all-bits.bp16").ok);

        assert(!createBp16Archive("build/native-tests/no-source", "build/native-tests/no-output.bp16").ok);
        assert(!std::filesystem::exists("build/native-tests/no-output.bp16"));
    }

}
