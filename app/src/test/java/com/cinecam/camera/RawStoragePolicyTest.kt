package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class RawStoragePolicyTest {
    @Test fun privateStagingOnlyWhereDownloadExportExists() {
        assertFalse(RawStoragePolicy.useInternal(true, 26))
        assertFalse(RawStoragePolicy.useInternal(true, 28))
        assertTrue(RawStoragePolicy.useInternal(true, 29))
        assertTrue(RawStoragePolicy.useInternal(true, 34))
        assertFalse(RawStoragePolicy.useInternal(false, 34))
    }
    @Test fun bothCaptureAndExportVolumesNeedSpace() {
        val min = RawStoragePolicy.MIN_FREE_BYTES
        RawStoragePolicy.checkFreeSpace(min, min)
        RawStoragePolicy.checkFreeSpace(Long.MAX_VALUE, min)
        for ((capture, export) in listOf(min - 1 to min, min to min - 1, -1L to min)) {
            try { RawStoragePolicy.checkFreeSpace(capture, export); fail("low free space must reject") }
            catch (_: IllegalStateException) { }
        }
    }
}
