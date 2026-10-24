package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class SegmentPlanTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun shortSessionsStayInOnePart() {
        assertEquals(1, RecordingPlanner.partCount(0))
        assertEquals(1, RecordingPlanner.partCount(500L * 1024 * 1024))
        assertEquals(1, RecordingPlanner.partCount(RecordingPlanner.PART_BYTES))
        assertEquals(2, RecordingPlanner.partCount(RecordingPlanner.PART_BYTES + 1))
    }

    @Test fun partsAreBoundedAndCoverTheWholeSession() {
        for (bytes in listOf(3 * gib, 7 * gib, 13 * gib, 24 * gib)) {
            val parts = RecordingPlanner.partCount(bytes)
            assertEquals((bytes + RecordingPlanner.PART_BYTES - 1) / RecordingPlanner.PART_BYTES, parts.toLong())
            assertTrue(parts <= RecordingPlanner.MAX_PARTS)
            // Every part must be able to hold at least one full-resolution frame.
            val perPart = bytes / parts
            assertTrue(perPart <= RecordingPlanner.PART_BYTES + 1)
        }
        assertEquals(RecordingPlanner.MAX_PARTS, RecordingPlanner.partCount(64 * gib))
    }

    @Test fun directSessionsMayExceedTheExportCap() {
        // 180 s at 5 fps of full-res RAW is ~22 GiB: allowed only without an export copy.
        val direct = RecordingPlanner.plan(4080, 3060, fps = 5, seconds = 180, availableBytes = 64 * gib,
            maxSessionBytes = RecordingPlanner.MAX_DIRECT_SESSION_BYTES, maxFramesCap = RecordingPlanner.MAX_DIRECT_FRAMES)
        assertTrue(direct.sessionBytes > RecordingPlanner.MAX_SESSION_BYTES)
        assertTrue(direct.maxFrames <= RecordingPlanner.MAX_DIRECT_FRAMES)
        assertTrue(direct.maxFrames > RecordingPlanner.MAX_FRAMES)
        val staged = RecordingPlanner.plan(4080, 3060, fps = 5, seconds = 180, availableBytes = 64 * gib)
        assertTrue(staged.sessionBytes <= RecordingPlanner.MAX_SESSION_BYTES)
        assertTrue(RecordingPlanner.partCount(direct.sessionBytes) <= RecordingPlanner.MAX_PARTS)
    }

    @Test fun capsOutsideTheSupportedRangeAreRejected() {
        for (cap in listOf(0L, 1024L, 32 * gib)) {
            try { RecordingPlanner.plan(4080, 3060, 3, 10, 64 * gib, maxSessionBytes = cap); fail("cap $cap") }
            catch (_: IllegalArgumentException) { }
        }
        for (frames in listOf(0, 100, 5000)) {
            try { RecordingPlanner.plan(4080, 3060, 3, 10, 64 * gib, maxFramesCap = frames); fail("frames $frames") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
