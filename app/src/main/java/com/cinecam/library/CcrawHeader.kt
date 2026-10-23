package com.cinecam.library

/** Header facts available without trusting a saved report. */
internal data class CcrawInfo(val width: Int, val height: Int, val bits: Int, val frames: Int)

internal object CcrawHeader {
    const val HEADER_BYTES = 64
    const val FRAME_HEADER_BYTES = 96
    const val FOOTER_BYTES = 32
    // Sanity bound only: a long session has hundreds of frames.
    const val MAX_FRAMES = 20000

    fun parse(header: ByteArray, fileSize: Long?): CcrawInfo? {
        if (header.size < HEADER_BYTES) return null
        if (String(header, 0, 8, Charsets.US_ASCII) != "CCRAW001") return null
        fun u32(offset: Int): Long =
            (header[offset].toLong() and 0xff) or ((header[offset + 1].toLong() and 0xff) shl 8) or
                ((header[offset + 2].toLong() and 0xff) shl 16) or ((header[offset + 3].toLong() and 0xff) shl 24)
        if (u32(8) != HEADER_BYTES.toLong()) return null
        val width = u32(12)
        val height = u32(16)
        val bits = u32(20)
        if (width !in 2..16384 || height !in 2..16384 || bits != 16L) return null
        val payload = width * height * 2
        val frameBytes = FRAME_HEADER_BYTES + payload
        val frames = if (fileSize != null && fileSize >= HEADER_BYTES + FOOTER_BYTES)
            (((fileSize - HEADER_BYTES - FOOTER_BYTES) / frameBytes).coerceIn(0, MAX_FRAMES.toLong())).toInt()
        else 0
        return CcrawInfo(width.toInt(), height.toInt(), 16, frames)
    }
}
