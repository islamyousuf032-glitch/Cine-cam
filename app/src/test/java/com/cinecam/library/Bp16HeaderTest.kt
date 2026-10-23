package com.cinecam.library

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class Bp16HeaderTest {
    private fun header(sourceBytes: Long, block: Int = 65536, magic: String = "CCBP0001"): ByteArray {
        val bytes = ByteArray(24)
        magic.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(8, sourceBytes)
        buffer.putInt(16, block)
        buffer.putInt(20, CRC32().apply { update(bytes, 0, 20) }.value.toInt())
        return bytes
    }

    @Test fun validHeaderGivesExactSourceSize() {
        val info = requireNotNull(Bp16Header.parse(header(674_181_888)))
        assertEquals(674_181_888L, info.sourceBytes)
        assertEquals(65536, info.blockBytes)
        assertEquals(320L, requireNotNull(Bp16Header.parse(header(320))).sourceBytes)
    }

    @Test fun badMagicBlockSizeCrcOrSizeAreRejected() {
        assertNull(Bp16Header.parse(header(320, magic = "CCBP0002")))
        assertNull(Bp16Header.parse(header(320, block = 32768)))
        assertNull(Bp16Header.parse(header(0)))
        assertNull(Bp16Header.parse(header(321))) // odd sizes cannot be RAW16
        assertNull(Bp16Header.parse(header(Bp16Header.MAX_BYTES + 2)))
        assertNull(Bp16Header.parse(ByteArray(10)))
        val corrupted = header(320).also { it[10] = (it[10] + 1).toByte() }
        assertNull(Bp16Header.parse(corrupted))
    }
}

class RestoreNamesTest {
    private val id = "CineCam_1792750236346_07a3ccc3"

    @Test fun restoredNameKeepsCapturePrefixAndNeverCollides() {
        val name = RestoreNames.restoredName(id, 1_792_760_000_000)
        assertEquals("${id}_capture_restored_1792760000000.ccraw", name)
        assertTrue(RestoreNames.isRestored(name))
        assertTrue(name.startsWith(id))
        assertTrue(name.endsWith(".ccraw"))
        // A different restore of the same archive produces a different file name.
        assertNotEquals(name, RestoreNames.restoredName(id, 1_792_760_000_001))
    }

    @Test fun originalAndArchiveNamesAreNotRestored() {
        assertFalse(RestoreNames.isRestored("${id}_capture.ccraw"))
        assertFalse(RestoreNames.isRestored("${id}_capture.ccraw.bp16"))
        assertFalse(RestoreNames.isRestored("${id}_frame_000001.dng"))
    }

    @Test fun unsafeCaptureIdsAreRejected() {
        for (bad in listOf("", "CineCam_1", "CineCam_1_xyz", "../CineCam_1_abcdef12", "CineCam_1_ABCDEF12")) {
            try { RestoreNames.restoredName(bad, 1); fail("unsafe id must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
        try { RestoreNames.restoredName(id, 0); fail("zero epoch must be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
