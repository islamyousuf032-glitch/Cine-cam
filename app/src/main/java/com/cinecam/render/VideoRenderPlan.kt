package com.cinecam.render

/** Pure sizing/timing rules for a burst render; no Android dependency so JVM tests cover them. */
internal object VideoRenderPlan {
    const val MAX_DIMENSION = 1280
    const val MIN_FRAME_US = 33_333L
    const val NOMINAL_FPS = 30

    /** Scales to at most [maxDimension] and rounds down to the even size encoders require. */
    fun dimensions(sourceWidth: Int, sourceHeight: Int, maxDimension: Int = MAX_DIMENSION): Pair<Int, Int> {
        require(sourceWidth > 1 && sourceHeight > 1) { "ছবির মাপ সঠিক নয়" }
        require(maxDimension in 240..2160) { "রেজোলিউশন সীমার বাইরে" }
        val longest = maxOf(sourceWidth, sourceHeight)
        val scale = if (longest > maxDimension) maxDimension.toDouble() / longest else 1.0
        val width = (sourceWidth * scale).toInt().coerceAtLeast(2) and 1.inv()
        val height = (sourceHeight * scale).toInt().coerceAtLeast(2) and 1.inv()
        return width to height
    }

    /**
     * Relative presentation timestamps in microseconds. Real sensor spacing is kept unless
     * [constantFps] is set (then every frame is spaced equally). Duplicates are always
     * separated so the encoder never sees equal PTS.
     */
    fun timestampsUs(sensorNs: List<Long>, constantFps: Int? = null): LongArray {
        require(sensorNs.isNotEmpty()) { "কোনো frame নেই" }
        require(constantFps == null || constantFps in 1..60) { "fps সীমার বাইরে" }
        val out = LongArray(sensorNs.size)
        val first = sensorNs.first()
        val step = constantFps?.let { 1_000_000L / it }
        for (i in sensorNs.indices) {
            val relative = if (step != null) i * step else (sensorNs[i] - first) / 1000L
            out[i] = if (i == 0) 0L else maxOf(relative, out[i - 1] + MIN_FRAME_US)
        }
        return out
    }

    /** Bits per pixel for the chosen quality, clamped to what phone encoders accept. */
    fun bitrate(width: Int, height: Int, bitsPerPixel: Int = 4): Int {
        require(width > 1 && height > 1) { "ভিডিওর মাপ সঠিক নয়" }
        require(bitsPerPixel in 1..16) { "quality সীমার বাইরে" }
        return (width.toLong() * height * bitsPerPixel).coerceIn(2_000_000L, 20_000_000L).toInt()
    }

    fun nominalFps(timestampsUs: LongArray): Int {
        if (timestampsUs.size < 2) return NOMINAL_FPS
        val span = timestampsUs.last() - timestampsUs.first()
        if (span <= 0) return NOMINAL_FPS
        val fps = ((timestampsUs.size - 1) * 1_000_000.0 / span).toInt()
        return fps.coerceIn(1, 60)
    }
}
