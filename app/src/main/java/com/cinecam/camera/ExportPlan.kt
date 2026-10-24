package com.cinecam.camera

/**
 * Pure planning for one export run: how much space it needs and in which order to copy.
 *
 * A real device run exported 52 DNG files, then failed on the 1.29 GB `.ccraw` with a
 * generic kernel-copy error, leaving the user with no usable clip. The file was almost
 * certainly larger than the free space left, so the checks here run *before* any copy.
 */
internal object ExportPlan {
    /** Files at or above this size are copied alone: parallel workers only add contention. */
    const val LARGE_FILE_BYTES = 256L * 1024 * 1024

    /** Headroom for MediaStore bookkeeping; a volume filled to the last byte fails anyway. */
    const val MARGIN_DIVISOR = 50
    const val MIN_MARGIN_BYTES = 8L * 1024 * 1024

    fun requiredBytes(sizes: List<Long>): Long {
        val total = sizes.sumOf { it.coerceAtLeast(0L) }
        return total + maxOf(MIN_MARGIN_BYTES, total / MARGIN_DIVISOR)
    }

    fun fits(sizes: List<Long>, freeBytes: Long): Boolean = freeBytes >= requiredBytes(sizes)

    /** Largest first: the risky file fails fast instead of last, after everything else is written. */
    fun copyOrder(sizes: List<Long>): List<Int> =
        sizes.indices.sortedWith(compareByDescending<Int> { sizes[it] }.thenBy { it })

    fun isLarge(bytes: Long): Boolean = bytes >= LARGE_FILE_BYTES

    /** A human-readable reason for a failed native copy; `errno` 0 means the source ended early. */
    fun describeFailure(file: String, errorCode: Int, error: String): String {
        val reason = when (errorCode) {
            28 -> "ডিস্কে জায়গা নেই (ENOSPC)"
            122 -> "ডিস্ক কোটা শেষ (EDQUOT)"
            27 -> "ফাইল সিস্টেমের সীমা ছাড়িয়েছে (EFBIG)"
            75 -> "মান খুব বড় (EOVERFLOW)"
            5 -> "ডিভাইস I/O সমস্যা (EIO)"
            9 -> "ফাইল হ্যান্ডেল বন্ধ হয়ে গেছে (EBADF)"
            else -> if (error.isEmpty()) "অজানা কারণ" else error
        }
        return "Export copy ব্যর্থ: $file: $reason" + if (errorCode != 0) " • errno $errorCode" else ""
    }
}
