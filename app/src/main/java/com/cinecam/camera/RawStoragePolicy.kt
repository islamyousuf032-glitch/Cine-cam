package com.cinecam.camera

/** Policy only: actual filesystem performance/support must be measured on device. */
internal object RawStoragePolicy {
    const val MIN_FREE_BYTES = 650L * 1024 * 1024
    const val SEQUENCE_MIN_FREE_BYTES = 1024L * 1024 * 1024
    fun minFreeBytes(sequence: Boolean) = if (sequence) SEQUENCE_MIN_FREE_BYTES else MIN_FREE_BYTES
    // Older Android retains its existing app-external export path, not inaccessible private files.
    fun useInternal(requested: Boolean, api: Int): Boolean = requested && api >= 29
    fun checkFreeSpace(captureBytes: Long, exportBytes: Long, sequence: Boolean = false) {
        val minimum = minFreeBytes(sequence)
        val mib = minimum / (1024 * 1024)
        check(captureBytes >= minimum) { "Capture storage-এ অন্তত $mib MiB ফাঁকা জায়গা প্রয়োজন" }
        check(exportBytes >= minimum) { "Download export storage-এ অন্তত $mib MiB ফাঁকা জায়গা প্রয়োজন" }
    }
}
