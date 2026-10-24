package com.cinecam.camera

/**
 * Pure planning for a bounded RAW session. No Android or filesystem access: the caller
 * measures free bytes and passes them in, so the arithmetic is unit-testable.
 *
 * A session is always finite. Its frame budget is the smaller of
 *   - the duration the user asked for (seconds x target fps), and
 *   - what fits in free storage while keeping [RecordingPlanner.RESERVE_BYTES] untouched.
 *
 * The target fps is a pacing target only: the real rate follows storage and sensor speed.
 */
internal data class RecordingPlan(
    val width: Int,
    val height: Int,
    val fps: Int,
    val seconds: Int,
    /** Frames this session may actually write (storage or duration bound). */
    val maxFrames: Int,
    /** Frames implied by the requested duration; may be larger than [maxFrames]. */
    val slotFrames: Int,
    val frameBytes: Long,
    /** CCRAW container bytes for [maxFrames], headers and footer included. */
    val sessionBytes: Long,
    /** Bytes the session plus its optional DNG/archive copies want for [slotFrames]. */
    val requiredBytes: Long,
    val storageLimited: Boolean,
    val safetyTimeoutMs: Long
) {
    val intervalNs: Long get() = RecordingPlanner.intervalNs(fps)
    val durationMs: Long get() = seconds * 1000L
    val summary: String
        get() = buildString {
            append("$seconds s • $fps fps target • সর্বোচ্চ $maxFrames frame (${frameBytes * maxFrames / (1024 * 1024)} MiB)")
            if (storageLimited) append(" • জায়গা সীমিত")
        }
}

internal object RecordingPlanner {
    /** Never fill the phone: the session must leave this much free. */
    const val RESERVE_BYTES = 1024L * 1024 * 1024
    const val MIN_SECONDS = 3
    const val MAX_SECONDS = 180
    /** Offered session lengths; the planner still trims them to what the disk can hold. */
    val SECONDS_CHOICES = listOf(3, 5, 10, 30, 60, 120, 180)
    const val MIN_FPS = 1
    const val MAX_FPS = 30
    // 480 frames x 25 MB would exceed the 8 GiB container cap, so bytes usually bind first.
    const val MAX_FRAMES = 480
    const val MAX_SESSION_BYTES = 8L * 1024 * 1024 * 1024
    const val GRACE_MS = 6000L
    const val CcrawHeaderBytes = 64L
    const val CcrawFrameHeaderBytes = 96L
    const val CcrawFooterBytes = 32L

    fun frameBytes(width: Int, height: Int): Long = width.toLong() * height * 2

    fun intervalNs(fps: Int): Long {
        require(fps in MIN_FPS..MAX_FPS) { "fps সীমার বাইরে" }
        return 1_000_000_000L / fps
    }

    /**
     * [copyFactor] is the total byte multiplier for everything that stays after recording:
     * 1.0 = CCRAW only, 2.0 = CCRAW + one DNG per frame, +0.6 for the optional lossless archive.
     */
    fun plan(width: Int, height: Int, fps: Int, seconds: Int, availableBytes: Long,
             copyFactor: Double = 1.0): RecordingPlan {
        require(width >= 2 && height >= 2) { "RAW মাপ সঠিক নয়" }
        require(fps in MIN_FPS..MAX_FPS) { "fps সীমার বাইরে" }
        require(seconds in MIN_SECONDS..MAX_SECONDS) { "duration সীমার বাইরে" }
        require(copyFactor >= 1.0) { "copy factor সঠিক নয়" }
        val frameBytes = frameBytes(width, height)
        val usable = (availableBytes - RESERVE_BYTES).coerceAtLeast(0L).coerceAtMost(MAX_SESSION_BYTES)
        val perFrame = (frameBytes * copyFactor).toLong().coerceAtLeast(1L)
        var byStorage = (usable / perFrame).coerceAtMost(MAX_FRAMES.toLong()).toInt()
        val slots = (seconds.toLong() * fps).coerceAtMost(MAX_FRAMES.toLong()).toInt()
        var frames = minOf(slots, byStorage)
        // Keep the container itself inside the cap: headers count too, so a full 8 GiB
        // session must not spill past it (export/archive limits are 8 GiB as well).
        while (frames > 0 && frames * frameBytes + CcrawHeaderBytes + CcrawFooterBytes +
            frames * CcrawFrameHeaderBytes > usable) frames--
        if (frames < byStorage) byStorage = frames
        val overhead = CcrawHeaderBytes + CcrawFooterBytes + frames * CcrawFrameHeaderBytes
        return RecordingPlan(
            width = width, height = height, fps = fps, seconds = seconds,
            maxFrames = frames, slotFrames = slots, frameBytes = frameBytes,
            sessionBytes = if (frames > 0) frames * frameBytes + overhead else 0L,
            requiredBytes = (slots * frameBytes * copyFactor).toLong(),
            storageLimited = byStorage < slots,
            safetyTimeoutMs = seconds * 1000L + GRACE_MS
        )
    }

    /** Absolute slot start for the next request; pacing never bunches submissions. */
    fun slotDueNs(startNs: Long, slot: Int, fps: Int): Long =
        startNs + slot.toLong() * intervalNs(fps)

    /** Whole slots that elapsed while the camera or writer was busy. */
    fun missedSlots(dueNs: Long, nowNs: Long, fps: Int): Int {
        if (nowNs <= dueNs) return 0
        return ((nowNs - dueNs) / intervalNs(fps)).toInt()
    }
}
