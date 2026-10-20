package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class CcrawVerifierTest {
    private fun fixture(): ByteArray {
        fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fun crc(bytes: ByteArray, size: Int) = CRC32().apply { update(bytes, 0, size) }.value.toInt()
        val h = buffer(64)
        h.put("CCRAW001".toByteArray()); h.putInt(8, 64); h.putInt(12, 4); h.putInt(16, 2)
        h.putInt(20, 16); h.putInt(28, 1); h.putLong(32, 100); h.putInt(60, crc(h.array(), 60))
        val payload = ByteArray(16) { it.toByte() }
        val f = buffer(96)
        f.put("FRM1".toByteArray()); f.putInt(4, 96); f.putLong(8, 200)
        f.putLong(16, 1000); f.putLong(24, 16); f.putInt(32, 4); f.putInt(36, 2)
        f.putInt(40, 8); f.putInt(44, 200); f.putInt(48, 17); f.putInt(52, 16)
        f.putInt(72, crc(payload, payload.size)); f.putInt(76, crc(f.array(), 76))
        val end = buffer(32)
        end.put("END1".toByteArray()); end.putInt(4, 32); end.putLong(8, 1); end.putLong(16, 16)
        end.putInt(24, crc(end.array(), 24))
        return h.array() + f.array() + payload + end.array()
    }
    private fun <T> withFile(bytes: ByteArray, action: (File) -> T): T {
        val file = File.createTempFile("ccraw-verifier-", ".ccraw")
        try { file.writeBytes(bytes); return action(file) } finally { file.delete() }
    }
    @Test fun independentlyChecksSavedPayloadAndOffset() = withFile(fixture()) { file ->
        val result = CcrawVerifier.verify(file)
        assertEquals(4, result.width); assertEquals(2, result.height)
        assertEquals(16L, result.payloadBytes); assertEquals(1, result.frames.size)
        assertEquals(160L, result.frames.first().payloadOffset)
        assertEquals(200L, result.frames.first().timestampNs)
        assertEquals(17L, result.frames.first().sourceFrameNumber)
    }
    @Test fun rejectsCorruptionTruncationAndTrailingBytes() {
        val good = fixture()
        val cases = listOf(12, 60, 80, 160, 200).map { index ->
            good.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
        } + listOf(good.copyOf(63), good.copyOf(170), good.copyOf(good.size - 1), good + byteArrayOf(1))
        for (bytes in cases) withFile(bytes) {
            try { CcrawVerifier.verify(it); fail("must reject malformed file") }
            catch (_: Exception) { }
        }
    }
    @Test fun rejectsResignedWrongGeometryAndFooterCount() {
        for (at in listOf(64 + 32, 176 + 8)) {
            val bytes = fixture()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(at, 2)
            val start = if (at < 176) 64 else 176
            val count = if (start == 64) 76 else 24
            b.putInt(start + count, CRC32().apply { update(bytes, start, count) }.value.toInt())
            withFile(bytes) {
                try { CcrawVerifier.verify(it); fail("must reject semantic mismatch") }
                catch (_: Exception) { }
            }
        }
    }
}
