package com.cinecam.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.ParcelFileDescriptor
import com.cinecam.engine.NativeRawEngine
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object RawClipStore {
    data class PreparedClip(val folder: File, val stagingLocation: String,
                            val captureAvailableBytes: Long, val exportAvailableBytes: Long)
    fun prepare(context: Context, chars: CameraCharacteristics, width: Int, height: Int, exifOrientation: Int, mode: String, crcBackend: String, internalStaging: Boolean, sequence: Boolean, archive: Boolean): PreparedClip {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: error("Export storage unavailable")
        val useInternal = RawStoragePolicy.useInternal(internalStaging, Build.VERSION.SDK_INT)
        val root = if (useInternal) File(context.filesDir, "raw-clips") else external
        check(root.isDirectory || root.mkdirs()) { "Capture staging folder তৈরি হয়নি" }
        val captureFree = StatFs(root.absolutePath).availableBytes
        val exportFree = StatFs(external.absolutePath).availableBytes
        RawStoragePolicy.checkFreeSpace(captureFree, exportFree, sequence, archive)
        val location = if (useInternal) "app-private-internal" else "app-specific-external"
        val folder = File(root, "CineCam_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
        check(folder.mkdirs()) { "RAW clip folder তৈরি হয়নি" }
        try {
            val black = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let {
                val values = IntArray(4); it.copyTo(values, 0); JSONArray(values.toList())
            }
            fun matrix(value: ColorSpaceTransform?): Any = value?.let {
                JSONArray((0..2).map { row -> JSONArray((0..2).map { column -> it.getElement(column, row).toString() }) })
            } ?: JSONObject.NULL
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val metadata = JSONObject().apply {
                put("schema", "cinecam-calibration-1")
                put("losslessArchiveRequested", archive)
                put("dngExportMode", if (sequence) "all-saved-frames" else "first-frame-or-off")
                put("stagingLocation", location)
                put("captureAvailableBytesAtPrepare", captureFree); put("exportAvailableBytesAtPrepare", exportFree)
                put("captureMode", mode); put("poolSlots", 3); put("crcBackend", crcBackend)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("androidApi", Build.VERSION.SDK_INT)
                put("width", width); put("height", height); put("storageBits", 16)
                put("sensorPrecision", "not inferred from storageBits")
                put("timestampClock", "BOOTTIME")
                put("sensorOrientation", chars.get(CameraCharacteristics.SENSOR_ORIENTATION))
                put("exifOrientationAtStart", exifOrientation)
                put("cfa", chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
                put("whiteLevel", chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
                put("blackLevel2x2", black ?: JSONObject.NULL)
                put("activeArray", active?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
                put("colorTransform1", matrix(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)))
                put("colorTransform2", matrix(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)))
                put("calibrationTransform1", matrix(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)))
                put("calibrationTransform2", matrix(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)))
                put("forwardMatrix1", matrix(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)))
                put("forwardMatrix2", matrix(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)))
                put("referenceIlluminant1", chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
                put("referenceIlluminant2", chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2))
                put("warning", "Experimental bounded RAW burst; optional post-capture archive only, no compressed recording, audio, fixed FPS or MotionCam compatibility")
            }
            File(folder, "metadata.json").writeText(metadata.toString(2))
            return PreparedClip(folder, location, captureFree, exportFree)
        } catch (e: Exception) { folder.deleteRecursively(); throw e }
    }

    // Unique staging directories stay private; user-visible export is a single flat directory.
    fun export(context: Context, folder: File, report: JSONObject, onProgress: (String) -> Unit = {}): String {
        val session = folder.name
        val legacyRoot = File(folder.parentFile, "CineCam")
        val destination = if (Build.VERSION.SDK_INT >= 29) "Download/CineCam" else legacyRoot.absolutePath
        val files = folder.listFiles()?.filter { it.isFile && it.name !in setOf("report.json", "report.txt") &&
            (it.name != "capture.ccraw.bp16" || (report.optJSONObject("losslessArchive")?.optBoolean("ok") == true &&
                report.optJSONObject("losslessArchive")?.optBoolean("byteExactSourceComparison") == true)) }
            ?: error("Clip files পাওয়া যায়নি")
        val mapping = JSONObject()
        (files.map { it.name } + listOf("report.json", "report.txt")).forEach {
            mapping.put(it, RawExportNames.name(session, it))
        }
        report.put("exportFolder", destination); report.put("exportSessionId", session)
        report.put("exportLayout", "flat-unique-prefix"); report.put("exportFiles", mapping)
        // Actual exported sequence references MUST match prefixed DNG basenames.
        val sequence = File(folder, "sequence.json")
        if (sequence.exists()) {
            val manifest = JSONObject(sequence.readText())
            val entries = manifest.getJSONArray("frames")
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val current = entry.getString("file")
                entry.put("file", RawExportNames.sequenceReference(session, current, files.map { it.name }.toSet()))
            }
            manifest.put("exportSessionId", session)
            sequence.writeText(manifest.toString(2))
        }
        fun writeReports() {
            File(folder, "report.json").writeText(report.toString(2))
            File(folder, "report.txt").writeText(RawReadableReport.text(report))
        }
        writeReports() // Retain a readable diagnostic even if export subsequently fails.
        val inserted = mutableListOf<android.net.Uri>()
        val legacyCreated = mutableListOf<File>()
        val copyEngine = NativeRawEngine.getInstance()
        val copyDetails = JSONArray()
        report.put("exportCopyBackend", "native-sendfile-with-bounded-fallback-v1")
        report.put("exportCopyDetails", copyDetails)
        fun copyFd(file: File, descriptor: ParcelFileDescriptor) {
            val detail = JSONObject(copyEngine.nativeCopyExport(file.absolutePath, descriptor.fd))
            detail.put("file", mapping.getString(file.name))
            // Reports copied last; keep their timing out of their own serialized snapshot.
            if (file.name !in setOf("report.json", "report.txt")) copyDetails.put(detail)
            check(detail.optBoolean("ok") && detail.optLong("bytes") == file.length()) {
                "Export copy ব্যর্থ: ${file.name}: ${detail.optString("error")}" }
        }
        try {
            if (Build.VERSION.SDK_INT < 29) check(legacyRoot.isDirectory || legacyRoot.mkdirs()) { "Export folder তৈরি হয়নি" }
            fun copyPending(file: File) {
                val outputName = mapping.getString(file.name)
                if (Build.VERSION.SDK_INT < 29) {
                    val output = File(legacyRoot, outputName)
                    check(output.createNewFile()) { "Export destination already exists" }
                    legacyCreated.add(output)
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_WRITE_ONLY).use { copyFd(file, it) }
                    return
                }
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outputName)
                    put(MediaStore.Downloads.MIME_TYPE, when (file.extension) {
                        "json" -> "application/json"; "txt" -> "text/plain"; "dng" -> "image/x-adobe-dng"; else -> "application/octet-stream"
                    })
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/CineCam")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Download entry তৈরি হয়নি")
                inserted.add(uri)
                // A provider must not silently rename a companion file or sequence references break.
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == outputName) { "Export name collision; source retained" }
                } ?: error("Export name যাচাই হয়নি")
                resolver.openFileDescriptor(uri, "w")?.use { copyFd(file, it) }
                    ?: error("Download output descriptor খোলা যায়নি")
            }
            val copyBegin = SystemClock.elapsedRealtimeNanos()
            var dataBytes = 0L
            files.forEachIndexed { index, file ->
                onProgress("Export ${index + 1}/${files.size}: ${file.name}…")
                copyPending(file); dataBytes += file.length()
            }
            report.put("exportDataCopyNs", SystemClock.elapsedRealtimeNanos() - copyBegin)
            report.put("exportDataBytes", dataBytes)
            writeReports()
            copyPending(File(folder, "report.txt")); copyPending(File(folder, "report.json"))
            inserted.forEach { uri ->
                check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            }
            folder.deleteRecursively()
            return destination
        } catch (e: Exception) {
            inserted.forEach { uri -> try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { } }
            legacyCreated.forEach { it.delete() }
            report.put("exportError", e.message)
            try { writeReports() } catch (_: Exception) { }
            throw IllegalStateException("Export ব্যর্থ; temporary data রাখা আছে: ${folder.absolutePath}: ${e.message}", e)
        }
    }
}
