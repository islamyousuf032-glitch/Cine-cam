package com.cinecam.library

import org.junit.Assert.assertNotEquals
import org.junit.Test

class PreviewCacheKeyTest {
    private fun key(ccraw: String?, frame: Int = 0, dim: Int = 960, cfa: Int = 0, mode: Int = 0, white: Double = 1023.0) =
        PreviewCacheKey.of(null, ccraw, frame, dim, cfa, mode, white)

    @Test fun differentContainersNeverShareCachedFrames() {
        // Regression: CCRAW targets have no DNG URI, so two clips shared a cache key and a
        // newly rendered MP4 showed the previously rendered clip's frames.
        assertNotEquals(key("content://a/clip1.ccraw"), key("content://b/clip2.ccraw"))
    }

    @Test fun frameSizeCalibrationAndDimensionAllMatter() {
        val base = key("content://a/clip.ccraw")
        assertNotEquals(base, key("content://a/clip.ccraw", frame = 1))
        assertNotEquals(base, key("content://a/clip.ccraw", dim = 320))
        assertNotEquals(base, key("content://a/clip.ccraw", cfa = 1))
        assertNotEquals(base, key("content://a/clip.ccraw", mode = 2))
        assertNotEquals(base, key("content://a/clip.ccraw", white = 4095.0))
    }

    @Test fun dngAndContainerAreBothPartOfIdentity() {
        val a = PreviewCacheKey.of("content://d/1.dng", "content://c/1.ccraw", 0, 960, 0, 0, 1023.0)
        val b = PreviewCacheKey.of("content://d/1.dng", "content://c/2.ccraw", 0, 960, 0, 0, 1023.0)
        val c = PreviewCacheKey.of("content://d/2.dng", "content://c/1.ccraw", 0, 960, 0, 0, 1023.0)
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }
}
