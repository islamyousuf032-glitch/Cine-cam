#include "bp16_archive.h"
#include "raw16_codec.h"
#include "ccraw_writer.h"
#include "pipeline_timing.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace cinecam {
namespace {
constexpr uint64_t limit = 257ULL * 1024 * 1024;
struct Fd { int n; ~Fd() { if (n >= 0) ::close(n); } };
uint32_t get32(const uint8_t* p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24; }
uint64_t get64(const uint8_t* p) { return uint64_t(get32(p)) | uint64_t(get32(p + 4)) << 32; }
void put32(uint8_t* p, uint32_t n) { for (int i = 0; i < 4; ++i) p[i] = n >> (8 * i); }
void put64(uint8_t* p, uint64_t n) { put32(p, n); put32(p + 4, n >> 32); }
bool readAll(int fd, uint8_t* p, size_t bytes, Bp16ArchiveResult* stats = nullptr) {
    while (bytes) {
        const auto begin = stats ? pipelineNowNs() : 0;
        const auto n = ::read(fd, p, bytes);
        const int error = errno;
        if (stats) { stats->sourceReadNs += pipelineNowNs() - begin; ++stats->readCalls; }
        if (n < 0 && error == EINTR) continue;
        if (n <= 0) return false;
        p += n; bytes -= static_cast<size_t>(n);
    }
    return true;
}
bool writeAll(int fd, const uint8_t* p, size_t bytes, Bp16ArchiveResult& stats) {
    while (bytes) {
        const auto begin = pipelineNowNs();
        const auto n = ::write(fd, p, bytes);
        const int error = errno;
        const auto elapsed = pipelineNowNs() - begin;
        stats.writeNs += elapsed; stats.maxWriteNs = std::max(stats.maxWriteNs, elapsed); ++stats.writeCalls;
        if (n < 0 && error == EINTR) continue;
        if (n <= 0) return false;
        p += n; bytes -= static_cast<size_t>(n);
    }
    return true;
}
bool eof(int fd) { uint8_t byte; int n; do { n = ::read(fd, &byte, 1); } while (n < 0 && errno == EINTR); return n == 0; }
}
bool verifyBp16Archive(const char* archive, const char* source) {
    Fd in{::open(archive, O_RDONLY | O_CLOEXEC)}, original{::open(source, O_RDONLY | O_CLOEXEC)};
    if (in.n < 0 || original.n < 0) return false;
    std::array<uint8_t, 24> header{};
    if (!readAll(in.n, header.data(), header.size()) || std::memcmp(header.data(), "CCBP0001", 8) != 0 ||
        get32(header.data() + 16) != kRaw16BlockBytes || ccrawCrc32(header.data(), 20) != get32(header.data() + 20)) return false;
    uint64_t remaining = get64(header.data() + 8);
    struct stat st{};
    if (!remaining || remaining % 2 || remaining > limit || ::fstat(original.n, &st) != 0 ||
        !S_ISREG(st.st_mode) || static_cast<uint64_t>(st.st_size) != remaining) return false;
    std::array<uint8_t, kRaw16BlockBytes + kRaw16BlockHeader> packed{};
    std::array<uint8_t, kRaw16BlockBytes> decoded{}, raw{};
    while (remaining) {
        std::array<uint8_t, 8> record{};
        if (!readAll(in.n, record.data(), record.size())) return false;
        const size_t count = get32(record.data());
        const size_t expected = static_cast<size_t>(std::min<uint64_t>(remaining, kRaw16BlockBytes));
        size_t restored = 0;
        if (count < 8 || count > packed.size() || !readAll(in.n, packed.data(), count) ||
            !unpackRaw16Block(packed.data(), count, decoded.data(), decoded.size(), restored) || restored != expected ||
            ccrawCrc32(decoded.data(), restored) != get32(record.data() + 4) ||
            !readAll(original.n, raw.data(), expected) || std::memcmp(raw.data(), decoded.data(), expected) != 0) return false;
        remaining -= restored;
    }
    return eof(in.n) && eof(original.n);
}
Bp16ArchiveResult createBp16Archive(const char* source, const char* destination) {
    Bp16ArchiveResult result;
    Fd in{::open(source, O_RDONLY | O_CLOEXEC)};
    struct stat st{};
    if (in.n < 0 || ::fstat(in.n, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size % 2 || static_cast<uint64_t>(st.st_size) > limit) { result.error = "Invalid archive source"; return result; }
    Fd out{::open(destination, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600)};
    if (out.n < 0) { result.error = "Cannot exclusively create archive"; return result; }
    // File is private until successful completion/readback and later MediaStore export.
    const auto begin = pipelineNowNs();
    auto fail = [&]() {
        if (!result.encodeWriteNs) result.encodeWriteNs = pipelineNowNs() - begin;
        ::unlink(destination); return result;
    };
    result.sourceBytes = static_cast<uint64_t>(st.st_size);
    std::array<uint8_t, 24> header{};
    std::memcpy(header.data(), "CCBP0001", 8); put64(header.data() + 8, result.sourceBytes);
    put32(header.data() + 16, kRaw16BlockBytes);
    const auto headerCrcBegin = pipelineNowNs();
    put32(header.data() + 20, ccrawCrc32(header.data(), 20));
    result.crcNs += pipelineNowNs() - headerCrcBegin;
    if (!writeAll(out.n, header.data(), header.size(), result)) { result.error = "Archive header write failed"; return fail(); }
    result.archiveBytes = header.size();
    std::array<uint8_t, kRaw16BlockBytes> raw{};
    std::array<uint8_t, kRaw16BlockBytes + kRaw16BlockHeader> packed{};
    for (uint64_t remaining = result.sourceBytes; remaining;) {
        const size_t count = static_cast<size_t>(std::min<uint64_t>(remaining, raw.size()));
        size_t encoded = 0;
        if (!readAll(in.n, raw.data(), count, &result)) {
            result.error = "Archive source read failed"; return fail();
        }
        const auto packBegin = pipelineNowNs();
        const bool packedOk = packRaw16Block(raw.data(), count, packed.data(), packed.size(), encoded);
        result.packNs += pipelineNowNs() - packBegin;
        if (!packedOk) { result.error = "Archive encode failed"; return fail(); }
        std::array<uint8_t, 8> record{};
        put32(record.data(), static_cast<uint32_t>(encoded));
        const auto crcBegin = pipelineNowNs();
        put32(record.data() + 4, ccrawCrc32(raw.data(), count));
        result.crcNs += pipelineNowNs() - crcBegin;
        if (!writeAll(out.n, record.data(), record.size(), result) || !writeAll(out.n, packed.data(), encoded, result)) {
            result.error = "Archive block write failed"; return fail();
        }
        result.archiveBytes += record.size() + encoded; remaining -= count; ++result.blocks;
    }
    int status;
    const auto syncBegin = pipelineNowNs();
    do { status = ::fsync(out.n); } while (status != 0 && errno == EINTR);
    result.fsyncNs = pipelineNowNs() - syncBegin;
    const auto closeBegin = pipelineNowNs();
    const int closeStatus = ::close(out.n); out.n = -1; // never retry close on EINTR
    result.closeNs = pipelineNowNs() - closeBegin;
    if (status != 0 || closeStatus != 0 || !eof(in.n)) { result.error = "Archive sync/close/source-size failed"; return fail(); }
    result.encodeWriteNs = pipelineNowNs() - begin;
    const auto verifyBegin = pipelineNowNs();
    result.ok = verifyBp16Archive(destination, source);
    result.verifyNs = pipelineNowNs() - verifyBegin;
    if (!result.ok) { result.error = "Archive full-file byte comparison failed"; return fail(); }
    return result;
}
}
