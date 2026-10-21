package com.cinecam.camera

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class RawReadableReportTest {
    @Test fun archiveStagesAreReadableAndDistinctFromWholeVerify() {
        val report = JSONObject().put("losslessArchiveRequested", true).put("losslessArchive", JSONObject()
            .put("ok", true).put("encodeWriteNs", 8_451_179_841L).put("verifyNs", 625_611_562L)
            .put("sourceReadNs", 10_000_000L).put("packNs", 400_000_000L).put("crcNs", 70_000_000L)
            .put("writeNs", 6_000_000_000L).put("fsyncNs", 1_900_000_000L).put("closeNs", 100_000L)
            .put("maxWriteNs", 1_500_000_000L).put("blocks", 3049).put("readCalls", 3049).put("writeCalls", 6099))
        val text = RawReadableReport.text(report)
        assertTrue(text.contains("সফল ও byte-exact যাচাই"))
        assertTrue(text.contains("8451.18 ms; পুরো-file verify: 625.61 ms"))
        assertTrue(text.contains("encode 400.00; CRC 70.00"))
        assertTrue(text.contains("fsync 1900.00"))
        assertTrue(text.contains("3049/6099"))
        assertFalse(text.contains("Lossless archive: OFF"))
    }
    @Test fun oldReportsDoNotInventMissingArchiveStages() {
        val text = RawReadableReport.text(JSONObject().put("losslessArchiveRequested", true)
            .put("losslessArchive", JSONObject().put("ok", true).put("encodeWriteNs", 10)))
        assertFalse(text.contains("Archive stage"))
        assertTrue(RawReadableReport.text(JSONObject()).contains("Lossless archive: OFF"))
    }
    @Test fun errorsAndStorageDelayAreVisibleWithoutMislabelingCaptureTime() {
        val report = JSONObject().put("losslessArchiveRequested", true).put("losslessArchiveError", "test-error")
            .put("wallUntilDrainNs", 8_191_378_382L).put("captureWindowNs", 4_231_529_946L)
            .put("pacedCapacityWaitNs", 2_219_273_905L).put("dngSequenceNs", 7_347_079_007L)
            .put("exportDataCopyNs", 38_470_096_079L).put("exportDataBytes", 509497469)
        val text = RawReadableReport.text(report)
        assertTrue(text.contains("8191.38; এর ভিতরে capture window 4231.53"))
        assertTrue(text.contains("Export data copy: 38470.10"))
        assertTrue(text.contains("losslessArchiveError: test-error"))
    }
    @Test fun numericFormattingIsStableAcrossDeviceLocales() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val text = RawReadableReport.text(JSONObject().put("dngSequenceNs", 1234567))
            assertTrue(text.contains("1.23"))
        } finally { Locale.setDefault(previous) }
    }
}
