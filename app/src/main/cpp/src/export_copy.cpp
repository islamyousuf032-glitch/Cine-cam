#include "export_copy.h"
#include "pipeline_timing.h"
#include <array>
#include <algorithm>
#include <cerrno>
#include <fcntl.h>
#include <sys/sendfile.h>
#include <sys/stat.h>
#include <unistd.h>

namespace cinecam {
namespace {
// A long RAW session exports files above 1 GiB; the cap only bounds a single copy.
constexpr int64_t kMaxExportSourceBytes = 8LL * 1024 * 1024 * 1024;
}
ExportCopyResult copyExportFile(const char* source, int output, bool preferKernel, KernelCopy kernelCopy) {
    ExportCopyResult r;
    const int input = ::open(source, O_RDONLY | O_CLOEXEC);
    if (input < 0) { r.error = "Export source open failed"; return r; }
    struct Close { int fd; ~Close() { ::close(fd); } } close{input};
    struct stat src{}, dst{};
    if (::fstat(input, &src) || ::fstat(output, &dst) || !S_ISREG(src.st_mode) || !S_ISREG(dst.st_mode) ||
        src.st_size < 0 || src.st_size > kMaxExportSourceBytes || dst.st_size != 0 ||
        (src.st_dev == dst.st_dev && src.st_ino == dst.st_ino) || ::lseek(output, 0, SEEK_CUR) != 0) {
        r.error = "Export needs bounded source and empty regular destination"; return r;
    }
    const auto begin = pipelineNowNs();
    auto fail = [&](const char* error) { r.copyNs = pipelineNowNs() - begin; r.error = error; return r; };
    off_t offset = 0;
    while (preferKernel && offset < src.st_size) {
        const auto count = static_cast<size_t>(std::min<off_t>(src.st_size - offset, 1024 * 1024));
        const auto n = (kernelCopy ? kernelCopy : ::sendfile)(output, input, &offset, count);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EINVAL || errno == ENOSYS || errno == EOPNOTSUPP || errno == EXDEV)) break;
        if (n <= 0) return fail("Export kernel copy failed or source truncated");
        r.kernelBytes += static_cast<uint64_t>(n); r.bytes += static_cast<uint64_t>(n);
    }
    // Continue at the exact successful kernel offset, never duplicate a partial copy.
    std::array<uint8_t, 256 * 1024> buffer{};
    while (offset < src.st_size) {
        const auto count = static_cast<size_t>(std::min<off_t>(src.st_size - offset, buffer.size()));
        const auto n = ::pread(input, buffer.data(), count, offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return fail("Export fallback read failed or source truncated");
        size_t done = 0;
        while (done < static_cast<size_t>(n)) {
            const auto wrote = ::write(output, buffer.data() + done, static_cast<size_t>(n) - done);
            if (wrote < 0 && errno == EINTR) continue;
            if (wrote <= 0) return fail("Export fallback write failed");
            done += static_cast<size_t>(wrote); r.bytes += wrote; r.bufferedBytes += wrote;
        }
        offset += n;
    }
    if (::fstat(input, &src) || ::fstat(output, &dst) || static_cast<uint64_t>(src.st_size) != r.bytes ||
        static_cast<uint64_t>(dst.st_size) != r.bytes) return fail("Export source/destination size changed");
    r.copyNs = pipelineNowNs() - begin;
    const auto syncBegin = pipelineNowNs();
    int status;
    do { status = ::fsync(output); } while (status < 0 && errno == EINTR);
    r.fsyncNs = pipelineNowNs() - syncBegin;
    if (status < 0) { r.error = "Export destination fsync failed"; return r; }
    r.ok = true;
    return r;
}
}
