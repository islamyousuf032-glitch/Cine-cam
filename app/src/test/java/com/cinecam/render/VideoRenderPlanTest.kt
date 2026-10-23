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

    @Test fun resolutionPresetsScaleIndependently() {
        assertEquals(1920 to 1440, VideoRenderPlan.dimensions(4080, 3060, 1920))
        assertEquals(960 to 720, VideoRenderPlan.dimensions(4080, 3060, 960))
        // A source that is already smaller than the preset is never upscaled.
        assertEquals(800 to 600, VideoRenderPlan.dimensions(800, 600, 1920))
        try { VideoRenderPlan.dimensions(4080, 3060, 100); fail("tiny presets must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun bitrateFollowsQualityAndStaysEncoderSafe() {
        assertEquals(2_000_000, VideoRenderPlan.bitrate(960, 720, 2))
        assertEquals(4_915_200, VideoRenderPlan.bitrate(1280, 960, 4))
        assertEquals(20_000_000, VideoRenderPlan.bitrate(1920, 1440, 8))
        try { VideoRenderPlan.bitrate(1280, 960, 0); fail("0 bpp must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun constantPacingSpacesEveryFrameEqually() {
        val ns = listOf(1_000_000_000L, 1_400_000_000L, 1_700_000_000L, 2_500_000_000L)
        val equal = VideoRenderPlan.timestampsUs(ns, constantFps = 10)
        assertArrayEquals(longArrayOf(0L, 100_000L, 200_000L, 300_000L), equal)
        assertEquals(10, VideoRenderPlan.nominalFps(equal))
        try { VideoRenderPlan.timestampsUs(ns, constantFps = 0); fail("0 fps must be rejected") }
        catch (_: IllegalArgumentException) { }
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
