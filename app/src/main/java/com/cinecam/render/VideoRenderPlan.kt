package com.cinecam.render

/** Pure sizing/timing rules for a burst render; no Android dependency so JVM tests cover them. */
internal object VideoRenderPlan {
    const val MAX_DIMENSION = 1280
    const val MIN_FRAME_US = 33_333L
    const val NOMINAL_FPS = 30

    /** Scales to at most [MAX_DIMENSION] and rounds down to the even size encoders require. */
    fun dimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        require(sourceWidth > 1 && sourceHeight > 1) { "ছবির মাপ সঠিক নয়" }
        val longest = maxOf(sourceWidth, sourceHeight)
        val scale = if (longest > MAX_DIMENSION) MAX_DIMENSION.toDouble() / longest else 1.0
        val width = (sourceWidth * scale).toInt().coerceAtLeast(2) and 1.inv()
        val height = (sourceHeight * scale).toInt().coerceAtLeast(2) and 1.inv()
        return width to height
    }

    /**
     * Relative presentation timestamps in microseconds. Real sensor spacing is kept,
     * but duplicates/near-duplicates are separated so the encoder never sees equal PTS.
     */
    fun timestampsUs(sensorNs: List<Long>): LongArray {
        require(sensorNs.isNotEmpty()) { "কোনো frame নেই" }
        val out = LongArray(sensorNs.size)
        val first = sensorNs.first()
        for (i in sensorNs.indices) {
            val relative = (sensorNs[i] - first) / 1000L
            out[i] = if (i == 0) 0L else maxOf(relative, out[i - 1] + MIN_FRAME_US)
        }
        return out
    }

    fun nominalFps(timestampsUs: LongArray): Int {
        if (timestampsUs.size < 2) return NOMINAL_FPS
        val span = timestampsUs.last() - timestampsUs.first()
        if (span <= 0) return NOMINAL_FPS
        val fps = ((timestampsUs.size - 1) * 1_000_000.0 / span).toInt()
        return fps.coerceIn(1, 60)
    }
}
