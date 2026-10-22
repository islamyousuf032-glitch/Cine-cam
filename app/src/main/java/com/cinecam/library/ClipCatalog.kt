package com.cinecam.library

import com.cinecam.camera.RawExportNames
import com.cinecam.camera.RawReadableReport
import org.json.JSONObject

/** Untrusted saved reports are an index, NOT independent payload verification. */
internal data class ClipFile(val original: String, val exported: String)
internal data class FrameCalibration(
    val neutralR: Double, val neutralG: Double, val neutralB: Double,
    val black: DoubleArray, val white: Double
)
/** Calibration copied from the capture's own metadata/report sidecars; optional. */
internal data class ClipMetadata(
    val cfa: Int = 0, val sensorOrientation: Int? = null,
    val whiteLevel: Double = 0.0, val colorMatrix: DoubleArray? = null,
    /** 0=none, 1=invert (ColorMatrix/Camera2 ColorTransform), 2=direct ForwardMatrix. */
    val matrixMode: Int = 0
)
internal data class SavedClip(
    val id: String, val epochMs: Long, val written: Long, val requested: Int,
    val reason: String, val rawBytes: Long, val archiveBytes: Long?, val drops: Long,
    val readback: Boolean?, val dng: Boolean?, val archive: Boolean?,
    val files: List<ClipFile>, val readable: String, val sensorOrientation: Int? = null,
    val metadata: ClipMetadata? = null, val framesCalibration: List<FrameCalibration> = emptyList(),
    val report: org.json.JSONObject? = null, val frameTimestamps: List<Long> = emptyList()
)
internal object ClipCatalog {
    const val MAX_REPORT_BYTES = 65536
    const val MAX_DOCUMENTS = 5000
    const val MAX_REPORTS = 100
    private val reportPattern = Regex("(CineCam_([0-9]+)_[a-f0-9]{8})_report\\.json")
    fun isReport(name: String) = reportPattern.matches(name)

    /** Parses "11/16", "0.6" or numeric JSON values; null if not finite. */
    fun fraction(value: Any?): Double? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> {
            val parts = value.trim().split('/')
            val number = parts[0].toDoubleOrNull()
            val denominator = if (parts.size == 2) parts[1].toDoubleOrNull() else 1.0
            if (number == null || denominator == null || denominator == 0.0) null else (number / denominator).takeIf { it.isFinite() }
        }
        else -> null
    }

    private fun numbers(array: org.json.JSONArray?, count: Int): DoubleArray? {
        if (array == null || array.length() != count) return null
        val out = DoubleArray(count)
        for (i in 0 until count) out[i] = fraction(array.opt(i)) ?: return null
        return out
    }

    fun parseMetadata(text: String): ClipMetadata? = try {
        val json = JSONObject(text)
        val cfa = json.optInt("cfa", 0).coerceIn(0, 3)
        val orientation = json.optInt("sensorOrientation", 0).takeIf { it in 90..270 && it % 90 == 0 }
        val white = fraction(json.opt("whiteLevel"))?.takeIf { it > 0 } ?: 0.0
        fun flatten(rows: org.json.JSONArray?): DoubleArray? {
            if (rows == null || rows.length() != 3) return null
            val values = DoubleArray(9)
            for (row in 0 until 3) {
                val cells = numbers(rows.optJSONArray(row), 3) ?: return null
                System.arraycopy(cells, 0, values, row * 3, 3)
            }
            return values
        }
        // ForwardMatrix1 already expects white-balanced camera values and maps to XYZ D50.
        val forward = flatten(json.optJSONArray("forwardMatrix1"))
        val matrix = forward ?: flatten(json.optJSONArray("colorTransform1"))
        ClipMetadata(cfa, orientation, white, matrix, if (forward != null) 2 else if (matrix != null) 1 else 0)
    } catch (_: Exception) { null }

    private fun frameTimestamps(report: JSONObject): List<Long> {
        val frames = report.optJSONArray("frames") ?: return emptyList()
        val out = mutableListOf<Long>()
        for (i in 0 until frames.length()) {
            val frame = frames.optJSONObject(i) ?: continue
            if (!frame.optBoolean("accepted", true)) continue
            val timestamp = frame.optLong("timestampNs", 0)
            if (timestamp > 0) out.add(timestamp)
        }
        return out
    }

    private fun frameCalibration(report: JSONObject): List<FrameCalibration> {
        val frames = report.optJSONArray("frames") ?: return emptyList()
        val out = mutableListOf<FrameCalibration>()
        for (i in 0 until frames.length()) {
            val frame = frames.optJSONObject(i) ?: continue
            if (!frame.optBoolean("accepted", true)) continue
            val neutral = numbers(frame.optJSONArray("neutralColorPoint"), 3)
            if (neutral == null || neutral.any { it <= 0 }) continue
            val black = numbers(frame.optJSONArray("dynamicBlackLevel"), 4) ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            val white = fraction(frame.opt("dynamicWhiteLevel"))?.takeIf { it > black.max() }
                ?: black.max() + 65535.0
            out.add(FrameCalibration(neutral[0], neutral[1], neutral[2], black, white))
        }
        return out
    }
    fun parse(name: String, text: String): SavedClip {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) { "Report খুব বড়" }
        val match = requireNotNull(reportPattern.matchEntire(name)) { "Capture filename সঠিক নয়" }
        val id = match.groupValues[1]
        val epoch = match.groupValues[2].toLongOrNull() ?: error("Capture timestamp সঠিক নয়")
        val report = JSONObject(text)
        require(report.getString("exportSessionId") == id) { "Capture prefix মেলে না" }
        require(report.getString("exportLayout") == "flat-unique-prefix") { "শুধু flat capture layout সমর্থিত" }
        require(report.getString("schema") == "cinecam-raw-test-2") { "Report schema সমর্থিত নয়" }
        val mapping = report.getJSONObject("exportFiles")
        require(mapping.length() in 1..64) { "File mapping সীমার বাইরে" }
        val files = mapping.keys().asSequence().map { original ->
            val exported = mapping.getString(original)
            require(exported == RawExportNames.name(id, original)) { "Unsafe বা অন্য capture-এর file reference" }
            ClipFile(original, exported)
        }.sortedBy { it.original }.toList()
        require(files.any { it.original == "report.json" && it.exported == name }) { "Report mapping নেই" }
        fun flag(json: JSONObject, key: String): Boolean? = if (json.has(key) && !json.isNull(key)) json.getBoolean(key) else null
        val native = report.optJSONObject("native") ?: JSONObject()
        val written = native.optLong("framesWritten")
        val requested = report.optInt("requestedFrames")
        val rawBytes = report.optLong("fileBytes")
        val drops = native.optLong("framesDropped")
        require(written >= 0 && requested >= 0 && rawBytes >= 0 && drops >= 0) { "Negative capture counters" }
        val archive = report.optJSONObject("losslessArchive")
        val archiveBytes = archive?.optLong("archiveBytes")
        require(archiveBytes == null || archiveBytes >= 0)
        return SavedClip(id, epoch, written, requested, report.optString("reason"), rawBytes,
            archiveBytes, drops, flag(report, "readbackOk"), flag(report, "dngSequenceOk") ?: flag(report, "firstFrameDngOk"),
            archive?.let { flag(it, "ok") }, files, RawReadableReport.text(report), null, null,
            frameCalibration(report), report, frameTimestamps(report))
    }
}
