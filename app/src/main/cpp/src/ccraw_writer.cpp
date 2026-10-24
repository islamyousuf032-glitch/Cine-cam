#include "ccraw_writer.h"
#include "pipeline_timing.h"
#include <array>
#include <algorithm>
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
// Portable slicing-by-8 CRC32. Same IEEE polynomial/bytes as CCRAW001 v1.
// No unaligned uint32 dereference, host-endian assumption, ISA requirement or extra copy.
uint32_t ccrawCrc32Portable(const uint8_t* p, size_t size) {
    static const auto table = [] {
        std::array<std::array<uint32_t, 256>, 8> t{};
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t n = i;
            for (int b = 0; b < 8; ++b) n = (n >> 1) ^ ((n & 1) ? 0xedb88320U : 0);
            t[0][i] = n;
        }
        for (size_t slice = 1; slice < 8; ++slice)
            for (size_t i = 0; i < 256; ++i) {
                const auto n = t[slice - 1][i];
                t[slice][i] = (n >> 8) ^ t[0][n & 255];
            }
        return t;
    }();
    uint32_t crc = 0xffffffffU;
    while (size >= 8) {
        const uint32_t word = uint32_t(p[0]) | (uint32_t(p[1]) << 8) |
            (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
        crc ^= word;
        crc = table[7][crc & 255] ^ table[6][(crc >> 8) & 255] ^
              table[5][(crc >> 16) & 255] ^ table[4][crc >> 24] ^
              table[3][p[4]] ^ table[2][p[5]] ^ table[1][p[6]] ^ table[0][p[7]];
        p += 8; size -= 8;
    }
    while (size--) crc = table[0][(crc ^ *p++) & 255] ^ (crc >> 8);
    return crc ^ 0xffffffffU;
}
CcrawWriter::Timings CcrawWriter::timings() const {
    return {crcNs_.load(), writeNs_.load(), fsyncNs_.load(), appendNs_.load(),
            maxAppendNs_.load(), finalizeNs_.load(), payloadBytes_.load(), allocationNs_.load(), reservedBytes_.load(),
            truncateNs_.load(), closeNs_.load(), renameNs_.load(), writeCalls_.load(), maxWriteNs_.load(), allocationError_.load()};
}
CcrawWriter::~CcrawWriter() { if (fd_ >= 0 && ownsFd_) ::close(fd_); }
bool CcrawWriter::writeAll(const void* data, size_t size) {
    auto p = static_cast<const uint8_t*>(data);
    while (size) {
        const auto begin = pipelineNowNs();
        const auto n = ::write(fd_, p, size);
        const auto writeErrno = errno;
        const auto elapsed = pipelineNowNs() - begin;
        writeNs_.fetch_add(elapsed); writeCalls_.fetch_add(1);
        if (elapsed > maxWriteNs_.load()) maxWriteNs_.store(elapsed);
        errno = writeErrno;
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { failed_ = true; error_ = "CCRAW write failed (errno=" + std::to_string(errno) + ")"; return false; }
        fileOffset_ += static_cast<uint64_t>(n);
        p += n; size -= static_cast<size_t>(n);
    }
    return true;
}
bool CcrawWriter::open(const std::string& directory, uint32_t width, uint32_t height, int64_t epoch,
                       bool preallocate, uint32_t maxFrames, uint64_t maxBytes) {
    struct stat st{};
    const std::string finalPath = directory + "/capture.ccraw";
    if (::stat(finalPath.c_str(), &st) == 0) { error_ = "CCRAW destination already exists"; return false; }
    const std::string partialPath = directory + "/capture.ccraw.partial";
    const int fd = ::open(partialPath.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) { error_ = "Cannot exclusively create CCRAW partial"; return false; }
    if (!openInternal(fd, directory, true, width, height, epoch, preallocate, maxFrames, maxBytes)) {
        ::close(fd); return false; // exclusive create: a rejected config must not leave a partial
    }
    return true;
}
bool CcrawWriter::openFd(int fd, uint32_t width, uint32_t height, int64_t epoch,
                         bool preallocate, uint32_t maxFrames, uint64_t maxBytes) {
    if (fd < 0) { error_ = "Invalid CCRAW destination descriptor"; return false; }
    // A borrowed descriptor must be an empty file at offset 0, or the container would be corrupt.
    struct stat st{};
    if (::fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size != 0 || ::lseek(fd, 0, SEEK_CUR) != 0) {
        error_ = "CCRAW destination descriptor is not an empty file"; return false;
    }
    return openInternal(fd, std::string(), false, width, height, epoch, preallocate, maxFrames, maxBytes);
}
bool CcrawWriter::openInternal(int fd, const std::string& directory, bool ownsFd,
                               uint32_t width, uint32_t height, int64_t epoch,
                               bool preallocate, uint32_t maxFrames, uint64_t maxBytes) {
    const uint64_t framePayload = uint64_t(width) * height * 2;
    constexpr uint64_t kHeaderBytes = 64, kFrameHeaderBytes = 96, kFooterBytes = 32;
    if (fd_ >= 0 || fd < 0 || width == 0 || height == 0 || width > 16384 || height > 16384 || epoch < 0 ||
        framePayload > 256ULL * 1024 * 1024 || maxFrames == 0 || maxFrames > 100000 ||
        maxBytes < kHeaderBytes + kFooterBytes + kFrameHeaderBytes + framePayload ||
        maxBytes > 64ULL * 1024 * 1024 * 1024) { error_ = "Invalid CCRAW configuration"; return false; }
    // Reserve room for every frame header up front so the byte budget cannot be overshot.
    const uint64_t overhead = kHeaderBytes + kFooterBytes + uint64_t(maxFrames) * kFrameHeaderBytes;
    if (maxBytes < overhead + framePayload) { error_ = "Invalid CCRAW configuration"; return false; }
    const uint64_t payloadBudget = maxBytes - overhead;
    partial_ = ownsFd ? directory + "/capture.ccraw.partial" : std::string();
    final_ = ownsFd ? directory + "/capture.ccraw" : std::string();
    fd_ = fd; ownsFd_ = ownsFd;
    crcNs_.store(0); writeNs_.store(0); fsyncNs_.store(0);
    appendNs_.store(0); maxAppendNs_.store(0); finalizeNs_.store(0); payloadBytes_.store(0);
    allocationNs_.store(0); reservedBytes_.store(0); allocationError_.store(0);
    truncateNs_.store(0); closeNs_.store(0); renameNs_.store(0); writeCalls_.store(0); maxWriteNs_.store(0);
    fileOffset_ = 0; preallocated_ = false; error_.clear();
    maxFrames_ = maxFrames; maxPayload_ = payloadBudget;
    if (preallocate) {
        const uint64_t frames = std::min<uint64_t>(maxFrames, payloadBudget / framePayload);
        const int64_t reserve = static_cast<int64_t>(64 + frames * (96 + framePayload) + 32);
        const auto begin = pipelineNowNs();
        int result;
        do { result = allocator_ ? allocator_(fd_, reserve) : ::posix_fallocate(fd_, 0, static_cast<off_t>(reserve)); }
        while (result == EINTR);
        allocationNs_.store(pipelineNowNs() - begin); allocationError_.store(result);
        if (result == 0) { preallocated_ = true; reservedBytes_.store(reserve); }
        else if (result != EOPNOTSUPP && result != ENOSYS && result != EINVAL) {
            error_ = "CCRAW preallocation failed (errno=" + std::to_string(result) + ")";
            fd_ = -1; // The caller owns closing; open() closes its own exclusive file on failure.
            return false; // ENOSPC/permission/I/O errors are not silently ignored.
        }
    }
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
    if (fd_ < 0 || failed_ || !frame.data || !frame.isValid || count_ >= maxFrames_ ||
        frame.sizeBytes != expected || expected > maxPayload_ || payload_ + expected > maxPayload_ ||
        m.width != static_cast<int32_t>(width_) || m.height != static_cast<int32_t>(height_) ||
        m.bitDepth != 16 || m.exposureTimeNs < 0 || m.iso < 0 || m.frameNumber < 0 ||
        m.stride != static_cast<int32_t>(width_ * 2) || m.timestampNs < epoch_ || m.timestampNs <= lastTimestamp_) {
        failed_ = true; error_ = "CCRAW frame layout, limit or timestamp invalid"; return false;
    }
    const auto appendBegin = pipelineNowNs();
    std::array<uint8_t, 96> h{};
    std::memcpy(h.data(), "FRM1", 4); put32(h.data() + 4, 96);
    put64(h.data() + 8, static_cast<uint64_t>(m.timestampNs));
    put64(h.data() + 16, static_cast<uint64_t>(m.exposureTimeNs)); put64(h.data() + 24, expected);
    put32(h.data() + 32, width_); put32(h.data() + 36, height_);
    put32(h.data() + 40, width_ * 2); put32(h.data() + 44, static_cast<uint32_t>(m.iso));
    put32(h.data() + 48, static_cast<uint32_t>(m.frameNumber)); put32(h.data() + 52, 16);
    putFloat(h.data() + 56, m.focusDistance);
    for (int i = 0; i < 3; ++i) putFloat(h.data() + 60 + i * 4, m.neutralWhitePoint[i]);
    const auto crcBegin = pipelineNowNs();
    put32(h.data() + 72, ccrawCrc32(frame.data, frame.sizeBytes));
    crcNs_.fetch_add(pipelineNowNs() - crcBegin);
    put32(h.data() + 76, ccrawCrc32(h.data(), 76));
    if (!writeAll(h.data(), h.size()) || !writeAll(frame.data, frame.sizeBytes)) return false;
    ++count_; payload_ += expected; lastTimestamp_ = m.timestampNs;
    payloadBytes_.store(payload_);
    const auto elapsed = pipelineNowNs() - appendBegin;
    appendNs_.fetch_add(elapsed);
    if (elapsed > maxAppendNs_.load()) maxAppendNs_.store(elapsed);
    return true;
}
bool CcrawWriter::finish() {
    if (fd_ < 0) return false;
    const auto finishBegin = pipelineNowNs();
    bool ok = !failed_;
    if (ok) {
        std::array<uint8_t, 32> footer{};
        std::memcpy(footer.data(), "END1", 4); put32(footer.data() + 4, 32);
        put64(footer.data() + 8, count_); put64(footer.data() + 16, payload_);
        put32(footer.data() + 24, ccrawCrc32(footer.data(), 24));
        ok = writeAll(footer.data(), footer.size());
    }
    if (ok && preallocated_) {
        const auto truncateBegin = pipelineNowNs();
        int result;
        do { result = ::ftruncate(fd_, static_cast<off_t>(fileOffset_)); } while (result != 0 && errno == EINTR);
        truncateNs_.store(pipelineNowNs() - truncateBegin);
        if (result != 0) { error_ = "CCRAW truncate failed"; ok = false; }
    }
    if (ok) {
        const auto syncBegin = pipelineNowNs();
        int result;
        do { result = ::fsync(fd_); } while (result != 0 && errno == EINTR);
        fsyncNs_.fetch_add(pipelineNowNs() - syncBegin);
        if (result != 0) { error_ = "CCRAW fsync failed"; ok = false; }
    }
    if (ownsFd_) {
        const auto closeBegin = pipelineNowNs();
        if (::close(fd_) != 0) { error_ = "CCRAW close failed"; ok = false; }
        closeNs_.store(pipelineNowNs() - closeBegin);
        fd_ = -1;
        if (ok) {
            const auto renameBegin = pipelineNowNs();
            const auto result = ::rename(partial_.c_str(), final_.c_str());
            renameNs_.store(pipelineNowNs() - renameBegin);
            if (result != 0) { error_ = "CCRAW finalize rename failed"; ok = false; }
        }
    } else {
        fd_ = -1; // Borrowed descriptor: the caller closes it after publishing the provider row.
    }
    finalizeNs_.store(pipelineNowNs() - finishBegin);
    return ok;
}

// ---------------------------------------------------------------------------
// Segmented sessions
// ---------------------------------------------------------------------------
namespace {
constexpr uint64_t kSegHeaderBytes = 64, kSegFrameHeaderBytes = 96, kSegFooterBytes = 32;
}
bool CcrawSegmentWriter::open(std::vector<int> fds, uint32_t width, uint32_t height, int64_t epoch,
                              bool preallocate, uint32_t maxFrames, uint64_t maxBytes, uint64_t maxPartBytes) {
    if (fds.empty() || width == 0 || height == 0 || epoch < 0 || maxFrames == 0) {
        error_ = "Invalid CCRAW segment configuration"; return false;
    }
    const uint64_t frameBytes = uint64_t(width) * height * 2;
    if (frameBytes > 256ULL * 1024 * 1024) { error_ = "Invalid CCRAW segment frame size"; return false; }
    // A part must hold header + one frame + footer, or rotation would loop forever on one frame.
    if (maxPartBytes < kSegHeaderBytes + kSegFrameHeaderBytes + kSegFooterBytes + frameBytes) {
        error_ = "CCRAW part size too small"; return false;
    }
    const uint64_t minimum = kSegHeaderBytes + kSegFooterBytes +
        uint64_t(fds.size()) * (kSegFrameHeaderBytes + frameBytes);
    if (maxBytes < minimum) { error_ = "Invalid CCRAW segment budget"; return false; }
    fds_ = std::move(fds); width_ = width; height_ = height; epoch_ = epoch;
    preallocate_ = preallocate; maxFrames_ = maxFrames; maxBytes_ = maxBytes; maxPartBytes_ = maxPartBytes;
    payloadBudget_ = maxBytes - kSegHeaderBytes - kSegFooterBytes - uint64_t(fds_.size()) * kSegFrameHeaderBytes;
    partIndex_ = 0; stats_ = Stats{}; error_.clear();
    return openPart();
}
bool CcrawSegmentWriter::openPart() {
    if (partIndex_ >= fds_.size()) { error_ = "CCRAW segments exhausted"; return false; }
    const uint64_t frameBytes = uint64_t(width_) * height_ * 2;
    const uint64_t remainingParts = fds_.size() - partIndex_;
    const uint64_t perPartPayload = payloadBudget_ / remainingParts;
    // Frames must fit both the part's byte cap and its share of the session payload budget.
    uint64_t frames = (maxPartBytes_ - kSegHeaderBytes - kSegFooterBytes) / (kSegFrameHeaderBytes + frameBytes);
    frames = std::min(frames, maxFrames_ - stats_.frames);
    frames = std::min(frames, perPartPayload / frameBytes);
    if (frames == 0) { error_ = "CCRAW segment frame budget exhausted"; return false; }
    part_ = std::make_unique<CcrawWriter>();
    const uint64_t partBytes = kSegHeaderBytes + kSegFooterBytes + frames * (kSegFrameHeaderBytes + frameBytes);
    if (!part_->openFd(fds_[partIndex_], width_, height_, epoch_, preallocate_, frames, partBytes)) {
        error_ = part_->error(); part_.reset(); return false;
    }
    return true;
}
bool CcrawSegmentWriter::closePart() {
    if (!part_) return true;
    if (!part_->finish()) { error_ = part_->error(); part_.reset(); return false; }
    const auto timings = part_->timings();
    stats_.segments.push_back({part_->frameCount(),
        kSegHeaderBytes + part_->frameCount() * kSegFrameHeaderBytes + part_->payloadBytes() + kSegFooterBytes});
    stats_.payloadBytes += part_->payloadBytes();
    stats_.fsyncNs += timings.fsyncNs; stats_.appendNs += timings.appendNs;
    stats_.maxAppendNs = std::max(stats_.maxAppendNs, timings.maxAppendNs);
    stats_.writeSyscallNs += timings.writeSyscallNs;
    stats_.maxWriteNs = std::max(stats_.maxWriteNs, timings.maxWriteNs);
    stats_.crcNs += timings.crcNs;
    part_.reset();
    return true;
}
bool CcrawSegmentWriter::append(const RawFrame& frame) {
    if (!part_) { error_ = "CCRAW segment writer closed"; return false; }
    const uint64_t frameBytes = uint64_t(width_) * height_ * 2;
    // Rotate only when the next whole frame cannot fit in the current part, so no frame is split.
    const uint64_t used = kSegHeaderBytes + part_->frameCount() * kSegFrameHeaderBytes + part_->payloadBytes();
    const bool full = used + kSegFrameHeaderBytes + frameBytes + kSegFooterBytes > maxPartBytes_;
    if (full && partIndex_ + 1 < fds_.size()) {
        if (!closePart()) return false;
        ++partIndex_;
        if (!openPart()) return false;
    }
    if (!part_->append(frame)) { error_ = part_->error(); return false; }
    ++stats_.frames; stats_.parts = partIndex_ + 1;
    return true;
}
bool CcrawSegmentWriter::finish() {
    if (!closePart()) return false;
    stats_.parts = static_cast<uint64_t>(stats_.segments.size());
    return true;
}
}
