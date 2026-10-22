package com.cinecam.library

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClipCatalogTest {
    private val id = "CineCam_1792657839174_cabf7553"
    private val name = "${id}_report.json"
    private fun report() = JSONObject(javaClass.getResource("/library/v81-report.json")!!.readText())
    private fun rejects(filename: String = name, value: String) {
        try { ClipCatalog.parse(filename, value); fail("untrusted report must reject") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: org.json.JSONException) { }
    }
    @Test fun realEightFrameDeviceReportIsIndexedWithoutPixels() {
        val clip = ClipCatalog.parse(name, report().toString())
        assertEquals(id, clip.id)
        assertEquals(1792657839174L, clip.epochMs)
        assertEquals(8L, clip.written)
        assertEquals(8, clip.requested)
        assertEquals(0L, clip.drops)
        assertEquals(14, clip.files.size)
        assertEquals(199757664L, clip.rawBytes)
        assertEquals(114400776L, clip.archiveBytes)
        assertEquals(true, clip.readback)
        assertEquals(true, clip.dng)
        assertEquals(true, clip.archive)
        assertTrue(clip.readable.contains("Capture: $id"))
        assertEquals(8, clip.framesCalibration.size)
        val first = clip.framesCalibration.first()
        assertEquals(439.0 / 512.0, first.neutralR, 1e-9)
        assertEquals(1.0, first.neutralG, 1e-9)
        assertEquals(523.0 / 1024.0, first.neutralB, 1e-9)
        assertEquals(64.0, first.black[0], 0.0)
        assertEquals(1023.0, first.white, 0.0)
    }
    @Test fun captureCalibrationMetadataIsOptionalAndBounded() {
        val metadata = ClipCatalog.parseMetadata("""{"cfa":1,"sensorOrientation":90,"whiteLevel":1023,
            "colorTransform1":[["1/1","0/1","0/1"],["0/1","1/1","0/1"],["0/1","0/1","1/1"]]}""")
        requireNotNull(metadata)
        assertEquals(1, metadata.cfa)
        assertEquals(90, metadata.sensorOrientation)
        assertEquals(1023.0, metadata.whiteLevel, 0.0)
        assertEquals(1.0, metadata.colorMatrix!![4], 0.0)
        assertNull(ClipCatalog.parseMetadata("not json"))
        assertEquals(0, ClipCatalog.parseMetadata("{}")!!.cfa)
    }
    @Test fun userStopIsNotTreatedAsCompletedEightFrames() {
        val json = report().put("reason", "user stop")
        json.getJSONObject("native").put("framesWritten", 4)
        val clip = ClipCatalog.parse(name, json.toString())
        assertEquals(4L, clip.written)
        assertEquals(8, clip.requested)
        assertEquals("user stop", clip.reason)
    }
    @Test fun optionalVerificationAndArchiveCanBeAbsent() {
        val json = report()
        for (key in listOf("losslessArchive", "dngSequenceOk", "readbackOk")) json.remove(key)
        val clip = ClipCatalog.parse(name, json.toString())
        assertNull(clip.archive); assertNull(clip.archiveBytes)
        assertNull(clip.dng); assertNull(clip.readback)
    }
    @Test fun fileReferencesCannotEscapeFolderOrCapture() {
        for (bad in listOf("../capture.ccraw", "content://attacker/file", "other_capture.ccraw", "${id}_x.dng")) {
            val json = report()
            json.getJSONObject("exportFiles").put("capture.ccraw", bad)
            rejects(value = json.toString())
        }
        val json = report(); json.getJSONObject("exportFiles").put("../evil", "${id}_../evil")
        rejects(value = json.toString())
    }
    @Test fun malformedUnsupportedOrMismatchedReportsReject() {
        rejects(value = "not JSON")
        rejects(value = report().put("exportSessionId", "CineCam_1_01234567").toString())
        rejects(value = report().put("schema", "unknown").toString())
        rejects(value = report().put("exportLayout", "nested").toString())
        val missing = report(); missing.getJSONObject("exportFiles").remove("report.json")
        rejects(value = missing.toString())
        rejects(filename = "../$name", value = report().toString())
        rejects(filename = "CineCam_999999999999999999999999_cabf7553_report.json", value = report().toString())
    }
    @Test fun reportByteAndMappingLimitsAreEnforced() {
        rejects(value = "x".repeat(ClipCatalog.MAX_REPORT_BYTES + 1))
        // A UTF-8 byte limit, not merely a character limit.
        rejects(value = "আ".repeat(ClipCatalog.MAX_REPORT_BYTES / 2))
        val tooMany = report()
        for (i in 1..65) tooMany.getJSONObject("exportFiles").put("extra$i", "${id}_extra$i")
        rejects(value = tooMany.toString())
        val negative = report(); negative.getJSONObject("native").put("framesWritten", -1)
        rejects(value = negative.toString())
    }
    @Test fun onlyExpectedPrefixedReportsAreCandidates() {
        assertTrue(ClipCatalog.isReport(name))
        for (bad in listOf("report.json", "${id}_metadata.json", "${id}_report.txt", "../$name"))
            assertFalse(ClipCatalog.isReport(bad))
    }
}
