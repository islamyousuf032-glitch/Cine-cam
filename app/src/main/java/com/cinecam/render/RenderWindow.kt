package com.cinecam.render

/**
 * A long session can hold hundreds of frames; rendering all of them takes minutes.
 * This picks a window of frames to render without touching the source files.
 */
internal enum class RenderRange(val label: String) {
    ALL("সব"),
    FIRST("প্রথম"),
    LAST("শেষ");

    companion object {
        /** Frames per window for [FIRST]/[LAST]; short clips always render whole. */
        const val CHUNK = 24

        /** Returns `offset to length`; callers still bound-check against the real list. */
        fun window(mode: RenderRange, size: Int, chunk: Int = CHUNK): Pair<Int, Int> {
            require(size > 0) { "frame নেই" }
            require(chunk > 0) { "window সীমার বাইরে" }
            if (mode == ALL || size <= chunk) return 0 to size
            return if (mode == FIRST) 0 to chunk else (size - chunk) to chunk
        }

        fun of(name: String?): RenderRange = values().firstOrNull { it.name == name } ?: ALL
    }
}
