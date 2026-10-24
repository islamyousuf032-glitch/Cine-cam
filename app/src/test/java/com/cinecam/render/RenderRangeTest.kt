package com.cinecam.render

import org.junit.Assert.*
import org.junit.Test

class RenderRangeTest {
    @Test fun longClipIsTrimmedToAWindow() {
        assertEquals(0 to 40, RenderRange.window(RenderRange.ALL, 40))
        assertEquals(0 to 24, RenderRange.window(RenderRange.FIRST, 40))
        assertEquals(16 to 24, RenderRange.window(RenderRange.LAST, 40))
    }

    @Test fun shortClipsAlwaysRenderWhole() {
        for (mode in RenderRange.values()) {
            assertEquals(0 to 8, RenderRange.window(mode, 8))
            assertEquals(0 to 24, RenderRange.window(mode, 24))
        }
    }

    @Test fun windowNeverEscapesTheClip() {
        for (size in 1..80) {
            for (mode in RenderRange.values()) {
                val (offset, length) = RenderRange.window(mode, size)
                assertTrue(offset >= 0)
                assertTrue(length >= 1)
                assertTrue(offset + length <= size)
            }
        }
    }

    @Test fun customChunkAndSavedNames() {
        assertEquals(0 to 5, RenderRange.window(RenderRange.FIRST, 30, chunk = 5))
        assertEquals(25 to 5, RenderRange.window(RenderRange.LAST, 30, chunk = 5))
        assertEquals(RenderRange.LAST, RenderRange.of("LAST"))
        assertEquals(RenderRange.ALL, RenderRange.of(null))
        assertEquals(RenderRange.ALL, RenderRange.of("bogus"))
        try { RenderRange.window(RenderRange.ALL, 0); fail("empty clip") } catch (_: IllegalArgumentException) { }
    }
}
