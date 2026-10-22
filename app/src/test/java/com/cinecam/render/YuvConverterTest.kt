package com.cinecam.render

import org.junit.Assert.*
import org.junit.Test

class YuvConverterTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun limitedRangeWhiteAndBlackMatchBt601() {
        val white = YuvConverter.convert(IntArray(4) { argb(255, 255, 255) }, 2, 2, YuvLayout.I420)
        assertEquals(235, white.data[0].toInt() and 0xff)
        assertEquals(128, white.data[4].toInt() and 0xff)
        assertEquals(128, white.data[5].toInt() and 0xff)

        val black = YuvConverter.convert(IntArray(4) { argb(0, 0, 0) }, 2, 2, YuvLayout.I420)
        assertEquals(16, black.data[0].toInt() and 0xff)
        assertEquals(128, black.data[4].toInt() and 0xff)
    }

    @Test fun redPushesCrUpAndCbDown() {
        val frame = YuvConverter.convert(IntArray(4) { argb(255, 0, 0) }, 2, 2, YuvLayout.I420)
        val u = frame.data[4].toInt() and 0xff
        val v = frame.data[5].toInt() and 0xff
        assertTrue("Cr should rise for red, was $v", v > 200)
        assertTrue("Cb should fall for red, was $u", u < 120)
    }

    @Test fun nv12InterleavesSameChromaLessSamples() {
        val pixels = IntArray(16) { argb(40, 200, 90) }
        val i420 = YuvConverter.convert(pixels, 4, 4, YuvLayout.I420)
        val nv12 = YuvConverter.convert(pixels, 4, 4, YuvLayout.NV12)
        assertEquals(24, i420.data.size)
        assertEquals(24, nv12.data.size)
        for (i in 0 until 16) assertEquals(i420.data[i], nv12.data[i])
        for (i in 0 until 4) {
            assertEquals(i420.data[16 + i], nv12.data[16 + i * 2])
            assertEquals(i420.data[20 + i], nv12.data[16 + i * 2 + 1])
        }
    }

    @Test fun invalidDimensionsAreRejected() {
        for ((w, h) in listOf(3 to 2, 2 to 3, 0 to 2)) {
            try {
                YuvConverter.convert(IntArray((w * h).coerceAtLeast(0)), w, h, YuvLayout.I420)
                fail("odd/empty dimensions must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            YuvConverter.convert(IntArray(3), 2, 2, YuvLayout.I420)
            fail("pixel count mismatch must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
