package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class PreviewGeometryTest {
    @Test fun galaxyF23PortraitDoesNotRotateTheProducerAgain() {
        val points = PreviewGeometry.corners(1080, 1920, 1920, 1080, 90, 0, false)
        assertArrayEquals(floatArrayOf(0f, 0f, 1080f, 0f, 1080f, 1920f, 0f, 1920f), points, 0.01f)
    }
    @Test fun allDisplayRotationsStayCenteredAndDoNotStretch() {
        for (sensor in listOf(0, 90, 180, 270)) for (display in listOf(0, 90, 180, 270)) {
            for ((width, height) in listOf(1080 to 1720, 1920 to 800, 1080 to 1000)) {
                val p = PreviewGeometry.corners(width, height, 1920, 1080, sensor, display, false)
                assertEquals(width / 2f, (0..3).sumOf { p[it * 2].toDouble() }.toFloat() / 4, 0.01f)
                assertEquals(height / 2f, (0..3).sumOf { p[it * 2 + 1].toDouble() }.toFloat() / 4, 0.01f)
                for (i in 0..3) {
                    assertTrue(p[i * 2] >= -0.01f && p[i * 2] <= width + 0.01f)
                    assertTrue(p[i * 2 + 1] >= -0.01f && p[i * 2 + 1] <= height + 0.01f)
                }
                val edge1 = hypot(p[2] - p[0], p[3] - p[1])
                val edge2 = hypot(p[6] - p[0], p[7] - p[1])
                val ratio = if (sensor % 180 == 0) 1920f / 1080f else 1080f / 1920f
                assertEquals(ratio, edge1 / edge2, 0.001f)
            }
        }
    }
    @Test fun reversePortraitAndLandscapeHaveDistinctCounterRotations() {
        val reverse = PreviewGeometry.corners(1080, 1920, 1920, 1080, 90, 180, false)
        assertArrayEquals(floatArrayOf(1080f, 1920f, 0f, 1920f, 0f, 0f, 1080f, 0f), reverse, 0.01f)
        val landscape = PreviewGeometry.corners(1920, 1080, 1920, 1080, 90, 90, false)
        assertArrayEquals(floatArrayOf(0f, 1080f, 0f, 0f, 1920f, 0f, 1920f, 1080f), landscape, 0.01f)
    }
}
