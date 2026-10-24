package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class RawExportNamesTest {
    private val first = "CineCam_1792561349594_b5e39469"
    private val second = "CineCam_1792561349595_b5e39469"
    @Test fun sharedDirectoryNamesAreUniqueAndHaveNoSubfolder() {
        val originals = listOf("capture.ccraw", "capture.ccraw.bp16", "metadata.json", "report.json", "report.txt", "sequence.json") +
            (1..8).map { "frame_%06d.dng".format(it) }
        val names = listOf(first, second).flatMap { id -> originals.map { RawExportNames.name(id, it) } }
        assertEquals(28, names.toSet().size)
        assertTrue(names.none { '/' in it || '\\' in it })
        assertEquals("${first}_frame_000001.dng", RawExportNames.name(first, "frame_000001.dng"))
    }
    @Test fun manifestMappingIsExactAndRetrySafe() {
        val files = setOf("frame_000001.dng", "frame_000002.dng", "metadata.json")
        val mapped = RawExportNames.sequenceReference(first, "frame_000001.dng", files)
        assertEquals(RawExportNames.name(first, "frame_000001.dng"), mapped)
        assertEquals(mapped, RawExportNames.sequenceReference(first, mapped, files))
        for (bad in listOf("../frame_000001.dng", "metadata.json", "frame_000009.dng", "${second}_frame_000001.dng")) {
            try { RawExportNames.sequenceReference(first, bad, files); fail("unknown DNG must fail") }
            catch (_: IllegalStateException) { }
        }
    }
    @Test fun directRowsUseTheSameNamesAndSafeMimeTypes() {
        // A direct session publishes exactly the names a staging export would have produced.
        val session = "CineCam_1792835277528_503c24f6"
        assertEquals("${session}_capture.ccraw", RawExportNames.name(session, "capture.ccraw"))
        assertEquals("${session}_frame_000001.dng", RawExportNames.name(session, "frame_000001.dng"))
        assertEquals("application/octet-stream", RawExportNames.mimeOf("capture.ccraw"))
        assertEquals("application/octet-stream", RawExportNames.mimeOf("capture.ccraw.bp16"))
        assertEquals("image/x-adobe-dng", RawExportNames.mimeOf("frame_000001.dng"))
        assertEquals("application/json", RawExportNames.mimeOf("sequence.json"))
        assertEquals("application/json", RawExportNames.mimeOf("metadata.json"))
        assertEquals("text/plain", RawExportNames.mimeOf("report.txt"))
        assertEquals("application/octet-stream", RawExportNames.mimeOf("noextension"))
        assertEquals("application/octet-stream", RawExportNames.mimeOf("weird.DNG"))
    }

    @Test fun unsafeCaptureIdsAndNamesReject() {
        for (bad in listOf("../capture.ccraw", "a/b", "a\\b", ".", "..", "x".repeat(201))) {
            try { RawExportNames.name(first, bad); fail("invalid basename") }
            catch (_: IllegalArgumentException) { }
        }
        try { RawExportNames.name("../bad", "capture.ccraw"); fail("invalid session") }
        catch (_: IllegalArgumentException) { }
    }
}
