package com.cinecam.render

import org.junit.Assert.*
import org.junit.Test

class RenderOptionsTest {
    @Test fun presetsMapToPlanValues() {
        val fastest = RenderOptions.of(RenderResolution.SD, RenderQuality.SMALL, RenderPacing.REAL)
        assertEquals(960, fastest.maxDimension)
        assertEquals(2, fastest.bitsPerPixel)
        assertNull(fastest.constantFps)

        val best = RenderOptions.of(RenderResolution.FHD, RenderQuality.HIGH, RenderPacing.EQUAL_10)
        assertEquals(1920, best.maxDimension)
        assertEquals(8, best.bitsPerPixel)
        assertEquals(10, best.constantFps)
    }

    @Test fun savedNamesFallBackToDefaults() {
        assertEquals(RenderResolution.HD, RenderResolution.of(null))
        assertEquals(RenderResolution.HD, RenderResolution.of("bogus"))
        assertEquals(RenderResolution.FHD, RenderResolution.of("FHD"))
        assertEquals(RenderQuality.STANDARD, RenderQuality.of(null))
        assertEquals(RenderPacing.REAL, RenderPacing.of("bogus"))
        assertEquals(RenderPacing.EQUAL_10, RenderPacing.of("EQUAL_10"))
    }

    @Test fun outOfRangeOptionsRejected() {
        val bad = listOf(
            { RenderOptions(maxDimension = 100) },
            { RenderOptions(maxDimension = 4000) },
            { RenderOptions(bitsPerPixel = 0) },
            { RenderOptions(constantFps = 0) },
            { RenderOptions(constantFps = 120) }
        )
        for (build in bad) {
            try { build(); fail("out-of-range option must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
