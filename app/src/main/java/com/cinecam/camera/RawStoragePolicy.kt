package com.cinecam.camera

/** Policy only: actual filesystem performance/support must be measured on device. */
internal object RawStoragePolicy {
    const val MIN_FREE_BYTES = 650L * 1024 * 1024
    // Older Android retains its existing app-external export path, not inaccessible private files.
    fun useInternal(requested: Boolean, api: Int): Boolean = requested && api >= 29
    fun checkFreeSpace(captureBytes: Long, exportBytes: Long) {
        check(captureBytes >= MIN_FREE_BYTES) { "Capture storage-এ অন্তত 650 MiB ফাঁকা জায়গা প্রয়োজন" }
        check(exportBytes >= MIN_FREE_BYTES) { "Download export storage-এ অন্তত 650 MiB ফাঁকা জায়গা প্রয়োজন" }
    }
}
