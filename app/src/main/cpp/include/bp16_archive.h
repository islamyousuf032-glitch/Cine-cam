#pragma once
#include <cstdint>
namespace cinecam {
struct Bp16ArchiveResult {
    bool ok = false;
    uint64_t sourceBytes = 0, archiveBytes = 0, encodeWriteNs = 0, verifyNs = 0;
    // Encode phase only; verifyNs remains separate. Syscall wall times, not CPU time.
    uint64_t sourceReadNs = 0, packNs = 0, crcNs = 0, writeNs = 0;
    uint64_t fsyncNs = 0, closeNs = 0, maxWriteNs = 0;
    uint64_t readCalls = 0, writeCalls = 0, blocks = 0;
    const char* error = "";
};
// Post-capture archive only. Exclusive destination, source never replaced.
Bp16ArchiveResult createBp16Archive(const char* source, const char* destination);
bool verifyBp16Archive(const char* archive, const char* source);
}
