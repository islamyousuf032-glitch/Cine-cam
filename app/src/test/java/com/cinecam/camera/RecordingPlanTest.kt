package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class RecordingPlanTest {
    private val gib = 1024L * 1024 * 1024
    private val frame = 4080L * 3060 * 2

    @Test fun durationSetsTheFrameBudgetWhenStorageIsPlentiful() {
        val plan = RecordingPlanner.plan(4080, 3060, fps = 3, seconds = 10, availableBytes = 64 * gib)
        assertEquals(30, plan.maxFrames)
        assertEquals(30, plan.slotFrames)
        assertEquals(frame, plan.frameBytes)
        assertFalse(plan.storageLimited)
        assertEquals(10_000L + RecordingPlanner.GRACE_MS, plan.safetyTimeoutMs)
        assertTrue(plan.sessionBytes > frame * 30)
    }

    @Test fun storageCapsFramesAndKeepsTheReserve() {
        // 1 GiB reserve + room for exactly 4 frames.
        val plan = RecordingPlanner.plan(4080, 3060, fps = 5, seconds = 20, availableBytes = gib + 4 * frame + 12)
        assertEquals(4, plan.maxFrames)
        assertEquals(100, plan.slotFrames)
        assertTrue(plan.storageLimited)
        assertEquals(4 * frame + RecordingPlanner.CcrawHeaderBytes + RecordingPlanner.CcrawFooterBytes +
            4 * RecordingPlanner.CcrawFrameHeaderBytes, plan.sessionBytes)
    }

    @Test fun nothingFitsWhenFreeSpaceIsBelowTheReserve() {
        val plan = RecordingPlanner.plan(4080, 3060, fps = 3, seconds = 5, availableBytes = gib - 1)
        assertEquals(0, plan.maxFrames)
        assertEquals(0L, plan.sessionBytes)
        assertTrue(plan.storageLimited)
        assertTrue(plan.requiredBytes > gib / 4)
    }

    @Test fun dngOrArchiveCopiesHalveTheBudget() {
        val onlyRaw = RecordingPlanner.plan(4080, 3060, 3, 10, 64 * gib, copyFactor = 1.0).maxFrames
        val withDng = RecordingPlanner.plan(4080, 3060, 3, 10, 64 * gib, copyFactor = 2.0).maxFrames
        val withArchive = RecordingPlanner.plan(4080, 3060, 3, 10, 64 * gib, copyFactor = 1.6).maxFrames
        assertEquals(30, onlyRaw)
        assertEquals(30, withDng) // 64 GiB still fits both
        assertTrue(withArchive <= onlyRaw)

        val tight = 1_000_000_000L // below reserve
        assertEquals(0, RecordingPlanner.plan(4080, 3060, 3, 10, tight).maxFrames)
    }

    @Test fun frameCountIsAlwaysFiniteAndBounded() {
        val plan = RecordingPlanner.plan(4080, 3060, 30, 30, 1024 * gib)
        assertEquals(RecordingPlanner.MAX_FRAMES, plan.maxFrames)
        assertFalse(plan.storageLimited)
        try { RecordingPlanner.plan(4080, 3060, 0, 5, gib); fail("0 fps") } catch (_: IllegalArgumentException) { }
        try { RecordingPlanner.plan(4080, 3060, 3, 0, gib); fail("0 seconds") } catch (_: IllegalArgumentException) { }
        try { RecordingPlanner.plan(4080, 3060, 3, RecordingPlanner.MAX_SECONDS + 1, gib); fail("too long") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun longSessionsAreOfferedAndStillBoundedByBytes() {
        assertTrue(RecordingPlanner.SECONDS_CHOICES.contains(180))
        // 8 GiB cap with ~24.97 MB frames: bytes bind well before the 480-frame cap.
        val long = RecordingPlanner.plan(4080, 3060, fps = 2, seconds = 180, availableBytes = 512L * gib)
        assertTrue(long.storageLimited)
        assertEquals(RecordingPlanner.MAX_FRAMES, long.slotFrames.coerceAtMost(RecordingPlanner.MAX_FRAMES))
        assertTrue(long.maxFrames in 1..RecordingPlanner.MAX_FRAMES)
        assertTrue(long.sessionBytes <= RecordingPlanner.MAX_SESSION_BYTES)
        assertEquals(180_000L + RecordingPlanner.GRACE_MS, long.safetyTimeoutMs)
    }

    @Test fun containerNeverSpillsPastTheSessionCap() {
        // 2 TB free: the planner must still keep the CCRAW itself within 8 GiB.
        val huge = RecordingPlanner.plan(4080, 3060, fps = 5, seconds = 180, availableBytes = 2_000L * gib)
        assertTrue(huge.sessionBytes <= RecordingPlanner.MAX_SESSION_BYTES)
        assertTrue(huge.maxFrames in 1..RecordingPlanner.MAX_FRAMES)
        assertTrue(huge.sessionBytes > 7L * gib)
    }

    @Test fun pacingUsesAbsoluteSlotTimes() {
        val start = 5_000_000_000L
        assertEquals(start, RecordingPlanner.slotDueNs(start, 0, 3))
        assertEquals(start + 333_333_333L, RecordingPlanner.slotDueNs(start, 1, 3))
        assertEquals(0, RecordingPlanner.missedSlots(start, start + 10, 3))
        assertEquals(1, RecordingPlanner.missedSlots(start, start + 400_000_000L, 3))
        assertEquals(3, RecordingPlanner.missedSlots(start, start + 1_000_000_000L, 3))
        assertEquals(1_000_000_000L / 5, RecordingPlanner.intervalNs(5))
    }

    @Test fun summaryMentionsStorageLimit() {
        val plan = RecordingPlanner.plan(4080, 3060, 3, 10, gib + frame)
        assertTrue(plan.summary.contains("জায়গা সীমিত"))
        assertTrue(plan.summary.contains("10 s"))
    }
}
