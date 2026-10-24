package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class ExportPlanTest {
    private val mib = 1024L * 1024

    @Test fun requiredBytesAddsABoundedMargin() {
        assertEquals(100 * mib + ExportPlan.MIN_MARGIN_BYTES, ExportPlan.requiredBytes(listOf(100 * mib)))
        // 2 GiB plan: 2% margin, not the 8 MiB floor.
        val twoGib = 2048 * mib
        assertEquals(twoGib + twoGib / ExportPlan.MARGIN_DIVISOR, ExportPlan.requiredBytes(listOf(twoGib)))
        assertEquals(ExportPlan.MIN_MARGIN_BYTES, ExportPlan.requiredBytes(emptyList()))
    }

    @Test fun fitsIsExactAtTheBoundary() {
        val sizes = listOf(52 * 25 * mib, 1290 * mib) // 52 DNG + one long clip, the real failure
        val required = ExportPlan.requiredBytes(sizes)
        assertTrue(ExportPlan.fits(sizes, required))
        assertFalse(ExportPlan.fits(sizes, required - 1))
        assertFalse(ExportPlan.fits(sizes, 1300 * mib)) // writing DNGs first would have "fit"
    }

    @Test fun largestFileIsCopiedFirst() {
        assertEquals(listOf(2, 0, 1), ExportPlan.copyOrder(listOf(25 * mib, 3 * mib, 1290 * mib)))
        assertEquals(listOf(0, 1), ExportPlan.copyOrder(listOf(5 * mib, 5 * mib)))
        assertTrue(ExportPlan.copyOrder(emptyList()).isEmpty())
    }

    @Test fun onlyFilesAtOrAboveTheThresholdCopyAlone() {
        assertFalse(ExportPlan.isLarge(ExportPlan.LARGE_FILE_BYTES - 1))
        assertTrue(ExportPlan.isLarge(ExportPlan.LARGE_FILE_BYTES))
        assertTrue(ExportPlan.isLarge(1290 * mib))
    }

    @Test fun failuresExplainThemselves() {
        assertTrue(ExportPlan.describeFailure("a.ccraw", 28, "x").contains("ENOSPC"))
        assertTrue(ExportPlan.describeFailure("a.ccraw", 28, "x").contains("জায়গা"))
        assertTrue(ExportPlan.describeFailure("a.ccraw", 5, "").contains("I/O"))
        assertTrue(ExportPlan.describeFailure("a.ccraw", 0, "source truncated").contains("source truncated"))
        assertTrue(ExportPlan.describeFailure("a.ccraw", 0, "").contains("অজানা"))
    }
}
