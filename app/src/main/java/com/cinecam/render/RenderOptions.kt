package com.cinecam.render

/**
 * Render presets the user picks before a burst is encoded. Pure Kotlin so JVM tests can
 * check the boundaries without an Android device.
 */

/** Long edge of the rendered video. Never larger than the preview the decoder produced. */
internal enum class RenderResolution(val label: String, val maxDimension: Int) {
    SD("540p", 960),
    HD("720p", 1280),
    FHD("1080p", 1920);

    companion object {
        fun of(name: String?): RenderResolution = values().firstOrNull { it.name == name } ?: HD
    }
}

/** Bits per pixel handed to the encoder: small files, balanced, or cleaner gradients. */
internal enum class RenderQuality(val label: String, val bitsPerPixel: Int) {
    SMALL("ছোট", 2),
    STANDARD("স্বাভাবিক", 4),
    HIGH("উচ্চ", 8);

    companion object {
        fun of(name: String?): RenderQuality = values().firstOrNull { it.name == name } ?: STANDARD
    }
}

/** Real sensor spacing keeps the burst's own speed; a constant rate spaces every frame equally. */
internal enum class RenderPacing(val label: String, val constantFps: Int?) {
    REAL("আসল", null),
    EQUAL_10("সমান ১০ fps", 10);

    companion object {
        fun of(name: String?): RenderPacing = values().firstOrNull { it.name == name } ?: REAL
    }
}

internal data class RenderOptions(
    val maxDimension: Int = RenderResolution.HD.maxDimension,
    val bitsPerPixel: Int = RenderQuality.STANDARD.bitsPerPixel,
    val constantFps: Int? = null
) {
    init {
        require(maxDimension in 240..2160) { "রেজোলিউশন সীমার বাইরে" }
        require(bitsPerPixel in 1..16) { "quality সীমার বাইরে" }
        require(constantFps == null || constantFps in 1..60) { "fps সীমার বাইরে" }
    }

    companion object {
        fun of(resolution: RenderResolution, quality: RenderQuality, pacing: RenderPacing) =
            RenderOptions(resolution.maxDimension, quality.bitsPerPixel, pacing.constantFps)
    }
}
