package com.cinecam.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import com.cinecam.engine.NativeRawEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One still frame: preferred DNG, plus our verified CCRAW payload as fallback. */
internal data class PreviewTarget(
    val dng: Uri?, val ccraw: Uri?, val frameIndex: Int, val orientation: Int,
    val cfa: Int = 0, val neutral: DoubleArray? = null, val white: Double = 0.0,
    val black: DoubleArray? = null, val matrix: DoubleArray? = null, val matrixMode: Int = 0
)

/** Bounded native previews; no full-frame Java RAW buffer and no source modification. */
internal object PreviewLoader {
    // Two decoders at a time keep large phones responsive; the old unbounded fan-out froze the UI.
    private val permits = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(context: Context, target: PreviewTarget, maxDimension: Int): Bitmap =
        withContext(Dispatchers.IO) {
            val key = PreviewCacheKey.of(target.dng?.toString(), target.ccraw?.toString(),
                target.frameIndex, maxDimension, target.cfa, target.matrixMode, target.white)
            cache.get(key)?.let { return@withContext it }
            permits.withPermit {
                cache.get(key)?.let { return@withPermit it }
                val bitmap = decode(context, target, maxDimension)
                cache.put(key, bitmap)
                bitmap
            }
        }

    private fun decode(context: Context, target: PreviewTarget, maxDimension: Int): Bitmap {
        val engine = NativeRawEngine.getInstance()
        var errors = mutableListOf<String>()
        val dng = target.dng
        if (dng != null) {
            try {
                return render(context, dng) { fd -> engine.nativeRenderDng(fd, maxDimension) }
            } catch (e: Exception) {
                errors.add(e.message ?: "DNG preview ব্যর্থ")
            }
        }
        val ccraw = target.ccraw
        if (ccraw != null) {
            try {
                return render(context, ccraw) { fd ->
                    engine.nativeRenderCcrawFrame(fd, target.frameIndex, maxDimension, target.orientation,
                        target.cfa, target.neutral?.get(0) ?: 0.0, target.neutral?.get(1) ?: 0.0,
                        target.neutral?.get(2) ?: 0.0, target.white, target.black, target.matrix,
                        target.matrixMode)
                }
            } catch (e: Exception) {
                errors.add(e.message ?: "RAW frame preview ব্যর্থ")
            }
        }
        throw IllegalStateException(errors.filter { it.isNotBlank() }.distinct().joinToString(" • ")
            .ifBlank { "DNG এবং RAW container পাওয়া যায়নি" })
    }

    private fun render(context: Context, uri: Uri, block: (Int) -> IntArray?): Bitmap {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("ফাইল খোলা যায়নি")
        val header = descriptor.use { block(it.fd) }
            ?: throw IllegalStateException("Preview তৈরি করা যায়নি")
        if (header.size < 4) throw IllegalStateException("Preview data সঠিক নয়")
        val width = header[0]
        val height = header[1]
        if (header[3] != 0 || width <= 0 || height <= 0 || width.toLong() * height + 4 != header.size.toLong()) {
            val reason = NativeRawEngine.getInstance().nativePreviewError()
            throw IllegalStateException(reason.ifBlank { "এই ফাইলের layout সমর্থিত নয়" })
        }
        val pixels = IntArray(width * height)
        System.arraycopy(header, 4, pixels, 0, pixels.size)
        var bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val degrees = when (header[2]) { 3 -> 180; 6 -> 90; 8 -> 270; else -> 0 }
        if (degrees != 0) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height,
                Matrix().apply { postRotate(degrees.toFloat()) }, false)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }
}
