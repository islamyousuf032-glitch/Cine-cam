package com.cinecam.library

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CcrawHeaderTest {
    private fun header(width: Int = 4080, height: Int = 3060, bits: Int = 16, magic: String = "CCRAW001",
                       headerBytes: Int = 64): ByteArray {
        val bytes = ByteArray(64)
        magic.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(8, headerBytes)
        buffer.putInt(12, width)
        buffer.putInt(16, height)
        buffer.putInt(20, bits)
        return bytes
    }

    private fun fileSize(frames: Int, width: Int = 4080, height: Int = 3060) =
        64L + frames.toLong() * (96L + width.toLong() * height * 2) + 32L

    @Test fun frameCountComesFromFileSizeNotReport() {
        val info = requireNotNull(CcrawHeader.parse(header(), fileSize(8)))
        assertEquals(4080, info.width)
        assertEquals(3060, info.height)
        assertEquals(8, info.frames)
    }

    @Test fun longSessionFrameCountIsNotCappedAtEight() {
        val info = requireNotNull(CcrawHeader.parse(header(), fileSize(150)))
        assertEquals(150, info.frames)
        assertEquals(150, requireNotNull(CcrawHeader.parse(header(), fileSize(150, 1024, 768))).frames)
    }

    @Test fun malformedHeadersAreRejected() {
        assertNull(CcrawHeader.parse(header(magic = "CCRAW002"), fileSize(1)))
        assertNull(CcrawHeader.parse(header(headerBytes = 60), fileSize(1)))
        assertNull(CcrawHeader.parse(header(bits = 12), fileSize(1)))
        assertNull(CcrawHeader.parse(header(width = 0), fileSize(1)))
        assertNull(CcrawHeader.parse(ByteArray(10), fileSize(1)))
    }

    @Test fun unknownOrTinyFileSizeYieldsNoFrames() {
        assertEquals(0, requireNotNull(CcrawHeader.parse(header(), null)).frames)
        assertEquals(0, requireNotNull(CcrawHeader.parse(header(), 63L)).frames)
        assertEquals(0, requireNotNull(CcrawHeader.parse(header(), 64L + 32L)).frames)
    }
}
