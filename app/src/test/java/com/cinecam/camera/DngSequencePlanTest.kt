package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class DngSequencePlanTest {
    @Test fun namesAreContiguousButRealMissingFrameGapsRemain() {
        val input = CcrawVerifier.Verified(4, 2, listOf(
            CcrawVerifier.Frame(200, 17, 160), CcrawVerifier.Frame(400, 20, 272)
        ), 32, 100)
        val plan = DngSequencePlan.entries(input)
        assertEquals(listOf("frame_000001.dng", "frame_000002.dng"), plan.map { it.name })
        assertEquals(listOf(100L, 300L), plan.map { it.ptsNs })
        assertEquals(listOf(17L, 20L), plan.map { it.frame.sourceFrameNumber })
        assertEquals(272L, plan.last().frame.payloadOffset)
    }
    @Test fun rejectsEmptyOversizedNonmonotonicOrPreEpochPlans() {
        val bad = listOf(emptyList(), List(9) { CcrawVerifier.Frame(200L + it, it.toLong(), 160) },
            listOf(CcrawVerifier.Frame(99, 1, 160)),
            listOf(CcrawVerifier.Frame(200, 1, 160), CcrawVerifier.Frame(200, 2, 272)))
        for (frames in bad) {
            try { DngSequencePlan.entries(CcrawVerifier.Verified(4, 2, frames, 16L * frames.size, 100)); fail("invalid sequence") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
