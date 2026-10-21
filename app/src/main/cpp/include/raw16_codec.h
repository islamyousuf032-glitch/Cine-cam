#pragma once
#include <cstddef>
#include <cstdint>

namespace cinecam {
// Input/output buffers must not overlap.
// Experimental block codec, NOT a CCRAW format revision. All RAW16 values preserved.
constexpr size_t kRaw16BlockBytes = 65536;
constexpr size_t kRaw16BlockHeader = 8;
bool packRaw16Block(const uint8_t* input, size_t bytes, uint8_t* output, size_t capacity, size_t& written);
bool unpackRaw16Block(const uint8_t* input, size_t bytes, uint8_t* output, size_t capacity, size_t& written);
// Read-only first-saved-frame probe. Bounded scratch; never replaces/deletes the source file.
struct Raw16Probe {
    bool ok = false;
    uint64_t inputBytes = 0, packedBytes = 0, blocks = 0;
    uint64_t readNs = 0, packNs = 0, unpackNs = 0;
    const char* error = "";
};
Raw16Probe probeRaw16File(const char* path, int64_t offset, int64_t bytes);
}
