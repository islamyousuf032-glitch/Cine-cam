#include "ccraw_preview.h"
#include "ccraw_writer.h"

#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <vector>

namespace cinecam {
namespace {

constexpr uint32_t kHeaderBytes = 64;
constexpr uint32_t kFrameHeaderBytes = 96;
constexpr uint32_t kFooterBytes = 32;
constexpr uint64_t kMaxPayload = 256ull << 20;
// Long sessions legitimately exceed the old 1 GiB burst file; only the wrapper stays bounded.
constexpr uint64_t kMaxFileBytes = 8ull << 30;
constexpr uint64_t kMaxFrames = 100000;
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

bool invert3(const std::array<double, 9>& m, std::array<double, 9>& out) {
    const double a = m[0], b = m[1], c = m[2];
    const double d = m[3], e = m[4], f = m[5];
    const double g = m[6], h = m[7], i = m[8];
    const double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if (!std::isfinite(det) || std::fabs(det) < 1e-12) return false;
    const double inv = 1.0 / det;
    out = {{(e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
            (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
            (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv}};
    return true;
}

void multiply3(const std::array<double, 9>& a, const std::array<double, 9>& b, std::array<double, 9>& out) {
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            double sum = 0;
            for (int k = 0; k < 3; ++k) sum += a[row * 3 + k] * b[k * 3 + col];
            out[row * 3 + col] = sum;
        }
    }
}

const uint8_t* cfaFor(int cfa) {
    static const uint8_t kPatterns[4][4] = {
        {0, 1, 1, 2},  // RGGB
        {1, 0, 2, 1},  // GRBG
        {1, 2, 0, 1},  // GBRG
        {2, 1, 1, 0},  // BGGR
    };
    return kPatterns[std::clamp(cfa, 0, 3)];
}

DngPreview failWith(const char* message) {
    DngPreview preview;
    preview.error = message;
    setPreviewError(message);
    return preview;
}

struct FrameLayout {
    uint32_t width = 0;
    uint32_t height = 0;
    uint64_t payload = 0;
    uint64_t payloadOffset = 0;
};

bool readFrameLayout(int fd, int frameIndex, FrameLayout& out) {
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) ||
        st.st_size < static_cast<off_t>(kHeaderBytes + kFrameHeaderBytes)) return false;
    if (static_cast<uint64_t>(st.st_size) > kMaxFileBytes) return false;
    std::array<uint8_t, kHeaderBytes> header{};
    if (!preadAll(fd, header.data(), header.size(), 0) ||
        std::memcmp(header.data(), "CCRAW001", 8) != 0 || u32(header.data() + 8) != kHeaderBytes) return false;
    if (ccrawCrc32(header.data(), 60) != u32(header.data() + 60)) return false;
    out.width = u32(header.data() + 12);
    out.height = u32(header.data() + 16);
    const uint32_t bits = u32(header.data() + 20);
    if (out.width < 8 || out.height < 8 || out.width > 16384 || out.height > 16384 || bits != 16 ||
        static_cast<uint64_t>(out.width) * out.height > kMaxPayload / 2) return false;
    out.payload = static_cast<uint64_t>(out.width) * out.height * 2;
    const uint64_t stride = kFrameHeaderBytes + out.payload;
    const uint64_t fileBytes = static_cast<uint64_t>(st.st_size);
    // Frame count comes from the real file size (header + frames + footer), not a fixed cap.
    const uint64_t frames = fileBytes >= kFooterBytes
        ? ((fileBytes - kFooterBytes - kHeaderBytes) / stride) : 0;
    if (frameIndex < 0 || static_cast<uint64_t>(frameIndex) >= std::min(frames, kMaxFrames) ||
        kHeaderBytes + (static_cast<uint64_t>(frameIndex) + 1) * stride > fileBytes) return false;
    out.payloadOffset = kHeaderBytes + static_cast<uint64_t>(frameIndex) * stride + kFrameHeaderBytes;
    std::array<uint8_t, kFrameHeaderBytes> frame{};
    if (!preadAll(fd, frame.data(), frame.size(), kHeaderBytes + static_cast<uint64_t>(frameIndex) * stride)) return false;
    return std::memcmp(frame.data(), "FRM1", 4) == 0 && u32(frame.data() + 4) == kFrameHeaderBytes &&
           u32(frame.data() + 24) == out.payload && u32(frame.data() + 32) == out.width &&
           u32(frame.data() + 36) == out.height && ccrawCrc32(frame.data(), 76) == u32(frame.data() + 76);
}

/** Reads `cellRows` consecutive 2-pixel-row blocks starting at cell row `cellY`.
 *  Buffer is 2 rows per cell row, packed width*2 samples per pixel row. */
bool readCellRows(int fd, const FrameLayout& layout, uint32_t cellY, uint32_t cellRows,
                  std::vector<uint8_t>& buffer) {
    const uint64_t bytes = static_cast<uint64_t>(cellRows) * 2 * layout.width * 2;
    if (bytes > 16ull << 20) return false;
    buffer.resize(static_cast<size_t>(bytes));
    return preadAll(fd, buffer.data(), buffer.size(), layout.payloadOffset + static_cast<uint64_t>(cellY) * 2 * layout.width * 2);
}

uint16_t sampleAt(const std::vector<uint8_t>& buffer, uint32_t width, uint32_t row, uint32_t x) {
    const size_t pos = (static_cast<size_t>(row) * width + x) * 2;
    return static_cast<uint16_t>(buffer[pos] | (buffer[pos + 1] << 8));
}

}  // namespace

CcrawPreviewResult renderCcrawFrame(int fd, int frameIndex, int maxDimension, int orientation,
                                   const CcrawCalibration& calibration) {
    CcrawPreviewResult result;
    FrameLayout layout;
    if (fd < 0) { result.image = failWith("RAW container descriptor নেই"); return result; }
    if (!readFrameLayout(fd, frameIndex, layout)) {
        result.image = failWith("AI RAW container header/frame সঠিক নয় বা এই frame নেই");
        return result;
    }
    const uint32_t cellW = layout.width / 2;
    const uint32_t cellH = layout.height / 2;
    if (cellW < 2 || cellH < 2) { result.image = failWith("RAW মাপ অসমর্থিত"); return result; }

    const uint8_t* pattern = cfaFor(calibration.cfa);
    std::array<double, 4> black{};
    for (int i = 0; i < 4; ++i) black[i] = calibration.black[i];
    if (!calibration.valid) black = {{0, 0, 0, 0}};
    double white = calibration.valid && calibration.white > 0 ? calibration.white : 65535.0;

    std::array<double, 3> fallbackLow{}, fallbackHigh{};
    std::array<double, 3> gain{{1, 1, 1}};
    if (calibration.valid) {
        const double g = std::max(calibration.neutral[1], 1e-6);
        for (int c = 0; c < 3; ++c) {
            gain[c] = std::clamp(g / std::max(calibration.neutral[c], 1e-6), 0.05, 20.0);
        }
    } else {
        // No calibration metadata: robust per-channel percentile normalization.
        std::array<std::vector<uint32_t>, 3> samples;
        const uint32_t sampleRows = std::min<uint32_t>(cellH, 32);
        const uint32_t rowStep = std::max<uint32_t>(1, cellH / sampleRows);
        std::vector<uint8_t> rows;
        for (uint32_t cy = 0; cy < cellH; cy += rowStep) {
            if (!readCellRows(fd, layout, cy, 1, rows)) break;
            const uint32_t xStep = std::max<uint32_t>(1, cellW / 600);
            for (uint32_t cx = 0; cx < cellW; cx += xStep) {
                const uint16_t v[4] = {sampleAt(rows, layout.width, 0, cx * 2),
                                       sampleAt(rows, layout.width, 0, cx * 2 + 1),
                                       sampleAt(rows, layout.width, 1, cx * 2),
                                       sampleAt(rows, layout.width, 1, cx * 2 + 1)};
                for (int j = 0; j < 4; ++j) samples[pattern[j]].push_back(v[j]);
            }
        }
        for (int c = 0; c < 3; ++c) {
            fallbackLow[c] = percentile(samples[c], 0.01);
            fallbackHigh[c] = std::max(percentile(samples[c], 0.995), fallbackLow[c] + 1.0);
        }
        const double gm = std::max((fallbackLow[1] + fallbackHigh[1]) / 2, 1.0);
        for (int c = 0; c < 3; ++c) {
            gain[c] = std::clamp(gm / std::max((fallbackLow[c] + fallbackHigh[c]) / 2, 1.0), 0.2, 5.0);
        }
    }

    std::array<double, 9> transform{{1, 0, 0, 0, 1, 0, 0, 0, 1}};
    if (calibration.matrix != nullptr && calibration.matrixMode != 0) {
        // DNG matrices use XYZ D50. The D65 sRGB matrix previously pushed previews red.
        const std::array<double, 9> xyzD50ToSrgb{{3.1338561, -1.6168667, -0.4906146,
                                                  -0.9787684, 1.9161415, 0.0334540,
                                                  0.0719453, -0.2289914, 1.4052427}};
        std::array<double, 9> cameraToXyz{};
        for (int i = 0; i < 9; ++i) cameraToXyz[i] = calibration.matrix[i];
        if (calibration.matrixMode == 1) {
            std::array<double, 9> inverse{};
            cameraToXyz = invert3(cameraToXyz, inverse) ? inverse
                                                        : std::array<double, 9>{{1, 0, 0, 0, 1, 0, 0, 0, 1}};
        }
        multiply3(xyzD50ToSrgb, cameraToXyz, transform);
    }

    const int requested = std::clamp(maxDimension, 16, kMaxDimension);
    int step = static_cast<int>((std::max(cellW, cellH) + requested - 1) / requested);
    if (step < 1) step = 1;
    const int outW = static_cast<int>((cellW + static_cast<uint32_t>(step) - 1) / static_cast<uint32_t>(step));
    const int outH = static_cast<int>((cellH + static_cast<uint32_t>(step) - 1) / static_cast<uint32_t>(step));
    if (outW < 1 || outH < 1 || static_cast<uint64_t>(outW) * outH > kMaxOutputPixels) {
        result.image = failWith("Preview আকার সীমার বাইরে");
        return result;
    }
    std::vector<uint32_t> pixels(static_cast<size_t>(outW) * outH);
    std::vector<uint8_t> rows;
    for (int oy = 0; oy < outH; ++oy) {
        const uint32_t cy0 = static_cast<uint32_t>(oy) * static_cast<uint32_t>(step);
        const uint32_t cyEnd = std::min<uint32_t>(cy0 + static_cast<uint32_t>(step), cellH);
        if (!readCellRows(fd, layout, cy0, cyEnd - cy0, rows)) {
            result.image = failWith("RAW frame data অসম্পূর্ণ");
            return result;
        }
        for (int ox = 0; ox < outW; ++ox) {
            const uint32_t cx0 = static_cast<uint32_t>(ox) * static_cast<uint32_t>(step);
            const uint32_t cxEnd = std::min<uint32_t>(cx0 + static_cast<uint32_t>(step), cellW);
            double sum[3] = {0, 0, 0};
            uint32_t counts[3] = {0, 0, 0};
            for (uint32_t cy = cy0; cy < cyEnd; ++cy) {
                const uint32_t r0 = (cy - cy0) * 2;
                for (uint32_t cx = cx0; cx < cxEnd; ++cx) {
                    const uint16_t v[4] = {sampleAt(rows, layout.width, r0, cx * 2),
                                           sampleAt(rows, layout.width, r0, cx * 2 + 1),
                                           sampleAt(rows, layout.width, r0 + 1, cx * 2),
                                           sampleAt(rows, layout.width, r0 + 1, cx * 2 + 1)};
                    for (int j = 0; j < 4; ++j) {
                        const int c = pattern[j];
                        const double range = calibration.valid ? white - black[j]
                                                               : fallbackHigh[c] - fallbackLow[c];
                        if (!(range > 0)) continue;
                        const double base = calibration.valid ? black[j] : fallbackLow[c];
                        sum[c] += std::clamp((v[j] - base) / range, 0.0, 1.0);
                        ++counts[c];
                    }
                }
            }
            double rgb[3] = {0, 0, 0};
            for (int c = 0; c < 3; ++c) {
                if (counts[c] == 0) continue;
                rgb[c] = std::clamp((sum[c] / counts[c]) * gain[c], 0.0, 1.0);
            }
            const double r = transform[0] * rgb[0] + transform[1] * rgb[1] + transform[2] * rgb[2];
            const double g = transform[3] * rgb[0] + transform[4] * rgb[1] + transform[5] * rgb[2];
            const double b = transform[6] * rgb[0] + transform[7] * rgb[1] + transform[8] * rgb[2];
            pixels[static_cast<size_t>(oy) * outW + ox] = 0xffu << 24 |
                (static_cast<uint32_t>(srgb(r)) << 16) |
                (static_cast<uint32_t>(srgb(g)) << 8) | static_cast<uint32_t>(srgb(b));
        }
    }
    result.image.ok = true;
    result.image.width = outW;
    result.image.height = outH;
    result.image.orientation = orientation;
    result.image.argb = std::move(pixels);
    result.frameIndex = frameIndex;
    result.gain = gain;
    setPreviewError("");
    return result;
}

}  // namespace cinecam
