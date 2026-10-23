package com.cinecam.camera

import java.util.Locale

/** Numbered DNG files + real timestamp mapping, never synthetic fixed-FPS replacement frames. */
internal object DngSequencePlan {
    data class Entry(val name: String, val frame: CcrawVerifier.Frame, val ptsNs: Long)
    fun entries(verified: CcrawVerifier.Verified): List<Entry> {
        require(verified.frames.size in 1..CcrawVerifier.MAX_FRAMES)
        var previous = verified.epochNs - 1
        return verified.frames.mapIndexed { index, frame ->
            require(frame.timestampNs >= verified.epochNs && frame.timestampNs > previous)
            previous = frame.timestampNs
            Entry(String.format(Locale.ROOT, "frame_%06d.dng", index + 1), frame, frame.timestampNs - verified.epochNs)
        }
    }
}
