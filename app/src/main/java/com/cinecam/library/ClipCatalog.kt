package com.cinecam.library

import com.cinecam.camera.RawExportNames
import com.cinecam.camera.RawReadableReport
import org.json.JSONObject

/** Untrusted saved reports are an index, NOT independent payload verification. */
internal data class ClipFile(val original: String, val exported: String)
internal data class SavedClip(
    val id: String, val epochMs: Long, val written: Long, val requested: Int,
    val reason: String, val rawBytes: Long, val archiveBytes: Long?, val drops: Long,
    val readback: Boolean?, val dng: Boolean?, val archive: Boolean?,
    val files: List<ClipFile>, val readable: String
)
internal object ClipCatalog {
    const val MAX_REPORT_BYTES = 65536
    const val MAX_DOCUMENTS = 5000
    const val MAX_REPORTS = 100
    private val reportPattern = Regex("(CineCam_([0-9]+)_[a-f0-9]{8})_report\\.json")
    fun isReport(name: String) = reportPattern.matches(name)
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
            archive?.let { flag(it, "ok") }, files, RawReadableReport.text(report))
    }
}
