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
struct Bp16RestoreResult {
    bool ok = false;
    // Every decoded block is CRC-checked against the archive record, so ok means byte-exact.
    uint64_t sourceBytes = 0, restoredBytes = 0, archiveBytes = 0, blocks = 0;
    uint64_t readNs = 0, unpackNs = 0, crcNs = 0, writeNs = 0, fsyncNs = 0, totalNs = 0;
    const char* error = "";
};
// Post-capture archive only. Exclusive destination, source never replaced.
Bp16ArchiveResult createBp16Archive(const char* source, const char* destination);
bool verifyBp16Archive(const char* archive, const char* source);
// Streams a CCBP0001 archive back into a CCRAW stream on an already-open descriptor.
// Reads from the current position to EOF; never seeks, so a provider pipe works too.
Bp16RestoreResult restoreBp16Archive(int archiveFd, int destinationFd);
}
