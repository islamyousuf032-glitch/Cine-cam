package com.cinecam.library

import java.util.zip.CRC32

/** Accepted archive names are `<captureId>_capture.ccraw.bp16`; CRC32 covers the first 20 bytes. */
internal object Bp16Header {
    const val HEADER_BYTES = 24
    const val BLOCK_BYTES = 65536
    const val MAX_BYTES = 8L * 1024 * 1024 * 1024

    data class Info(val sourceBytes: Long, val blockBytes: Int)

    fun parse(header: ByteArray): Info? {
        if (header.size < HEADER_BYTES) return null
        if (String(header, 0, 8, Charsets.US_ASCII) != "CCBP0001") return null
        fun u32(offset: Int): Long =
            (header[offset].toLong() and 0xff) or ((header[offset + 1].toLong() and 0xff) shl 8) or
                ((header[offset + 2].toLong() and 0xff) shl 16) or ((header[offset + 3].toLong() and 0xff) shl 24)
        fun u64(offset: Int): Long = u32(offset) or (u32(offset + 4) shl 32)
        val sourceBytes = u64(8)
        val blockBytes = u32(16)
        val crc = CRC32().apply { update(header, 0, 20) }.value
        if (blockBytes != BLOCK_BYTES.toLong() || sourceBytes <= 0 || sourceBytes % 2 != 0L ||
            sourceBytes > MAX_BYTES || crc != u32(20)) return null
        return Info(sourceBytes, BLOCK_BYTES)
    }
}

/** Restored files keep the capture id so metadata/calibration still apply, and never overwrite. */
internal object RestoreNames {
    private val CLIP_ID = Regex("^CineCam_[0-9]+_[a-f0-9]{8}$")

    fun restoredName(clipId: String, epochMs: Long): String {
        require(CLIP_ID.matches(clipId)) { "Capture prefix সঠিক নয়" }
        require(epochMs > 0) { "Restore timestamp সঠিক নয়" }
        return "${clipId}_capture_restored_$epochMs.ccraw"
    }

    fun isRestored(name: String): Boolean = name.contains("_capture_restored_") && name.endsWith(".ccraw")
}
