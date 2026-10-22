package com.cinecam.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import com.cinecam.engine.NativeRawEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded native previews of our own uncompressed CFA DNGs; no full-frame Java RAW buffer. */
internal object DngPreviewLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(context: Context, uri: Uri, maxDimension: Int): Bitmap = withContext(Dispatchers.IO) {
        val key = "$uri@$maxDimension"
        cache.get(key)?.let { return@withContext it }
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("DNG file খোলা যায়নি")
        val header = descriptor.use { NativeRawEngine.getInstance().nativeRenderDng(it.fd, maxDimension) }
            ?: throw IllegalStateException("DNG preview তৈরি করা যায়নি")
        require(header.size >= 4) { "DNG preview data সঠিক নয়" }
        val width = header[0]
        val height = header[1]
        require(header[3] == 0 && width > 0 && height > 0 && header.size == 4 + width * height) {
            "এই DNG-এর preview তৈরি করা যায়নি"
        }
        val pixels = IntArray(width * height)
        System.arraycopy(header, 4, pixels, 0, pixels.size)
        var bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val degrees = when (header[2]) { 3 -> 180; 6 -> 90; 8 -> 270; else -> 0 }
        if (degrees != 0) {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        cache.put(key, bitmap)
        bitmap
    }
}
