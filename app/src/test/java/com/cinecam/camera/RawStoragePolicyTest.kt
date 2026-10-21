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
    @Test fun archiveHasExtraDuplicateSpaceGuard() {
        val min = RawStoragePolicy.minFreeBytes(true, true)
        assertEquals(1536L * 1024 * 1024, min)
        RawStoragePolicy.checkFreeSpace(min, min, true, true)
        try { RawStoragePolicy.checkFreeSpace(min - 1, min, true, true); fail("archive guard") }
        catch (_: IllegalStateException) { }
    }
    @Test fun sequenceReservesSpaceForSourceDngAndExportDuplicate() {
        val min = RawStoragePolicy.SEQUENCE_MIN_FREE_BYTES
        assertEquals(1024L * 1024 * 1024, RawStoragePolicy.minFreeBytes(true))
        assertEquals(RawStoragePolicy.MIN_FREE_BYTES, RawStoragePolicy.minFreeBytes(false))
        RawStoragePolicy.checkFreeSpace(min, min, true)
        try { RawStoragePolicy.checkFreeSpace(min - 1, min, true); fail("sequence source guard") }
        catch (_: IllegalStateException) { }
        try { RawStoragePolicy.checkFreeSpace(min, min - 1, true); fail("sequence export guard") }
        catch (_: IllegalStateException) { }
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
