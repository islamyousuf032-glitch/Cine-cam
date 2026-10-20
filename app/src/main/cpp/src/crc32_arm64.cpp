#include <arm_acle.h>
#include <cstdint>
#include <cstddef>
#include <cstring>

static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "ARM64 CRC requires LE loads");
namespace cinecam {
// IEEE CRC32, NOT the CRC32C/Castagnoli intrinsic family. No extra payload buffer.
// Compile separately with +crc and without LTO: unsupported CPUs must never enter here.
__attribute__((noinline)) uint32_t ccrawCrc32Arm64(const uint8_t* p, size_t size) {
    uint32_t crc = 0xffffffffU;
    while (size >= 8) {
        uint64_t word;
        std::memcpy(&word, p, sizeof(word)); // alias/alignment safe, no read past the tail
        crc = __crc32d(crc, word);
        p += 8; size -= 8;
    }
    while (size--) crc = __crc32b(crc, *p++);
    return crc ^ 0xffffffffU;
}
}
