package com.cinecam.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object RawClipStore {
    fun prepare(context: Context, chars: CameraCharacteristics, width: Int, height: Int, exifOrientation: Int, mode: String, crcBackend: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: error("Storage unavailable")
        check(StatFs(root.absolutePath).availableBytes >= 650L * 1024 * 1024) { "RAW test-এর জন্য অন্তত 650 MiB ফাঁকা জায়গা প্রয়োজন" }
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
                put("warning", "Experimental bounded RAW burst; no audio, compression, fixed FPS or MotionCam compatibility")
            }
            File(folder, "metadata.json").writeText(metadata.toString(2))
            return folder
        } catch (e: Exception) { folder.deleteRecursively(); throw e }
    }

    // Called on the single storage worker after native stop/drain. No 200MB byte arrays.
    fun export(context: Context, folder: File, report: JSONObject): String {
        report.put("exportFolder", if (Build.VERSION.SDK_INT >= 29) "Download/CineCam/${folder.name}" else folder.absolutePath)
        File(folder, "report.json").writeText(report.toString(2))
        if (Build.VERSION.SDK_INT < 29) return folder.absolutePath
        val resolver = context.contentResolver
        val inserted = mutableListOf<android.net.Uri>()
        try {
            val files = folder.listFiles()?.filter { it.isFile } ?: error("Clip files পাওয়া যায়নি")
            for (file in files) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, if (file.extension == "json") "application/json" else "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/CineCam/${folder.name}")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Download entry তৈরি হয়নি")
                inserted.add(uri)
                resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output, 256 * 1024) } }
                    ?: error("Download output খোলা যায়নি")
            }
            inserted.forEach { uri ->
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            }
            folder.deleteRecursively() // Export succeeded; do not retain duplicate 200MB payload.
            return "Download/CineCam/${folder.name}"
        } catch (e: Exception) {
            inserted.forEach { uri -> try { resolver.delete(uri, null, null) } catch (_: Exception) {} }
            throw IllegalStateException("Export ব্যর্থ; temporary data রাখা আছে: ${folder.absolutePath}: ${e.message}", e)
        }
    }
}
