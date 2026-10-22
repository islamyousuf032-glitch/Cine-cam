#include "dng_preview.h"

#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>

namespace cinecam {
thread_local std::string g_previewError;
void setPreviewError(std::string message) { g_previewError = std::move(message); }
const char* lastPreviewError() { return g_previewError.c_str(); }
namespace {

constexpr size_t kMaxFileBytes = 96u << 20;
constexpr uint64_t kMaxPixels = 48ull << 20;
constexpr uint64_t kMaxOutputPixels = 6ull << 20;
constexpr size_t kMaxIfdEntries = 512;
constexpr int kMaxDimension = 4096;

constexpr uint16_t kTagNewSubfileType = 254;
constexpr uint16_t kTagImageWidth = 256;
constexpr uint16_t kTagImageLength = 257;
constexpr uint16_t kTagBitsPerSample = 258;
constexpr uint16_t kTagCompression = 259;
constexpr uint16_t kTagPhotometric = 262;
constexpr uint16_t kTagStripOffsets = 273;
constexpr uint16_t kTagOrientation = 274;
constexpr uint16_t kTagSamplesPerPixel = 277;
constexpr uint16_t kTagRowsPerStrip = 278;
constexpr uint16_t kTagStripByteCounts = 279;
constexpr uint16_t kTagPlanarConfig = 284;
constexpr uint16_t kTagTileWidth = 322;
constexpr uint16_t kTagTileLength = 323;
constexpr uint16_t kTagTileOffsets = 324;
constexpr uint16_t kTagTileByteCounts = 325;
constexpr uint16_t kTagSubIfds = 330;
constexpr uint16_t kTagCfaRepeatDim = 33421;
constexpr uint16_t kTagCfaPattern = 33422;
constexpr uint16_t kTagLinearization = 50712;
constexpr uint16_t kTagBlackLevel = 50714;
constexpr uint16_t kTagWhiteLevel = 50717;
constexpr uint16_t kTagCropOrigin = 50719;
constexpr uint16_t kTagCropSize = 50720;
constexpr uint16_t kTagColorMatrix1 = 50721;
constexpr uint16_t kTagAsShotNeutral = 50728;
constexpr uint16_t kTagActiveArea = 50829;

constexpr uint16_t kPhotometricCfa = 32803;
constexpr uint16_t kCompressionNone = 1;

struct Reader {
    const uint8_t* data = nullptr;
    size_t size = 0;
    bool little = true;

    bool has(size_t off, uint64_t len) const {
        return off <= size && len <= static_cast<uint64_t>(size) - off;
    }
    uint16_t u16(size_t off) const {
        return little ? static_cast<uint16_t>(data[off] | (data[off + 1] << 8))
                      : static_cast<uint16_t>((data[off] << 8) | data[off + 1]);
    }
    uint32_t u32(size_t off) const {
        if (little) {
            return static_cast<uint32_t>(data[off]) | (static_cast<uint32_t>(data[off + 1]) << 8) |
                   (static_cast<uint32_t>(data[off + 2]) << 16) | (static_cast<uint32_t>(data[off + 3]) << 24);
        }
        return (static_cast<uint32_t>(data[off]) << 24) | (static_cast<uint32_t>(data[off + 1]) << 16) |
               (static_cast<uint32_t>(data[off + 2]) << 8) | static_cast<uint32_t>(data[off + 3]);
    }
};

size_t typeSize(uint16_t type) {
    switch (type) {
        case 1: case 2: case 6: case 7: return 1;
        case 3: case 8: return 2;
        case 4: case 9: case 11: return 4;
        case 5: case 10: case 12: return 8;
        default: return 0;
    }
}

struct Entry {
    uint16_t tag = 0;
    uint16_t type = 0;
    uint32_t count = 0;
    size_t payload = 0;
};

struct Ifd {
    std::vector<Entry> entries;
    uint32_t next = 0;
    size_t order = 0;
    const Entry* find(uint16_t tag) const {
        for (const auto& e : entries) if (e.tag == tag) return &e;
        return nullptr;
    }
};

bool parseIfd(const Reader& r, uint32_t offset, Ifd& out) {
    if (!r.has(offset, 2)) return false;
    uint32_t count = r.u16(offset);
    if (count == 0 || count > kMaxIfdEntries) return false;
    const uint64_t tableBytes = 2ull + 12ull * count + 4ull;
    if (!r.has(offset, tableBytes)) return false;
    out.entries.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        const size_t pos = offset + 2 + static_cast<size_t>(i) * 12;
        Entry e{r.u16(pos), r.u16(pos + 2), r.u32(pos + 4), 0};
        const size_t unit = typeSize(e.type);
        if (unit == 0 || e.count == 0) continue;
        const uint64_t bytes = static_cast<uint64_t>(unit) * e.count;
        if (bytes > kMaxFileBytes) return false;
        e.payload = bytes <= 4 ? pos + 8 : r.u32(pos + 8);
        if (!r.has(e.payload, bytes)) return false;
        out.entries.push_back(e);
    }
    out.next = r.u32(offset + 2 + static_cast<size_t>(count) * 12);
    return true;
}

bool entryNumbers(const Reader& r, const Entry& e, std::vector<double>& out, size_t limit = 16) {
    if (e.count > limit) return false;
    out.clear();
    out.reserve(e.count);
    for (uint32_t i = 0; i < e.count; ++i) {
        const size_t pos = e.payload + static_cast<size_t>(i) * typeSize(e.type);
        switch (e.type) {
            case 1: out.push_back(r.data[pos]); break;
            case 3: out.push_back(r.u16(pos)); break;
            case 4: out.push_back(r.u32(pos)); break;
            case 6: out.push_back(static_cast<int8_t>(r.data[pos])); break;
            case 8: out.push_back(static_cast<int16_t>(r.u16(pos))); break;
            case 9: out.push_back(static_cast<int32_t>(r.u32(pos))); break;
            case 5:
            case 10: {
                const uint32_t num = r.u32(pos), den = r.u32(pos + 4);
                if (den == 0) return false;
                const bool negative = e.type == 10 && (num & 0x80000000u);
                const double n = negative ? -static_cast<double>(~num + 1u) : static_cast<double>(num);
                out.push_back(n / den);
                break;
            }
            default: return false;
        }
    }
    return true;
}

bool singleNumber(const Reader& r, const Ifd& ifd, uint16_t tag, double& value) {
    const Entry* e = ifd.find(tag);
    if (e == nullptr) return false;
    std::vector<double> values;
    if (!entryNumbers(r, *e, values, 1) || values.empty()) return false;
    value = values[0];
    return true;
}

bool numberList(const Reader& r, const Ifd& ifd, uint16_t tag, std::vector<double>& values, size_t limit) {
    const Entry* e = ifd.find(tag);
    if (e == nullptr) return false;
    return entryNumbers(r, *e, values, limit);
}

struct RawInfo {
    uint32_t width = 0;
    uint32_t height = 0;
    uint16_t bits = 0;
    uint16_t compression = 0;
    uint16_t photometric = 0;
    uint16_t samples = 0;
    uint16_t planar = 1;
    uint16_t orientation = 1;
    uint32_t rowsPerStrip = 0;
    std::vector<uint64_t> stripOffsets;
    std::vector<uint64_t> stripCounts;
    bool tiled = false;
    uint32_t tileWidth = 0, tileLength = 0;
    std::vector<uint64_t> tileOffsets, tileCounts;
    std::array<uint8_t, 4> cfa{{0, 1, 1, 2}};
    int blackCount = 0;
    std::array<double, 4> black{{0, 0, 0, 0}};
    int whiteCount = 0;
    std::array<double, 3> white{{65535, 65535, 65535}};
    bool neutral = false;
    std::array<double, 3> asShotNeutral{{1, 1, 1}};
    bool colorMatrix = false;
    std::array<double, 9> matrix{};
    bool crop = false;
    int64_t cropX = 0, cropY = 0, cropW = 0, cropH = 0;
    bool active = false;
    int64_t activeX = 0, activeY = 0, activeW = 0, activeH = 0;
};

bool readRawInfo(const Reader& r, const Ifd& ifd, RawInfo& info) {
    double v = 0;
    if (!singleNumber(r, ifd, kTagImageWidth, v) || v < 1 || v > 16384) return false;
    info.width = static_cast<uint32_t>(v);
    if (!singleNumber(r, ifd, kTagImageLength, v) || v < 1 || v > 16384) return false;
    info.height = static_cast<uint32_t>(v);
    if (static_cast<uint64_t>(info.width) * info.height > kMaxPixels) return false;
    if (!singleNumber(r, ifd, kTagBitsPerSample, v) || static_cast<int>(v) != 16) return false;
    info.bits = 16;
    if (!singleNumber(r, ifd, kTagCompression, v)) return false;
    info.compression = static_cast<uint16_t>(v);
    if (!singleNumber(r, ifd, kTagPhotometric, v)) return false;
    info.photometric = static_cast<uint16_t>(v);
    info.samples = singleNumber(r, ifd, kTagSamplesPerPixel, v) ? static_cast<uint16_t>(v) : 1;
    if (singleNumber(r, ifd, kTagPlanarConfig, v)) info.planar = static_cast<uint16_t>(v);
    if (singleNumber(r, ifd, kTagOrientation, v) && v >= 1 && v <= 8) info.orientation = static_cast<uint16_t>(v);
    if (singleNumber(r, ifd, kTagRowsPerStrip, v) && v > 0 && v <= info.height) info.rowsPerStrip = static_cast<uint32_t>(v);
    const Entry* offsets = ifd.find(kTagStripOffsets);
    const Entry* counts = ifd.find(kTagStripByteCounts);
    std::vector<double> offs, cnts;
    if (offsets != nullptr && counts != nullptr) {
        if (!entryNumbers(r, *offsets, offs, 512) || !entryNumbers(r, *counts, cnts, 512)) return false;
        if (offs.empty() || offs.size() != cnts.size()) return false;
        for (size_t i = 0; i < offs.size(); ++i) {
            if (offs[i] < 0 || cnts[i] < 0) return false;
            info.stripOffsets.push_back(static_cast<uint64_t>(offs[i]));
            info.stripCounts.push_back(static_cast<uint64_t>(cnts[i]));
        }
    } else {
        // DngCreator and other writers may use tiled RAW storage instead of strips.
        const Entry* tileOffsets = ifd.find(kTagTileOffsets);
        const Entry* tileCounts = ifd.find(kTagTileByteCounts);
        if (tileOffsets == nullptr || tileCounts == nullptr) return false;
        if (!singleNumber(r, ifd, kTagTileWidth, v) || v < 1 || v > 16384) return false;
        info.tileWidth = static_cast<uint32_t>(v);
        if (!singleNumber(r, ifd, kTagTileLength, v) || v < 1 || v > 16384) return false;
        info.tileLength = static_cast<uint32_t>(v);
        std::vector<double> toffs, tcnts;
        if (!entryNumbers(r, *tileOffsets, toffs, 512) || !entryNumbers(r, *tileCounts, tcnts, 512)) return false;
        const uint64_t tiles = ((static_cast<uint64_t>(info.width) + info.tileWidth - 1) / info.tileWidth) *
                               ((static_cast<uint64_t>(info.height) + info.tileLength - 1) / info.tileLength);
        if (toffs.size() != tiles || tcnts.size() != tiles) return false;
        for (size_t i = 0; i < toffs.size(); ++i) {
            if (toffs[i] < 0 || tcnts[i] < 0) return false;
            info.tileOffsets.push_back(static_cast<uint64_t>(toffs[i]));
            info.tileCounts.push_back(static_cast<uint64_t>(tcnts[i]));
        }
        info.tiled = true;
    }
    if (ifd.find(kTagLinearization) != nullptr) return false;
    if (numberList(r, ifd, kTagCfaRepeatDim, cnts, 2) && cnts.size() == 2 &&
        (static_cast<int>(cnts[0]) != 2 || static_cast<int>(cnts[1]) != 2)) return false;
    std::vector<double> pattern;
    if (numberList(r, ifd, kTagCfaPattern, pattern, 4)) {
        if (pattern.size() != 4) return false;
        for (int i = 0; i < 4; ++i) {
            if (pattern[i] < 0 || pattern[i] > 2) return false;
            info.cfa[i] = static_cast<uint8_t>(pattern[i]);
        }
    }
    if (numberList(r, ifd, kTagBlackLevel, pattern, 4) && (pattern.size() == 1 || pattern.size() == 4)) {
        info.blackCount = static_cast<int>(pattern.size());
        for (size_t i = 0; i < pattern.size(); ++i) info.black[i] = pattern[i];
    }
    if (numberList(r, ifd, kTagWhiteLevel, pattern, 3) && (pattern.size() == 1 || pattern.size() == 3)) {
        info.whiteCount = static_cast<int>(pattern.size());
        for (size_t i = 0; i < pattern.size(); ++i) info.white[i] = pattern[i];
    }
    if (numberList(r, ifd, kTagAsShotNeutral, pattern, 3) && pattern.size() == 3) {
        if (pattern[0] > 0 && pattern[1] > 0 && pattern[2] > 0) {
            for (int i = 0; i < 3; ++i) info.asShotNeutral[i] = pattern[i];
            info.neutral = true;
        }
    }
    if (numberList(r, ifd, kTagColorMatrix1, pattern, 9) && pattern.size() == 9) {
        for (int i = 0; i < 9; ++i) info.matrix[i] = pattern[i];
        info.colorMatrix = true;
    }
    std::vector<double> cropOrigin, cropSize;
    if (numberList(r, ifd, kTagCropOrigin, cropOrigin, 2) && cropOrigin.size() == 2 &&
        numberList(r, ifd, kTagCropSize, cropSize, 2) && cropSize.size() == 2) {
        info.crop = true;
        info.cropX = static_cast<int64_t>(std::llround(cropOrigin[0]));
        info.cropY = static_cast<int64_t>(std::llround(cropOrigin[1]));
        info.cropW = static_cast<int64_t>(std::llround(cropSize[0]));
        info.cropH = static_cast<int64_t>(std::llround(cropSize[1]));
    }
    std::vector<double> area;
    if (numberList(r, ifd, kTagActiveArea, area, 4) && area.size() == 4) {
        const double top = area[0], left = area[1], bottom = area[2], right = area[3];
        if (bottom > top && right > left) {
            info.active = true;
            info.activeX = static_cast<int64_t>(std::llround(left));
            info.activeY = static_cast<int64_t>(std::llround(top));
            info.activeW = static_cast<int64_t>(std::llround(right - left));
            info.activeH = static_cast<int64_t>(std::llround(bottom - top));
        }
    }
    return true;
}

bool invert3(const std::array<double, 9>& m, std::array<double, 9>& out) {
    const double a = m[0], b = m[1], c = m[2];
    const double d = m[3], e = m[4], f = m[5];
    const double g = m[6], h = m[7], i = m[8];
    const double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if (!std::isfinite(det) || std::fabs(det) < 1e-9) return false;
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

struct GammaLut {
    std::array<uint16_t, 4097> table{};
    GammaLut() {
        for (int i = 0; i <= 4096; ++i) {
            const double v = static_cast<double>(i) / 4096.0;
            const double g = v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055;
            table[i] = static_cast<uint16_t>(std::lround(std::clamp(g, 0.0, 1.0) * 255.0));
        }
    }
    uint8_t at(double v) const {
        if (!(v > 0)) return 0;
        if (v >= 1) return 255;
        const int index = static_cast<int>(v * 4096.0);
        return static_cast<uint8_t>(table[std::min(index, 4096)]);
    }
};

bool readAllFd(int fd, std::vector<uint8_t>& out, std::string& error) {
    struct stat st{};
    if (fstat(fd, &st) != 0) { error = "DNG descriptor পড়া যায়নি"; return false; }
    if (S_ISREG(st.st_mode) && st.st_size > 0) {
        if (static_cast<uint64_t>(st.st_size) > kMaxFileBytes) { error = "DNG ফাইল খুব বড়"; return false; }
        out.resize(static_cast<size_t>(st.st_size));
        size_t done = 0;
        while (done < out.size()) {
            const ssize_t n = pread(fd, out.data() + done, out.size() - done, static_cast<off_t>(done));
            if (n < 0) { error = "DNG পড়তে সমস্যা"; return false; }
            if (n == 0) { error = "DNG অসম্পূর্ণ"; return false; }
            done += static_cast<size_t>(n);
        }
        return true;
    }
    // Non-seekable provider streams: bounded read-all fallback.
    out.clear();
    std::array<uint8_t, 65536> buffer{};
    while (true) {
        const ssize_t n = read(fd, buffer.data(), buffer.size());
        if (n < 0) { error = "DNG পড়তে সমস্যা"; return false; }
        if (n == 0) break;
        if (out.size() + static_cast<size_t>(n) > kMaxFileBytes) { error = "DNG ফাইল খুব বড়"; return false; }
        out.insert(out.end(), buffer.begin(), buffer.begin() + n);
    }
    if (out.empty()) { error = "DNG ফাইল খালি"; return false; }
    return true;
}

DngPreview fail(std::string message) {
    DngPreview preview;
    preview.error = std::move(message);
    g_previewError = preview.error;
    return preview;
}

}  // namespace

DngPreview renderDngPreview(const uint8_t* data, size_t size, int maxDimension) {
    if (data == nullptr || size < 16) return fail("DNG header অসম্পূর্ণ");
    if (size > kMaxFileBytes) return fail("DNG ফাইল খুব বড়");
    Reader reader{data, size, true};
    if (data[0] == 'M' && data[1] == 'M') reader.little = false;
    else if (!(data[0] == 'I' && data[1] == 'I')) return fail("DNG byte order সঠিক নয়");
    reader.little = data[0] == 'I';
    if (reader.u16(2) != 42) return fail("DNG/TIFF magic সঠিক নয়");
    const uint32_t first = reader.u32(4);
    if (!reader.has(first, 2)) return fail("DNG IFD অসম্পূর্ণ");

    std::vector<Ifd> ifds;
    std::vector<uint32_t> pending{first};
    if (reader.u32(4) == 0) return fail("DNG IFD নেই");
    while (!pending.empty() && ifds.size() < 16) {
        const uint32_t offset = pending.back();
        pending.pop_back();
        if (offset == 0) continue;
        Ifd ifd;
        if (!parseIfd(reader, offset, ifd)) return fail("DNG IFD সঠিক নয়");
        ifd.order = ifds.size();
        if (ifd.next != 0) pending.push_back(ifd.next);
        if (ifd.find(kTagSubIfds) != nullptr) {
            std::vector<double> sub;
            if (entryNumbers(reader, *ifd.find(kTagSubIfds), sub, 8)) {
                for (double s : sub) if (s > 0) pending.push_back(static_cast<uint32_t>(s));
            }
        }
        ifds.push_back(std::move(ifd));
    }
    if (ifds.empty()) return fail("DNG IFD পাওয়া যায়নি");

    int best = -1;
    double bestArea = -1;
    for (size_t i = 0; i < ifds.size(); ++i) {
        double photometric = 0, compression = 0, width = 0, height = 0;
        if (!singleNumber(reader, ifds[i], kTagPhotometric, photometric) ||
            !singleNumber(reader, ifds[i], kTagCompression, compression) ||
            !singleNumber(reader, ifds[i], kTagImageWidth, width) ||
            !singleNumber(reader, ifds[i], kTagImageLength, height)) continue;
        if (static_cast<int>(photometric) != kPhotometricCfa) continue;
        if (static_cast<int>(compression) != kCompressionNone) continue;
        if (width * height > bestArea) { bestArea = width * height; best = static_cast<int>(i); }
    }
    if (best < 0) return fail("DNG-তে আনকম্প্রেসড 16-bit RAW ছবি পাওয়া যায়নি");
    RawInfo info;
    if (!readRawInfo(reader, ifds[best], info)) return fail("DNG RAW tag সীমার বাইরে বা অসমর্থিত");
    if (info.photometric != kPhotometricCfa || info.compression != kCompressionNone ||
        info.samples != 1 || info.planar != 1 || info.bits != 16) {
        return fail("DNG RAW layout অসমর্থিত");
    }
    double orientationValue = 0;
    if (singleNumber(reader, ifds[0], kTagOrientation, orientationValue) &&
        orientationValue >= 1 && orientationValue <= 8) {
        info.orientation = static_cast<uint16_t>(orientationValue);
    }

    const size_t rowBytes = static_cast<size_t>(info.width) * 2;
    const uint64_t needed = static_cast<uint64_t>(rowBytes) * info.height;
    uint64_t declared = 0;
    for (uint64_t c : info.tiled ? info.tileCounts : info.stripCounts) declared += c;
    if (declared < needed) return fail("DNG RAW data অসম্পূর্ণ");
    std::vector<uint16_t> raw(static_cast<size_t>(info.width) * info.height);
    size_t row = 0;
    if (!info.tiled) for (size_t i = 0; i < info.stripOffsets.size() && row < info.height; ++i) {
        const uint64_t offset = info.stripOffsets[i];
        const uint64_t count = info.stripCounts[i];
        if (!reader.has(static_cast<size_t>(offset), count)) return fail("DNG RAW data ফাইলের বাইরে");
        const uint32_t rowsPerStrip = info.rowsPerStrip ? info.rowsPerStrip : info.height;
        const size_t rows = std::min<uint64_t>(rowsPerStrip, info.height - row);
        const uint64_t bytes = static_cast<uint64_t>(rows) * rowBytes;
        if (count < bytes) return fail("DNG strip দৈর্ঘ্য মেলে না");
        for (size_t y = 0; y < rows; ++y) {
            const uint8_t* src = reader.data + offset + y * rowBytes;
            uint16_t* dst = raw.data() + (row + y) * info.width;
            if (reader.little) {
                for (size_t x = 0; x < info.width; ++x) {
                    dst[x] = static_cast<uint16_t>(src[x * 2] | (src[x * 2 + 1] << 8));
                }
            } else {
                for (size_t x = 0; x < info.width; ++x) {
                    dst[x] = static_cast<uint16_t>((src[x * 2] << 8) | src[x * 2 + 1]);
                }
            }
        }
        row += rows;
    }
    if (info.tiled) {
        const uint32_t across = (info.width + info.tileWidth - 1) / info.tileWidth;
        const uint32_t down = (info.height + info.tileLength - 1) / info.tileLength;
        for (uint32_t ty = 0; ty < down; ++ty) {
            for (uint32_t tx = 0; tx < across; ++tx) {
                const size_t index = static_cast<size_t>(ty) * across + tx;
                const uint64_t offset = info.tileOffsets[index];
                const uint64_t count = info.tileCounts[index];
                const uint32_t tw = std::min(info.tileWidth, info.width - tx * info.tileWidth);
                const uint32_t th = std::min(info.tileLength, info.height - ty * info.tileLength);
                const uint64_t bytes = static_cast<uint64_t>(tw) * th * 2;
                if (!reader.has(static_cast<size_t>(offset), count) || count < bytes) return fail("DNG tile data অসম্পূর্ণ");
                for (uint32_t y = 0; y < th; ++y) {
                    const uint8_t* src = reader.data + offset + static_cast<uint64_t>(y) * tw * 2;
                    uint16_t* dst = raw.data() + static_cast<size_t>(ty * info.tileLength + y) * info.width + tx * info.tileWidth;
                    for (uint32_t x = 0; x < tw; ++x) {
                        dst[x] = reader.little ? static_cast<uint16_t>(src[x * 2] | (src[x * 2 + 1] << 8))
                                               : static_cast<uint16_t>((src[x * 2] << 8) | src[x * 2 + 1]);
                    }
                }
            }
        }
    }
    if (row < info.height && !info.tiled) return fail("DNG RAW data অসম্পূর্ণ");

    int64_t cropX = 0, cropY = 0, cropW = info.width, cropH = info.height;
    if (info.crop && info.cropW > 1 && info.cropH > 1) {
        cropX = info.cropX; cropY = info.cropY; cropW = info.cropW; cropH = info.cropH;
    } else if (info.active) {
        cropX = info.activeX; cropY = info.activeY; cropW = info.activeW; cropH = info.activeH;
    }
    cropX = std::clamp<int64_t>(cropX, 0, static_cast<int64_t>(info.width) - 2);
    cropY = std::clamp<int64_t>(cropY, 0, static_cast<int64_t>(info.height) - 2);
    cropW = std::min<int64_t>(cropW, static_cast<int64_t>(info.width) - cropX);
    cropH = std::min<int64_t>(cropH, static_cast<int64_t>(info.height) - cropY);
    if (cropW < 4 || cropH < 4) return fail("DNG crop সীমা সঠিক নয়");

    const int64_t cellX0 = cropX / 2;
    const int64_t cellY0 = cropY / 2;
    const int64_t cellX1 = (cropX + cropW) / 2;
    const int64_t cellY1 = (cropY + cropH) / 2;
    const int64_t cellsW = cellX1 - cellX0;
    const int64_t cellsH = cellY1 - cellY0;
    if (cellsW < 1 || cellsH < 1) return fail("DNG preview region খালি");

    const int requested = std::clamp(maxDimension, 16, kMaxDimension);
    int step = 1;
    {
        const int64_t longest = std::max(cellsW, cellsH);
        step = static_cast<int>((longest + requested - 1) / requested);
        if (step < 1) step = 1;
    }
    const int outW = static_cast<int>((cellsW + step - 1) / step);
    const int outH = static_cast<int>((cellsH + step - 1) / step);
    if (outW < 1 || outH < 1 || static_cast<uint64_t>(outW) * outH > kMaxOutputPixels) {
        return fail("DNG preview আকার সীমার বাইরে");
    }

    std::array<double, 4> black = {{0, 0, 0, 0}};
    for (int i = 0; i < 4; ++i) black[i] = info.blackCount > 0 ? info.black[i % std::max(info.blackCount, 1)] : 0.0;
    if (info.blackCount == 1) black = {{info.black[0], info.black[0], info.black[0], info.black[0]}};
    std::array<double, 3> white{};
    for (int i = 0; i < 3; ++i) white[i] = info.whiteCount == 3 ? info.white[i] : info.white[0];
    std::array<double, 3> gain{{1, 1, 1}};
    if (info.neutral) {
        // AsShotNeutral is the camera-space value of a neutral object; divide each
        // channel by it (green normalised to 1). The previous reciprocal made
        // neutral scenes green (WB gains inverted).
        gain = {{info.asShotNeutral[1] / info.asShotNeutral[0], 1.0,
                 info.asShotNeutral[1] / info.asShotNeutral[2]}};
    }
    std::array<double, 9> transform{{1, 0, 0, 0, 1, 0, 0, 0, 1}};
    if (info.colorMatrix) {
        std::array<double, 9> inverse{};
        const std::array<double, 9> xyzToSrgb{{3.2406, -1.5372, -0.4986,
                                               -0.9689, 1.8758, 0.0415,
                                               0.0557, -0.2040, 1.0570}};
        if (invert3(info.matrix, inverse)) multiply3(xyzToSrgb, inverse, transform);
    }
    const GammaLut lut;
    std::vector<uint32_t> pixels(static_cast<size_t>(outW) * outH);
    for (int oy = 0; oy < outH; ++oy) {
        const int64_t cy0 = cellY0 + static_cast<int64_t>(oy) * step;
        const int64_t cyEnd = std::min<int64_t>(cy0 + step, cellY1);
        for (int ox = 0; ox < outW; ++ox) {
            const int64_t cx0 = cellX0 + static_cast<int64_t>(ox) * step;
            const int64_t cxEnd = std::min<int64_t>(cx0 + step, cellX1);
            double sum[3] = {0, 0, 0};
            uint32_t counts[3] = {0, 0, 0};
            for (int64_t cy = cy0; cy < cyEnd; ++cy) {
                const uint16_t* row0 = raw.data() + (cy * 2) * info.width;
                const uint16_t* row1 = row0 + info.width;
                for (int64_t cx = cx0; cx < cxEnd; ++cx) {
                    const uint16_t samples[4] = {row0[cx * 2], row0[cx * 2 + 1], row1[cx * 2], row1[cx * 2 + 1]};
                    for (int j = 0; j < 4; ++j) {
                        const int channel = info.cfa[j];
                        const double range = white[channel] - black[j];
                        if (!(range > 0)) continue;
                        double value = (samples[j] - black[j]) / range;
                        value = std::clamp(value, 0.0, 1.0);
                        sum[channel] += value;
                        ++counts[channel];
                    }
                }
            }
            double rgb[3] = {0, 0, 0};
            for (int c = 0; c < 3; ++c) {
                if (counts[c] == 0) continue;
                rgb[c] = (sum[c] / counts[c]) * gain[c];
            }
            const double r = transform[0] * rgb[0] + transform[1] * rgb[1] + transform[2] * rgb[2];
            const double g = transform[3] * rgb[0] + transform[4] * rgb[1] + transform[5] * rgb[2];
            const double b = transform[6] * rgb[0] + transform[7] * rgb[1] + transform[8] * rgb[2];
            const uint32_t alpha = 0xffu << 24;
            pixels[static_cast<size_t>(oy) * outW + ox] =
                alpha | (static_cast<uint32_t>(lut.at(r)) << 16) |
                (static_cast<uint32_t>(lut.at(g)) << 8) | static_cast<uint32_t>(lut.at(b));
        }
    }
    DngPreview preview;
    g_previewError.clear();
    preview.ok = true;
    preview.width = outW;
    preview.height = outH;
    preview.orientation = info.orientation;
    preview.argb = std::move(pixels);
    return preview;
}

DngPreview renderDngPreview(int fd, int maxDimension) {
    if (fd < 0) return fail("DNG descriptor নেই");
    struct stat st{};
    if (fstat(fd, &st) != 0) return fail("DNG descriptor পড়া যায়নি");
    if (S_ISREG(st.st_mode) && st.st_size > 0) {
        if (static_cast<uint64_t>(st.st_size) > kMaxFileBytes) return fail("DNG ফাইল খুব বড়");
        const size_t bytes = static_cast<size_t>(st.st_size);
        void* mapped = mmap(nullptr, bytes, PROT_READ, MAP_PRIVATE, fd, 0);
        if (mapped != MAP_FAILED) {
            auto preview = renderDngPreview(static_cast<const uint8_t*>(mapped), bytes, maxDimension);
            munmap(mapped, bytes);
            return preview;
        }
    }
    std::vector<uint8_t> owned;
    std::string error;
    if (!readAllFd(fd, owned, error)) return fail(error);
    return renderDngPreview(owned.data(), owned.size(), maxDimension);
}

}  // namespace cinecam
