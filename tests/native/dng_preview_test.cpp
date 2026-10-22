#include "dng_preview.h"

#include <fcntl.h>
#include <unistd.h>

#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace cinecam;

namespace {

struct Writer {
    std::vector<uint8_t> bytes;
    bool little;
    void u16(size_t off, uint16_t v) {
        if (little) { bytes[off] = v & 0xff; bytes[off + 1] = v >> 8; }
        else { bytes[off] = v >> 8; bytes[off + 1] = v & 0xff; }
    }
    void u32(size_t off, uint32_t v) {
        if (little) {
            for (int i = 0; i < 4; ++i) bytes[off + i] = (v >> (8 * i)) & 0xff;
        } else {
            for (int i = 0; i < 4; ++i) bytes[off + i] = (v >> (8 * (3 - i))) & 0xff;
        }
    }
    // TIFF inline values are left-justified, so SHORT and LONG differ in BE files.
    void entryTag(size_t pos, uint16_t tag, uint16_t type, uint32_t count, uint32_t value) {
        u16(pos, tag);
        u16(pos + 2, type);
        u32(pos + 4, count);
        if (type == 3 && count == 1) u16(pos + 8, static_cast<uint16_t>(value));
        else u32(pos + 8, value);
    }
};

// Deliberately independent, minimal uncompressed 16-bit CFA DNG fixture.
std::vector<uint8_t> build(bool little, uint32_t width, uint32_t height, uint16_t red, uint16_t green,
                           uint16_t blue, uint16_t black, uint16_t white, const uint8_t pattern[4],
                           uint16_t compression = 1, uint16_t photometric = 32803,
                           uint32_t widthOverride = 0, bool truncate = false) {
    constexpr size_t kEntries = 15;
    const size_t cfaOff = 8 + 2 + 12 * kEntries + 4 + 24 + 8;
    Writer w;
    w.little = little;
    w.bytes.assign(cfaOff + static_cast<size_t>(width) * height * 2, 0);
    w.bytes[0] = little ? 'I' : 'M';
    w.bytes[1] = little ? 'I' : 'M';
    w.u16(2, 42);
    w.u32(4, 8);
    w.u16(8, kEntries);
    size_t p = 10;
    const uint32_t widthField = widthOverride ? widthOverride : width;
    w.entryTag(p, 256, 4, 1, widthField); p += 12;
    w.entryTag(p, 257, 4, 1, height); p += 12;
    w.entryTag(p, 258, 3, 1, 16); p += 12;
    w.entryTag(p, 259, 3, 1, compression); p += 12;
    w.entryTag(p, 262, 3, 1, photometric); p += 12;
    w.entryTag(p, 273, 4, 1, static_cast<uint32_t>(cfaOff)); p += 12;
    w.entryTag(p, 274, 3, 1, 6); p += 12;
    w.entryTag(p, 277, 3, 1, 1); p += 12;
    w.entryTag(p, 278, 4, 1, height); p += 12;
    w.entryTag(p, 279, 4, 1, width * height * 2); p += 12;
    w.u16(p, 33421); w.u16(p + 2, 3); w.u32(p + 4, 2); w.u16(p + 8, 2); w.u16(p + 10, 2); p += 12;
    w.u16(p, 33422); w.u16(p + 2, 1); w.u32(p + 4, 4);
    for (int i = 0; i < 4; ++i) w.bytes[p + 8 + i] = pattern[i];
    p += 12;
    const size_t neutralOff = 8 + 2 + 12 * kEntries + 4;
    const size_t blackOff = neutralOff + 24;
    w.entryTag(p, 50714, 3, 4, static_cast<uint32_t>(blackOff)); p += 12;
    w.entryTag(p, 50717, 3, 1, white); p += 12;
    w.entryTag(p, 50728, 5, 3, static_cast<uint32_t>(neutralOff)); p += 12;
    w.u32(p, 0);
    for (int i = 0; i < 3; ++i) {
        w.u32(neutralOff + i * 8, 1);
        w.u32(neutralOff + i * 8 + 4, 1);
    }
    for (int i = 0; i < 4; ++i) w.u16(blackOff + i * 2, black);
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            const uint8_t channel = pattern[(y % 2) * 2 + (x % 2)];
            const uint16_t value = channel == 0 ? red : (channel == 1 ? green : blue);
            w.u16(cfaOff + (static_cast<size_t>(y) * width + x) * 2, value);
        }
    }
    if (truncate) w.bytes.resize(w.bytes.size() - 97);
    return w.bytes;
}

// Minimal tiled 16x16 CFA DNG with 8x8 tiles; exercises DngCreator-style storage.
std::vector<uint8_t> buildTiled() {
    constexpr size_t kEntries = 11;
    const size_t dataOff = 8 + 2 + 12 * kEntries + 4;
    const size_t tileBytes = 8 * 8 * 2;
    Writer w;
    w.little = true;
    w.bytes.assign(dataOff + 4 * tileBytes, 0);
    w.bytes[0] = 'I';
    w.bytes[1] = 'I';
    w.u16(2, 42);
    w.u32(4, 8);
    w.u16(8, kEntries);
    size_t p = 10;
    w.entryTag(p, 256, 4, 1, 16); p += 12;
    w.entryTag(p, 257, 4, 1, 16); p += 12;
    w.entryTag(p, 258, 3, 1, 16); p += 12;
    w.entryTag(p, 259, 3, 1, 1); p += 12;
    w.entryTag(p, 262, 3, 1, 32803); p += 12;
    w.entryTag(p, 274, 3, 1, 1); p += 12;
    w.entryTag(p, 322, 4, 1, 8); p += 12;
    w.entryTag(p, 323, 4, 1, 8); p += 12;
    // Tile offsets/counts are stored inline arrays elsewhere only if <=4 bytes; use one
    // LONG per tile by pointing at a small table after the data block.
    const size_t table = dataOff + 4 * tileBytes;
    w.entryTag(p, 324, 4, 4, static_cast<uint32_t>(table)); p += 12;
    w.entryTag(p, 325, 4, 4, static_cast<uint32_t>(table + 16)); p += 12;
    w.entryTag(p, 33422, 1, 4, 0x02010100); p += 12;
    w.u32(p, 0);
    w.bytes.resize(table + 32);
    for (int tile = 0; tile < 4; ++tile) {
        w.u32(table + tile * 4, static_cast<uint32_t>(dataOff + tile * tileBytes));
        w.u32(table + 16 + tile * 4, static_cast<uint32_t>(tileBytes));
        for (int y = 0; y < 8; ++y) {
            for (int x = 0; x < 8; ++x) {
                const int gx = (tile % 2) * 8 + x, gy = (tile / 2) * 8 + y;
                const int channel = (gy % 2 == 0 && gx % 2 == 0) ? 0 : ((gy % 2 == 1 && gx % 2 == 1) ? 2 : 1);
                const uint16_t value = channel == 0 ? 900 : (channel == 1 ? 400 : 100);
                w.u16(dataOff + tile * tileBytes + (y * 8 + x) * 2, value);
            }
        }
    }
    return w.bytes;
}

uint8_t encode(double v) {
    const double g = v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055;
    return static_cast<uint8_t>(std::lround(std::min(1.0, std::max(0.0, g)) * 255.0));
}

void expectPixel(const DngPreview& p, size_t index, double lr, double lg, double lb, int tolerance = 2) {
    const uint32_t pixel = p.argb[index];
    const int r = (pixel >> 16) & 0xff, g = (pixel >> 8) & 0xff, b = pixel & 0xff;
    assert((pixel >> 24) == 0xff);
    assert(std::abs(r - encode(lr)) <= tolerance);
    assert(std::abs(g - encode(lg)) <= tolerance);
    assert(std::abs(b - encode(lb)) <= tolerance);
}

}  // namespace

int main() {
    const uint8_t rggb[4] = {0, 1, 1, 2};
    const double linR = (8000.0 - 1024.0) / (65535.0 - 1024.0);
    const double linG = (16000.0 - 1024.0) / (65535.0 - 1024.0);
    const double linB = (4000.0 - 1024.0) / (65535.0 - 1024.0);

    for (const bool little : {true, false}) {
        const auto file = build(little, 16, 16, 8000, 16000, 4000, 1024, 65535, rggb);
        const auto preview = renderDngPreview(file.data(), file.size(), 8);
        assert(preview.ok);
        assert(preview.width == 8 && preview.height == 8);
        assert(preview.orientation == 6);
        assert(preview.argb.size() == 64);
        for (size_t i = 0; i < preview.argb.size(); ++i) expectPixel(preview, i, linR, linG, linB);
    }

    {
        // Both CFA green sites and the 2x2 cell average must stay channel-correct.
        const uint8_t grbg[4] = {1, 0, 2, 1};
        const auto file = build(true, 16, 16, 8000, 16000, 4000, 0, 65535, grbg);
        const auto preview = renderDngPreview(file.data(), file.size(), 16);
        assert(preview.ok && preview.width == 8 && preview.height == 8);
        for (size_t i = 0; i < preview.argb.size(); ++i) {
            const int r = (preview.argb[i] >> 16) & 0xff, g = (preview.argb[i] >> 8) & 0xff, b = preview.argb[i] & 0xff;
            assert(g > r && r > b);
        }
    }

    {
        const auto tiled = buildTiled();
        const auto preview = renderDngPreview(tiled.data(), tiled.size(), 16);
        assert(preview.ok);
        assert(preview.width == 8 && preview.height == 8);
        for (uint32_t pixel : preview.argb) {
            const int r = (pixel >> 16) & 0xff, g = (pixel >> 8) & 0xff, b = pixel & 0xff;
            assert(r > g && g > b);
        }
    }
    {
        char path[] = "build/native-tests/preview-fixture.XXXXXX";
        const int fd = mkstemp(path);
        assert(fd >= 0);
        const auto file = build(true, 32, 24, 2000, 4000, 1000, 64, 65535, rggb);
        assert(write(fd, file.data(), file.size()) == static_cast<ssize_t>(file.size()));
        const auto preview = renderDngPreview(fd, 64);
        assert(preview.ok);
        assert(preview.width == 16 && preview.height == 12);
        assert(preview.orientation == 6);
        close(fd);
        unlink(path);
    }

    {
        const auto jpeg = build(true, 16, 16, 8000, 16000, 4000, 1024, 65535, rggb, 7);
        assert(!renderDngPreview(jpeg.data(), jpeg.size(), 32).ok);
        const auto rgb = build(true, 16, 16, 8000, 16000, 4000, 1024, 65535, rggb, 1, 2);
        assert(!renderDngPreview(rgb.data(), rgb.size(), 32).ok);
        const uint8_t badPattern[4] = {0, 1, 2, 3};
        const auto bad = build(true, 16, 16, 8000, 16000, 4000, 1024, 65535, badPattern);
        assert(!renderDngPreview(bad.data(), bad.size(), 32).ok);
        const auto truncated = build(true, 16, 16, 8000, 16000, 4000, 1024, 65535, rggb, 1, 32803, 0, true);
        assert(!renderDngPreview(truncated.data(), truncated.size(), 32).ok);
        const auto huge = build(true, 16, 16, 8000, 16000, 4000, 1024, 65535, rggb, 1, 32803, 60000);
        assert(!renderDngPreview(huge.data(), huge.size(), 32).ok);
        const auto file = build(true, 16, 16, 8000, 16000, 4000, 1024, 65535, rggb);
        assert(!renderDngPreview(file.data(), 8, 32).ok);
        assert(!renderDngPreview(nullptr, 0, 32).ok);
        assert(!renderDngPreview(-1, 32).ok);
    }
        return 0;
}
