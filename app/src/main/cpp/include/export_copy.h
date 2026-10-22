#pragma once
#include <cstdint>
#include <cstddef>
#include <sys/types.h>
namespace cinecam {
struct ExportCopyResult {
    bool ok = false;
    uint64_t bytes = 0, kernelBytes = 0, bufferedBytes = 0, copyNs = 0, fsyncNs = 0;
    const char* error = "";
};
// Borrowed empty regular output FD, owned/closed by caller. Source is read-only.
using KernelCopy = ssize_t (*)(int, int, off_t*, size_t);
// Optional syscall seam supports deterministic short-copy/fallback/error tests.
ExportCopyResult copyExportFile(const char* source, int outputFd, bool preferKernel = true, KernelCopy kernelCopy = nullptr);
}
