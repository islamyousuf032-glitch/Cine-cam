package com.cinecam.camera

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Independent of the native CRC/writer: bounded 64 KiB readback, no full-frame buffer. */
internal object CcrawVerifier {
    data class Frame(val timestampNs: Long, val sourceFrameNumber: Long, val payloadOffset: Long)
    data class Verified(val width: Int, val height: Int, val frames: List<Frame>, val payloadBytes: Long, val epochNs: Long)
    fun verify(file: File): Verified = RandomAccessFile(file, "r").use { input ->
        fun read(size: Int) = ByteArray(size).also { input.readFully(it) }
        fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun crc(bytes: ByteArray, length: Int): Long = CRC32().apply { update(bytes, 0, length) }.value
        fun uint(buffer: ByteBuffer, at: Int) = buffer.getInt(at).toLong() and 0xffffffffL
        fun magic(bytes: ByteArray, text: String) = bytes.copyOfRange(0, text.length).contentEquals(text.toByteArray(Charsets.US_ASCII))
        fun zero(bytes: ByteArray, start: Int) = bytes.drop(start).all { it == 0.toByte() }
        val header = read(64); val h = le(header)
        check(magic(header, "CCRAW001") && h.getInt(8) == 64) { "Invalid CCRAW header" }
        val width = h.getInt(12); val height = h.getInt(16); val epoch = h.getLong(32)
        val frameBytes = width.toLong() * height * 2
        check(width in 1..16384 && height in 1..16384 && frameBytes <= 256L * 1024 * 1024) { "Invalid dimensions" }
        check(h.getInt(20) == 16 && h.getInt(24) == 0 && h.getInt(28) == 1 && epoch >= 0) { "Unsupported RAW configuration" }
        check(header.copyOfRange(40, 60).all { it == 0.toByte() } && crc(header, 60) == uint(h, 60)) { "Header CRC/reserved fields invalid" }
        val frames = mutableListOf<Frame>()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        var previous = epoch - 1
        while (true) {
            val tag = read(4)
            if (magic(tag, "END1")) {
                val footer = tag + read(28); val f = le(footer)
                check(f.getInt(4) == 32 && zero(footer, 28) && crc(footer, 24) == uint(f, 24)) { "Footer CRC/layout invalid" }
                check(f.getLong(8) == frames.size.toLong() && f.getLong(16) == total) { "Footer counts invalid" }
                check(input.filePointer == input.length()) { "Trailing bytes after footer" }
                break
            }
            check(magic(tag, "FRM1") && frames.size < 8) { "Invalid frame magic/count" }
            val frame = tag + read(92); val f = le(frame)
            check(f.getInt(4) == 96 && zero(frame, 80) && crc(frame, 76) == uint(f, 76)) { "Frame header CRC/layout invalid" }
            val timestamp = f.getLong(8); val exposure = f.getLong(16)
            check(timestamp >= epoch && timestamp > previous && exposure >= 0) { "Invalid/nonmonotonic timestamp" }
            check(f.getInt(32) == width && f.getInt(36) == height && f.getInt(40) == width * 2 && f.getInt(52) == 16) { "Frame geometry invalid" }
            check(f.getLong(24) == frameBytes && total + frameBytes <= 256L * 1024 * 1024) { "Frame payload size invalid" }
            check(f.getInt(44) >= 0 && f.getInt(48) >= 0) { "Invalid ISO/frame number" }
            val offset = input.filePointer
            val payloadCrc = CRC32()
            var remaining = frameBytes
            while (remaining > 0) {
                val count = minOf(remaining, chunk.size.toLong()).toInt()
                input.readFully(chunk, 0, count); payloadCrc.update(chunk, 0, count)
                remaining -= count
            }
            check(payloadCrc.value == uint(f, 72)) { "RAW payload CRC mismatch" }
            frames.add(Frame(timestamp, uint(f, 48), offset))
            total += frameBytes; previous = timestamp
        }
        Verified(width, height, frames.toList(), total, epoch)
    }
}
