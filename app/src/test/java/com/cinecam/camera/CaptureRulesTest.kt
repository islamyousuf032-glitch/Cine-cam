package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class CaptureRulesTest {
    @Test fun galaxyF23ExposureStaysWithinAdvertisedRange() {
        val lo = 77100L
        val hi = 647363350L
        assertEquals(lo, CaptureRules.exposureAt(-1f, lo, hi))
        assertTrue(CaptureRules.exposureAt(2f, lo, hi) in hi - 1..hi)
        var previous = lo
        for (i in 0..100) {
            val value = CaptureRules.exposureAt(i / 100f, lo, hi)
            assertTrue(value >= previous && value in lo..hi)
            assertEquals(i / 100f, CaptureRules.exposurePosition(value, lo, hi), 0.0001f)
            previous = value
        }
    }
    @Test fun exifTagsCoverRearPortraitAndLandscape() {
        assertEquals(6, CaptureRules.exifOrientation(90, 0, false))
        assertEquals(1, CaptureRules.exifOrientation(90, 90, false))
        assertEquals(8, CaptureRules.exifOrientation(90, 180, false))
        assertEquals(3, CaptureRules.exifOrientation(90, 270, false))
    }
}
