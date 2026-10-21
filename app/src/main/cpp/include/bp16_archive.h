#pragma once
#include <cstdint>
namespace cinecam {
struct Bp16ArchiveResult {
    bool ok = false;
    uint64_t sourceBytes = 0, archiveBytes = 0, encodeWriteNs = 0, verifyNs = 0;
    const char* error = "";
};
// Post-capture archive only. Exclusive destination, source never replaced.
Bp16ArchiveResult createBp16Archive(const char* source, const char* destination);
bool verifyBp16Archive(const char* archive, const char* source);
}
