#include "raw16_codec.h"
#include "pipeline_timing.h"
#include <array>
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace cinecam {
bool packRaw16Block(const uint8_t* in, size_t bytes, uint8_t* out, size_t cap, size_t& written) {
    written = 0;
    if (!in || !out || !bytes || bytes % 2 || bytes > kRaw16BlockBytes) return false;
    uint32_t combined = 0;
    for (size_t i = 0; i < bytes; i += 2) combined |= uint32_t(in[i]) | (uint32_t(in[i + 1]) << 8);
    unsigned bits = 0;
    for (auto n = combined; n; n >>= 1) ++bits;
    const size_t samples = bytes / 2;
    const size_t size = kRaw16BlockHeader + (samples * bits + 7) / 8;
    if (cap < size) return false;
    std::memcpy(out, "BP16", 4); out[4] = static_cast<uint8_t>(bits); out[5] = 0;
    out[6] = static_cast<uint8_t>(samples); out[7] = static_cast<uint8_t>(samples >> 8);
    size_t at = kRaw16BlockHeader;
    uint32_t accumulator = 0; unsigned pending = 0;
    for (size_t i = 0; i < bytes; i += 2) {
        const uint32_t value = uint32_t(in[i]) | (uint32_t(in[i + 1]) << 8);
        accumulator |= value << pending; pending += bits;
        while (pending >= 8) { out[at++] = static_cast<uint8_t>(accumulator); accumulator >>= 8; pending -= 8; }
    }
    if (pending) out[at++] = static_cast<uint8_t>(accumulator);
    written = at;
    return at == size;
}
bool unpackRaw16Block(const uint8_t* in, size_t bytes, uint8_t* out, size_t cap, size_t& written) {
    written = 0;
    if (!in || !out || bytes < kRaw16BlockHeader || std::memcmp(in, "BP16", 4) != 0 || in[4] > 16 || in[5] != 0) return false;
    const unsigned bits = in[4];
    const size_t samples = size_t(in[6]) | (size_t(in[7]) << 8);
    const size_t expected = kRaw16BlockHeader + (samples * bits + 7) / 8;
    if (!samples || samples > kRaw16BlockBytes / 2 || bytes != expected || cap < samples * 2) return false;
    const unsigned used = static_cast<unsigned>((samples * bits) % 8);
    if (used && (in[bytes - 1] >> used) != 0) return false; // canonical zero tail bits
    size_t at = kRaw16BlockHeader;
    uint32_t accumulator = 0; unsigned pending = 0;
    const uint32_t mask = bits ? (uint32_t(1) << bits) - 1 : 0;
    for (size_t i = 0; i < samples; ++i) {
        while (pending < bits) { accumulator |= uint32_t(in[at++]) << pending; pending += 8; }
        const uint32_t value = accumulator & mask;
        out[2 * i] = static_cast<uint8_t>(value); out[2 * i + 1] = static_cast<uint8_t>(value >> 8);
        accumulator >>= bits; pending -= bits;
    }
    written = samples * 2;
    return true;
}
Raw16Probe probeRaw16File(const char* path, int64_t offset, int64_t bytes) {
    Raw16Probe result;
    if (!path || offset < 0 || bytes <= 0 || bytes % 2 || bytes > 256LL * 1024 * 1024) {
        result.error = "Invalid probe range"; return result;
    }
    const int fd = ::open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { result.error = "Cannot open probe source"; return result; }
    struct Close { int fd; ~Close() { ::close(fd); } } close{fd};
    struct stat st{};
    if (::fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || offset > st.st_size || bytes > st.st_size - offset ||
        ::lseek(fd, static_cast<off_t>(offset), SEEK_SET) < 0) {
        result.error = "Probe range outside source"; return result;
    }
    std::array<uint8_t, kRaw16BlockBytes> raw{}, decoded{};
    std::array<uint8_t, kRaw16BlockBytes + kRaw16BlockHeader> packed{};
    for (int64_t remaining = bytes; remaining > 0;) {
        const size_t count = static_cast<size_t>(std::min<int64_t>(remaining, kRaw16BlockBytes));
        const auto readBegin = pipelineNowNs();
        size_t got = 0;
        while (got < count) {
            const auto n = ::read(fd, raw.data() + got, count - got);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) { result.error = "Probe source read failed"; return result; }
            got += static_cast<size_t>(n);
        }
        result.readNs += pipelineNowNs() - readBegin;
        size_t encoded = 0, restored = 0;
        const auto packBegin = pipelineNowNs();
        const bool encodedOk = packRaw16Block(raw.data(), count, packed.data(), packed.size(), encoded);
        result.packNs += pipelineNowNs() - packBegin;
        const auto unpackBegin = pipelineNowNs();
        const bool decodedOk = encodedOk && unpackRaw16Block(packed.data(), encoded, decoded.data(), decoded.size(), restored);
        result.unpackNs += pipelineNowNs() - unpackBegin;
        if (!decodedOk || restored != count || std::memcmp(raw.data(), decoded.data(), count) != 0) {
            result.error = "Probe byte-exact roundtrip failed"; return result;
        }
        result.inputBytes += count; result.packedBytes += encoded; result.blocks++;
        remaining -= static_cast<int64_t>(count);
    }
    result.ok = true;
    return result;
}
}
