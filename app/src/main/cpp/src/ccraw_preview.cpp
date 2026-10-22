#include "ccraw_preview.h"
#include "ccraw_writer.h"

#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cerrno>
#include <cstring>
#include <string>
#include <vector>

namespace cinecam {
namespace {

constexpr uint32_t kHeaderBytes = 64;
constexpr uint32_t kFrameHeaderBytes = 96;
constexpr uint64_t kMaxPayload = 256ull << 20;
constexpr uint64_t kMaxFileBytes = 1ull << 30;
constexpr uint64_t kMaxOutputPixels = 6ull << 20;
constexpr int kMaxDimension = 4096;

uint32_t u32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

bool preadAll(int fd, void* dst, size_t bytes, uint64_t offset) {
    size_t done = 0;
    while (done < bytes) {
        const ssize_t n = pread(fd, static_cast<uint8_t*>(dst) + done, bytes - done,
                                static_cast<off_t>(offset + done));
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (n == 0) return false;
        done += static_cast<size_t>(n);
    }
    return true;
}

double percentile(std::vector<uint32_t>& values, double fraction) {
    if (values.empty()) return 0;
    const size_t index = static_cast<size_t>(fraction * static_cast<double>(values.size() - 1));
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(index), values.end());
    return static_cast<double>(values[index]);
}

uint8_t srgb(double v) {
    if (!(v > 0)) return 0;
    if (v >= 1) return 255;
    const double g = v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055;
    return static_cast<uint8_t>(std::lround(std::clamp(g, 0.0, 1.0) * 255.0));
}

DngPreview failWith(const char* message) {
    DngPreview preview;
    preview.error = message;
    setPreviewError(message);
    return preview;
}

}  // namespace

CcrawPreviewResult renderCcrawFrame(int fd, int frameIndex, int maxDimension, int orientation) {
    CcrawPreviewResult result;
    if (fd < 0) { result.image = failWith("RAW container descriptor নেই"); return result; }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < static_cast<off_t>(kHeaderBytes + kFrameHeaderBytes)) {
        result.image = failWith("RAW container ফাইল সঠিক নয়");
        return result;
    }
    if (static_cast<uint64_t>(st.st_size) > kMaxFileBytes) {
        result.image = failWith("RAW container খুব বড়");
        return result;
    }
    std::array<uint8_t, kHeaderBytes> header{};
    if (!preadAll(fd, header.data(), header.size(), 0) ||
        std::memcmp(header.data(), "CCRAW001", 8) != 0 || u32(header.data() + 8) != kHeaderBytes) {
        result.image = failWith("CCRAW header সঠিক নয়");
        return result;
    }
    if (ccrawCrc32(header.data(), 60) != u32(header.data() + 60)) {
        result.image = failWith("CCRAW header checksum মেলেনি");
        return result;
    }
    const uint32_t width = u32(header.data() + 12);
    const uint32_t height = u32(header.data() + 16);
    const uint32_t bits = u32(header.data() + 20);
    if (width < 8 || height < 8 || width > 16384 || height > 16384 || bits != 16 ||
        static_cast<uint64_t>(width) * height > kMaxPayload / 2) {
        result.image = failWith("CCRAW মাপ অসমর্থিত");
        return result;
    }
    const uint64_t payload = static_cast<uint64_t>(width) * height * 2;
    const uint64_t stride = kFrameHeaderBytes + payload;
    const uint64_t fileBytes = static_cast<uint64_t>(st.st_size);
    if (frameIndex < 0 || frameIndex >= 64 ||
        kHeaderBytes + (static_cast<uint64_t>(frameIndex) + 1) * stride > fileBytes) {
        result.image = failWith("এই frame সংখ্যা RAW container-এ নেই");
        return result;
    }
    const uint64_t frameOffset = kHeaderBytes + static_cast<uint64_t>(frameIndex) * stride;
    std::array<uint8_t, kFrameHeaderBytes> frame{};
    if (!preadAll(fd, frame.data(), frame.size(), frameOffset) || std::memcmp(frame.data(), "FRM1", 4) != 0 ||
        u32(frame.data() + 4) != kFrameHeaderBytes || u32(frame.data() + 24) != payload ||
        u32(frame.data() + 32) != width || u32(frame.data() + 36) != height ||
        ccrawCrc32(frame.data(), 76) != u32(frame.data() + 76)) {
        result.image = failWith("Frame header অসম্পূর্ণ বা checksum ভুল");
        return result;
    }
    // Frame payload CRC is intentionally not recomputed for a read-only preview.
    std::vector<uint16_t> raw(static_cast<size_t>(width) * height);
    if (!preadAll(fd, raw.data(), static_cast<size_t>(payload), frameOffset + kFrameHeaderBytes)) {
        result.image = failWith("Frame data অসম্পূর্ণ");
        return result;
    }

    struct Sample { std::vector<uint32_t> channel[3]; };
    Sample samples;
    const uint64_t totalCells = static_cast<uint64_t>(width / 2) * (height / 2);
    const uint64_t stepCells = std::max<uint64_t>(1, totalCells / 120000);
    for (uint64_t cell = 0; cell < totalCells; cell += stepCells) {
        const uint32_t cx = static_cast<uint32_t>(cell % (width / 2));
        const uint32_t cy = static_cast<uint32_t>(cell / (width / 2));
        const uint16_t* row0 = raw.data() + static_cast<size_t>(cy * 2) * width;
        const uint16_t* row1 = row0 + width;
        const uint16_t values[4] = {row0[cx * 2], row0[cx * 2 + 1], row1[cx * 2], row1[cx * 2 + 1]};
        const int channels[4] = {0, 1, 1, 2};  // RGGB default for the diagnostic container.
        for (int i = 0; i < 4; ++i) samples.channel[channels[i]].push_back(values[i]);
    }
    std::array<double, 3> median{}, high{}, low{};
    for (int c = 0; c < 3; ++c) {
        median[c] = percentile(samples.channel[c], 0.5);
        high[c] = percentile(samples.channel[c], 0.995);
        low[c] = percentile(samples.channel[c], 0.005);
    }
    const double green = std::max(median[1], 1.0);
    for (int c = 0; c < 3; ++c) result.gain[c] = std::clamp(green / std::max(median[c], 1.0), 0.2, 5.0);
    for (int c = 0; c < 3; ++c) {
        high[c] = std::min(std::max(high[c], low[c] + 1.0), 65535.0);
    }

    const int longest = static_cast<int>(std::max(width / 2, height / 2));
    const int requested = std::clamp(maxDimension, 16, kMaxDimension);
    int down = (longest + requested - 1) / requested;
    if (down < 1) down = 1;
    const int outW = static_cast<int>((width / 2 + static_cast<uint32_t>(down) - 1) / static_cast<uint32_t>(down));
    const int outH = static_cast<int>((height / 2 + static_cast<uint32_t>(down) - 1) / static_cast<uint32_t>(down));
    if (outW < 1 || outH < 1 || static_cast<uint64_t>(outW) * outH > kMaxOutputPixels) {
        result.image = failWith("Preview আকার সীমার বাইরে");
        return result;
    }
    std::vector<uint32_t> pixels(static_cast<size_t>(outW) * outH);
    for (int oy = 0; oy < outH; ++oy) {
        const uint32_t cy0 = static_cast<uint32_t>(oy) * static_cast<uint32_t>(down);
        const uint32_t cyEnd = std::min<uint32_t>(cy0 + static_cast<uint32_t>(down), height / 2);
        for (int ox = 0; ox < outW; ++ox) {
            const uint32_t cx0 = static_cast<uint32_t>(ox) * static_cast<uint32_t>(down);
            const uint32_t cxEnd = std::min<uint32_t>(cx0 + static_cast<uint32_t>(down), width / 2);
            double sum[3] = {0, 0, 0};
            uint32_t counts[3] = {0, 0, 0};
            for (uint32_t cy = cy0; cy < cyEnd; ++cy) {
                const uint16_t* row0 = raw.data() + static_cast<size_t>(cy * 2) * width;
                const uint16_t* row1 = row0 + width;
                for (uint32_t cx = cx0; cx < cxEnd; ++cx) {
                    const uint16_t values[4] = {row0[cx * 2], row0[cx * 2 + 1], row1[cx * 2], row1[cx * 2 + 1]};
                    const int channels[4] = {0, 1, 1, 2};
                    for (int i = 0; i < 4; ++i) {
                        const int c = channels[i];
                        sum[c] += values[i];
                        ++counts[c];
                    }
                }
            }
            double rgb[3] = {0, 0, 0};
            for (int c = 0; c < 3; ++c) {
                if (counts[c] == 0) continue;
                // Per-channel auto black/white point, then grey-world white balance.
                const double normalized = (sum[c] / counts[c] - low[c]) / (high[c] - low[c]);
                rgb[c] = std::clamp(normalized * result.gain[c], 0.0, 1.0);
            }
            pixels[static_cast<size_t>(oy) * outW + ox] = 0xffu << 24 |
                (static_cast<uint32_t>(srgb(rgb[0])) << 16) |
                (static_cast<uint32_t>(srgb(rgb[1])) << 8) | static_cast<uint32_t>(srgb(rgb[2]));
        }
    }
    result.image.ok = true;
    result.image.width = outW;
    result.image.height = outH;
    result.image.orientation = orientation;
    result.image.argb = std::move(pixels);
    result.frameIndex = frameIndex;
    setPreviewError("");
    return result;
}

}  // namespace cinecam
