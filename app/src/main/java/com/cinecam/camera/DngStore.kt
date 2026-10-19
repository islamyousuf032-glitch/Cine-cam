package com.cinecam.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Invoked only by the single storage worker, never on the UI/camera callback thread. */
internal object DngStore {
    fun save(context: Context, chars: CameraCharacteristics, result: TotalCaptureResult,
             image: Image, orientation: Int): String {
        val name = "CineCam_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.dng"
        DngCreator(chars, result).use { creator ->
            creator.setOrientation(orientation)
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CineCam")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert ব্যর্থ")
                try {
                    resolver.openOutputStream(uri, "w")?.use { creator.writeImage(it, image) }
                        ?: error("DNG output খোলা যায়নি")
                    check(resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }, null, null) == 1) { "DNG publish ব্যর্থ" }
                    return "DCIM/CineCam/$name"
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                // App-specific storage needs no legacy storage permission.
                val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: error("External storage পাওয়া যায়নি"), "CineCam")
                check(directory.isDirectory || directory.mkdirs()) { "DNG folder তৈরি হয়নি" }
                val file = File(directory, name)
                try { file.outputStream().use { creator.writeImage(it, image) } }
                catch (e: Exception) { file.delete(); throw e }
                return file.absolutePath
            }
        }
    }
}
