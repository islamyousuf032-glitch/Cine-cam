package com.cinecam.render

import org.junit.Assert.*
import org.junit.Test

class VideoRenderPlanTest {
    @Test fun sensorSizeScalesToEvenPreviewVideo() {
        assertEquals(1280 to 960, VideoRenderPlan.dimensions(4080, 3060))
        assertEquals(960 to 1280, VideoRenderPlan.dimensions(3060, 4080))
        assertEquals(1000 to 998, VideoRenderPlan.dimensions(1001, 999))
        assertEquals(2 to 2, VideoRenderPlan.dimensions(3, 3))
    }

    @Test fun invalidSourceSizeRejected() {
        try { VideoRenderPlan.dimensions(1, 100); fail("1px source must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun timestampsKeepRealSpacingAndNeverCollide() {
        val ns = listOf(1_000_000_000L, 1_333_333_333L, 1_666_666_666L)
        val us = VideoRenderPlan.timestampsUs(ns)
        assertEquals(0L, us[0])
        assertEquals(333_333L, us[1])
        assertEquals(666_666L, us[2])
        val equal = VideoRenderPlan.timestampsUs(listOf(5L, 5L, 5L))
        assertEquals(0L, equal[0])
        assertEquals(VideoRenderPlan.MIN_FRAME_US, equal[1])
        assertEquals(VideoRenderPlan.MIN_FRAME_US * 2, equal[2])
    }

    @Test fun nominalFpsUsesActualSpanAndStaysBounded() {
        val eightAt3fps = VideoRenderPlan.timestampsUs((0 until 8).map { 1_000_000_000L + it * 333_000_000L })
        assertEquals(3, VideoRenderPlan.nominalFps(eightAt3fps))
        assertEquals(30, VideoRenderPlan.nominalFps(longArrayOf(0)))
        assertEquals(1, VideoRenderPlan.nominalFps(longArrayOf(0, 5_000_000L)))
        assertEquals(60, VideoRenderPlan.nominalFps(longArrayOf(0, 1L)))
    }

    @Test fun emptyTimestampListRejected() {
        try { VideoRenderPlan.timestampsUs(emptyList()); fail("empty timestamps must be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
