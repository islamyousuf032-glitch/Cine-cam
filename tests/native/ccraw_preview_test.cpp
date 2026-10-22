#include "ccraw_preview.h"
#include "ccraw_writer.h"

#include <fcntl.h>
#include <unistd.h>

#include <cassert>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

using namespace cinecam;

namespace {
void put32(std::vector<uint8_t>& b, size_t off, uint32_t v) {
    for (int i = 0; i < 4; ++i) b[off + i] = static_cast<uint8_t>(v >> (8 * i));
}
void put64(std::vector<uint8_t>& b, size_t off, uint64_t v) {
    for (int i = 0; i < 8; ++i) b[off + i] = static_cast<uint8_t>(v >> (8 * i));
}
// Independent minimal CCRAW001 writer for tests: 32x24, two frames, zero footer.
// cfa: 0=RGGB 1=GRBG 2=GBRG 3=BGGR (Camera2 numbering).
std::vector<uint8_t> build(uint16_t red, uint16_t green, uint16_t blue, bool finishHeader = true, int cfa = 0) {
    static const int channels[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    constexpr uint32_t w = 32, h = 24;
    const size_t payload = static_cast<size_t>(w) * h * 2;
    std::vector<uint8_t> file(64 + 2 * (96 + payload) + 32, 0);
    std::memcpy(file.data(), "CCRAW001", 8);
    put32(file, 8, 64);
    put32(file, 12, w);
    put32(file, 16, h);
    put32(file, 20, 16);
    put32(file, 24, 0);
    put32(file, 28, 1);
    put64(file, 32, 1000);
    if (finishHeader) put32(file, 60, ccrawCrc32(file.data(), 60));
    for (int f = 0; f < 2; ++f) {
        const size_t base = 64 + static_cast<size_t>(f) * (96 + payload);
        std::memcpy(file.data() + base, "FRM1", 4);
        put32(file, base + 4, 96);
        put64(file, base + 8, 1000 + static_cast<uint64_t>(f) * 33000000);
        put32(file, base + 24, static_cast<uint32_t>(payload));
        put32(file, base + 32, w);
        put32(file, base + 36, h);
        put32(file, base + 40, w * 2);
        put32(file, base + 52, 16);
        for (uint32_t y = 0; y < h; ++y) {
            for (uint32_t x = 0; x < w; ++x) {
                const int channel = channels[cfa][(y % 2) * 2 + (x % 2)];
                const uint16_t value = channel == 0 ? red : (channel == 1 ? green : blue);
                const size_t pos = base + 96 + (static_cast<size_t>(y) * w + x) * 2;
                file[pos] = static_cast<uint8_t>(value & 0xff);
                file[pos + 1] = static_cast<uint8_t>(value >> 8);
            }
        }
        put32(file, base + 72, ccrawCrc32(file.data() + base + 96, payload));
        put32(file, base + 76, ccrawCrc32(file.data() + base, 76));
    }
    std::memcpy(file.data() + 64 + 2 * (96 + payload), "END1", 4);
    put32(file, 64 + 2 * (96 + payload) + 4, 32);
    put64(file, 64 + 2 * (96 + payload) + 8, 2);
    put64(file, 64 + 2 * (96 + payload) + 16, 2 * payload);
    put32(file, 64 + 2 * (96 + payload) + 24, ccrawCrc32(file.data() + 64 + 2 * (96 + payload), 24));
    return file;
}

int expected(double linear) {
    const double g = linear <= 0.0031308 ? 12.92 * linear : 1.055 * std::pow(linear, 1.0 / 2.4) - 0.055;
    return static_cast<int>(std::lround(std::clamp(g, 0.0, 1.0) * 255.0));
}

int writeTemp(const std::vector<uint8_t>& bytes) {
    char path[] = "build/native-tests/ccraw-preview.XXXXXX";
    const int fd = mkstemp(path);
    assert(fd >= 0);
    assert(write(fd, bytes.data(), bytes.size()) == static_cast<ssize_t>(bytes.size()));
    unlink(path);
    return fd;
}
}  // namespace

int main() {
    CcrawCalibration raw;
    raw.cfa = 0;
    raw.neutral[0] = raw.neutral[1] = raw.neutral[2] = 1.0;
    raw.white = 1023;
    raw.black[0] = raw.black[1] = raw.black[2] = raw.black[3] = 0;
    raw.valid = true;
    {
        const auto file = build(200, 700, 120);
        const int fd = writeTemp(file);
        const auto preview = renderCcrawFrame(fd, 0, 16, 6, raw);
        assert(preview.image.ok);
        assert(preview.image.width == 16 && preview.image.height == 12);
        assert(preview.image.orientation == 6);
        assert(preview.image.argb.size() == 192);
        for (uint32_t pixel : preview.image.argb) {
            assert((pixel >> 24) == 0xff);
            const int r = (pixel >> 16) & 0xff, g = (pixel >> 8) & 0xff, b = pixel & 0xff;
            // Calibrated path: exact linear channel levels mapped through sRGB.
            assert(std::abs(r - expected(200.0 / 1023)) <= 1);
            assert(std::abs(g - expected(700.0 / 1023)) <= 1);
            assert(std::abs(b - expected(120.0 / 1023)) <= 1);
        }
        assert(std::strcmp(lastPreviewError(), "") == 0);
        const auto second = renderCcrawFrame(fd, 1, 8, 1, raw);
        assert(second.image.ok && second.frameIndex == 1);
        assert(!renderCcrawFrame(fd, 2, 8, 1, raw).image.ok);
        assert(!renderCcrawFrame(fd, -1, 8, 1, raw).image.ok);
        close(fd);
    }
    {
        // Red-dominant GRBG data must stay red when the CFA origin is honoured.
        auto grbg = raw;
        grbg.cfa = 1;
        const auto file = build(900, 300, 60, true, 1);
        const int fd = writeTemp(file);
        const auto preview = renderCcrawFrame(fd, 0, 16, 1, grbg);
        assert(preview.image.ok);
        size_t redder = 0;
        for (uint32_t pixel : preview.image.argb) {
            const int r = (pixel >> 16) & 0xff, g = (pixel >> 8) & 0xff, b = pixel & 0xff;
            if (r > g && g > b) ++redder;
        }
        assert(redder == preview.image.argb.size());
        const auto swapped = renderCcrawFrame(fd, 0, 16, 1, raw);  // deliberately wrong RGGB
        assert(swapped.image.ok);
        const uint32_t pixel = swapped.image.argb[0];
        assert(((pixel >> 8) & 0xff) > ((pixel >> 16) & 0xff));
        close(fd);
    }
    {
        auto badHeader = build(200, 700, 120, false);
        const int fd = writeTemp(badHeader);
        assert(!renderCcrawFrame(fd, 0, 16, 1, raw).image.ok);
        assert(std::strlen(lastPreviewError()) > 0);
        close(fd);
    }
    {
        auto badFrame = build(200, 700, 120);
        badFrame[64 + 76] ^= 0xff;  // frame-header CRC
        const int fd = writeTemp(badFrame);
        assert(!renderCcrawFrame(fd, 0, 16, 1, raw).image.ok);
        close(fd);
    }
    {
        auto truncated = build(200, 700, 120);
        truncated.resize(64 + 96 + 100);
        const int fd = writeTemp(truncated);
        assert(!renderCcrawFrame(fd, 0, 16, 1, raw).image.ok);
        close(fd);
    }
    assert(!renderCcrawFrame(-1, 0, 16, 1, raw).image.ok);
    return 0;
}
