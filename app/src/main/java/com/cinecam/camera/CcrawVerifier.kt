package com.cinecam.camera

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Independent of the native CRC/writer: bounded 64 KiB readback, no full-frame buffer. */
internal object CcrawVerifier {
    // A planned long session has hundreds of frames; these are safety bounds, not formats.
    const val MAX_FRAMES = 2000
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    data class Frame(val timestampNs: Long, val sourceFrameNumber: Long, val payloadOffset: Long)
    data class Verified(val width: Int, val height: Int, val frames: List<Frame>, val payloadBytes: Long, val epochNs: Long)

    /**
     * Random-access source: a staging file or a MediaStore descriptor written in place.
     * `pread` keeps the verifier independent of the native writer even in direct mode.
     */
    private interface Source : Closeable {
        val length: Long
        fun read(buffer: ByteArray, start: Int, count: Int, at: Long): Int
    }
    private class FileSource(private val file: RandomAccessFile) : Source {
        override val length get() = file.length()
        override fun read(buffer: ByteArray, start: Int, count: Int, at: Long): Int {
            file.seek(at)
            return file.read(buffer, start, count)
        }
        override fun close() = file.close()
    }
    private class DescriptorSource(private val descriptor: FileDescriptor, override val length: Long) : Source {
        override fun read(buffer: ByteArray, start: Int, count: Int, at: Long): Int =
            Os.pread(descriptor, buffer, start, count, at)
        override fun close() = Unit // The caller owns the descriptor and its file descriptor.
    }

    fun verify(file: File): Verified = FileSource(RandomAccessFile(file, "r")).use { verify(it) }

    /** Verifies a file the app is still writing through a provider descriptor (direct mode). */
    fun verify(descriptor: ParcelFileDescriptor): Verified =
        verify(DescriptorSource(descriptor.fileDescriptor, Os.fstat(descriptor.fileDescriptor).st_size))

    private fun verify(source: Source): Verified = source.use { body ->
        var cursor = 0L
        fun readInto(buffer: ByteArray, start: Int, count: Int) {
            var done = 0
            while (done < count) {
                val n = body.read(buffer, start + done, count - done, cursor + done)
                check(n > 0) { "RAW readback ended early" }
                done += n
            }
            cursor += count
        }
        fun read(size: Int): ByteArray = ByteArray(size).also { readInto(it, 0, size) }
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
                check(cursor == body.length) { "Trailing bytes after footer" }
                break
            }
            check(magic(tag, "FRM1") && frames.size < MAX_FRAMES) { "Invalid frame magic/count" }
            val frame = tag + read(92); val f = le(frame)
            check(f.getInt(4) == 96 && zero(frame, 80) && crc(frame, 76) == uint(f, 76)) { "Frame header CRC/layout invalid" }
            val timestamp = f.getLong(8); val exposure = f.getLong(16)
            check(timestamp >= epoch && timestamp > previous && exposure >= 0) { "Invalid/nonmonotonic timestamp" }
            check(f.getInt(32) == width && f.getInt(36) == height && f.getInt(40) == width * 2 && f.getInt(52) == 16) { "Frame geometry invalid" }
            check(f.getLong(24) == frameBytes && frameBytes <= 256L * 1024 * 1024 &&
                total + frameBytes <= MAX_TOTAL_BYTES) { "Frame payload size invalid" }
            check(f.getInt(44) >= 0 && f.getInt(48) >= 0) { "Invalid ISO/frame number" }
            val offset = cursor
            val payloadCrc = CRC32()
            var remaining = frameBytes
            while (remaining > 0) {
                val count = minOf(remaining, chunk.size.toLong()).toInt()
                readInto(chunk, 0, count); payloadCrc.update(chunk, 0, count)
                remaining -= count
            }
            check(payloadCrc.value == uint(f, 72)) { "RAW payload CRC mismatch" }
            frames.add(Frame(timestamp, uint(f, 48), offset))
            total += frameBytes; previous = timestamp
        }
        Verified(width, height, frames.toList(), total, epoch)
    }
}
