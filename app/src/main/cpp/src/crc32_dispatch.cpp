#include "ccraw_writer.h"
#include <array>
#if defined(CINECAM_ARM64_CRC) && defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#define CINECAM_CRC_HWCAP 1
#endif

namespace cinecam {
#ifdef CINECAM_CRC_HWCAP
uint32_t ccrawCrc32Arm64(const uint8_t*, size_t);
#endif
namespace {
using CrcFn = uint32_t (*)(const uint8_t*, size_t);
struct Backend { CrcFn fn; const char* name; const char* hardwareStatus; };
const Backend& backend() {
    static const Backend selected = []() -> Backend {
#ifdef CINECAM_FORCE_PORTABLE_CRC
        return {ccrawCrc32Portable, "slicing-by-8-forced", "forced-off"};
#elif defined(CINECAM_CRC_HWCAP)
        if ((getauxval(AT_HWCAP) & HWCAP_CRC32) != 0) {
            const uint8_t golden[] = {'1','2','3','4','5','6','7','8','9'};
            bool ok = ccrawCrc32Arm64(nullptr, 0) == 0 && ccrawCrc32Arm64(golden, 9) == 0xcbf43926U;
            std::array<uint8_t, 272> data{};
            uint32_t seed = 31;
            for (auto& byte : data) { seed = seed * 1664525U + 1013904223U; byte = seed >> 24; }
            for (size_t offset = 0; ok && offset < 16; ++offset)
                for (size_t size = 0; ok && size <= 256; ++size)
                    ok = ccrawCrc32Arm64(data.data() + offset, size) == ccrawCrc32Portable(data.data() + offset, size);
#ifdef CINECAM_CRC_TEST_FAIL_SELFTEST
            ok = false; // Test-only fault injection; never defined by Android CMake.
#endif
            if (ok) return {ccrawCrc32Arm64, "arm64-crc32", "passed"};
            return {ccrawCrc32Portable, "slicing-by-8-self-test-fallback", "failed"};
        }
#endif
        return {ccrawCrc32Portable, "slicing-by-8", "unavailable"};
    }();
    return selected;
}
}
uint32_t ccrawCrc32(const uint8_t* bytes, size_t size) { return backend().fn(bytes, size); }
const char* ccrawCrcBackend() { return backend().name; }
const char* ccrawHardwareCrcStatus() { return backend().hardwareStatus; }
}
