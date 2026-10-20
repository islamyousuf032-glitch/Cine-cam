#include "ccraw_writer.h"
#include <array>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace cinecam {
namespace {
void put32(uint8_t* p, uint32_t n) { for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(n >> (8 * i)); }
void put64(uint8_t* p, uint64_t n) { for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(n >> (8 * i)); }
void putFloat(uint8_t* p, float n) { uint32_t bits; static_assert(sizeof(n) == 4); std::memcpy(&bits, &n, 4); put32(p, bits); }
}
uint32_t ccrawCrc32(const uint8_t* p, size_t size) {
    static const auto table = [] {
        std::array<uint32_t, 256> t{};
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t n = i;
            for (int b = 0; b < 8; ++b) n = (n >> 1) ^ ((n & 1) ? 0xedb88320U : 0);
            t[i] = n;
        }
        return t;
    }();
    uint32_t crc = 0xffffffffU;
    for (size_t i = 0; i < size; ++i) crc = table[(crc ^ p[i]) & 255] ^ (crc >> 8);
    return crc ^ 0xffffffffU;
}
CcrawWriter::~CcrawWriter() { if (fd_ >= 0) ::close(fd_); }
bool CcrawWriter::writeAll(const void* data, size_t size) {
    auto p = static_cast<const uint8_t*>(data);
    while (size) {
        const auto n = ::write(fd_, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { failed_ = true; error_ = "CCRAW write failed (errno=" + std::to_string(errno) + ")"; return false; }
        p += n; size -= static_cast<size_t>(n);
    }
    return true;
}
bool CcrawWriter::open(const std::string& directory, uint32_t width, uint32_t height, int64_t epoch) {
    if (fd_ >= 0 || width == 0 || height == 0 || width > 16384 || height > 16384 || epoch < 0 ||
        uint64_t(width) * height * 2 > 256ULL * 1024 * 1024) { error_ = "Invalid CCRAW configuration"; return false; }
    partial_ = directory + "/capture.ccraw.partial";
    final_ = directory + "/capture.ccraw";
    struct stat st{};
    if (::stat(final_.c_str(), &st) == 0) { error_ = "CCRAW destination already exists"; return false; }
    fd_ = ::open(partial_.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd_ < 0) { error_ = "Cannot exclusively create CCRAW partial"; return false; }
    width_ = width; height_ = height; epoch_ = epoch;
    count_ = 0; payload_ = 0; lastTimestamp_ = -1; failed_ = false;
    std::array<uint8_t, 64> h{};
    std::memcpy(h.data(), "CCRAW001", 8);
    put32(h.data() + 8, 64); put32(h.data() + 12, width); put32(h.data() + 16, height);
    put32(h.data() + 20, 16); put32(h.data() + 24, 0); // 0 = no promised fixed FPS
    put32(h.data() + 28, 1); // BOOTTIME timestamp domain
    put64(h.data() + 32, static_cast<uint64_t>(epoch));
    put32(h.data() + 60, ccrawCrc32(h.data(), 60));
    return writeAll(h.data(), h.size());
}
bool CcrawWriter::append(const RawFrame& frame) {
    const auto& m = frame.metadata;
    const uint64_t expected = uint64_t(width_) * height_ * 2;
    if (fd_ < 0 || failed_ || !frame.data || !frame.isValid || count_ >= 8 ||
        frame.sizeBytes != expected || payload_ + expected > 256ULL * 1024 * 1024 ||
        m.width != static_cast<int32_t>(width_) || m.height != static_cast<int32_t>(height_) ||
        m.bitDepth != 16 || m.exposureTimeNs < 0 || m.iso < 0 || m.frameNumber < 0 ||
        m.stride != static_cast<int32_t>(width_ * 2) || m.timestampNs < epoch_ || m.timestampNs <= lastTimestamp_) {
        failed_ = true; error_ = "CCRAW frame layout, limit or timestamp invalid"; return false;
    }
    std::array<uint8_t, 96> h{};
    std::memcpy(h.data(), "FRM1", 4); put32(h.data() + 4, 96);
    put64(h.data() + 8, static_cast<uint64_t>(m.timestampNs));
    put64(h.data() + 16, static_cast<uint64_t>(m.exposureTimeNs)); put64(h.data() + 24, expected);
    put32(h.data() + 32, width_); put32(h.data() + 36, height_);
    put32(h.data() + 40, width_ * 2); put32(h.data() + 44, static_cast<uint32_t>(m.iso));
    put32(h.data() + 48, static_cast<uint32_t>(m.frameNumber)); put32(h.data() + 52, 16);
    putFloat(h.data() + 56, m.focusDistance);
    for (int i = 0; i < 3; ++i) putFloat(h.data() + 60 + i * 4, m.neutralWhitePoint[i]);
    put32(h.data() + 72, ccrawCrc32(frame.data, frame.sizeBytes));
    put32(h.data() + 76, ccrawCrc32(h.data(), 76));
    if (!writeAll(h.data(), h.size()) || !writeAll(frame.data, frame.sizeBytes)) return false;
    ++count_; payload_ += expected; lastTimestamp_ = m.timestampNs;
    return true;
}
bool CcrawWriter::finish() {
    if (fd_ < 0) return false;
    bool ok = !failed_;
    if (ok) {
        std::array<uint8_t, 32> footer{};
        std::memcpy(footer.data(), "END1", 4); put32(footer.data() + 4, 32);
        put64(footer.data() + 8, count_); put64(footer.data() + 16, payload_);
        put32(footer.data() + 24, ccrawCrc32(footer.data(), 24));
        ok = writeAll(footer.data(), footer.size());
    }
    if (ok && ::fsync(fd_) != 0) { error_ = "CCRAW fsync failed"; ok = false; }
    if (::close(fd_) != 0) { error_ = "CCRAW close failed"; ok = false; }
    fd_ = -1;
    if (ok && ::rename(partial_.c_str(), final_.c_str()) != 0) { error_ = "CCRAW finalize rename failed"; ok = false; }
    return ok;
}
}
