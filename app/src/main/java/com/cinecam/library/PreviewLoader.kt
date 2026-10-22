package com.cinecam.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import com.cinecam.engine.NativeRawEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One still frame: preferred DNG, plus our verified CCRAW payload as fallback. */
internal data class PreviewTarget(
    val dng: Uri?, val ccraw: Uri?, val frameIndex: Int, val orientation: Int
)

/** Bounded native previews; no full-frame Java RAW buffer and no DNG modification. */
internal object PreviewLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(context: Context, target: PreviewTarget, maxDimension: Int): Bitmap =
        withContext(Dispatchers.IO) {
            val key = "${target.dng}|${target.ccraw}|${target.frameIndex}@$maxDimension"
            cache.get(key)?.let { return@withContext it }
            val engine = NativeRawEngine.getInstance()
            var firstError: String? = null
            val dng = target.dng
            if (dng != null) {
                try {
                    return@withContext decode(context, dng, key) { fd -> engine.nativeRenderDng(fd, maxDimension) }
                } catch (e: Exception) {
                    firstError = e.message
                }
            }
            val ccraw = target.ccraw
            if (ccraw != null) {
                try {
                    return@withContext decode(context, ccraw, key) { fd ->
                        engine.nativeRenderCcrawFrame(fd, target.frameIndex, maxDimension, target.orientation)
                    }
                } catch (e: Exception) {
                    firstError = listOfNotNull(firstError, e.message).joinToString(" • ")
                }
            }
            throw IllegalStateException(firstError ?: "DNG এবং RAW container পাওয়া যায়নি")
        }

    private fun decode(context: Context, uri: Uri, key: String,
                       render: (Int) -> IntArray?): Bitmap {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("ফাইল খোলা যায়নি")
        val header = descriptor.use { render(it.fd) }
            ?: throw IllegalStateException("Preview তৈরি করা যায়নি")
        if (header.size < 4) throw IllegalStateException("Preview data সঠিক নয়")
        val width = header[0]
        val height = header[1]
        if (header[3] != 0 || width <= 0 || height <= 0 || width.toLong() * height + 4 != header.size.toLong()) {
            val reason = NativeRawEngine.getInstance().nativePreviewError().ifBlank { "এই DNG-এর layout সমর্থিত নয়" }
            throw IllegalStateException(reason)
        }
        val pixels = IntArray(width * height)
        System.arraycopy(header, 4, pixels, 0, pixels.size)
        var bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val degrees = when (header[2]) { 3 -> 180; 6 -> 90; 8 -> 270; else -> 0 }
        if (degrees != 0) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, false)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        cache.put(key, bitmap)
        return bitmap
    }
}
