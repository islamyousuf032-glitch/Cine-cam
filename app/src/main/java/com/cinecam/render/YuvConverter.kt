package com.cinecam.render

/** Encoder input layout we can write without a GL pipeline. */
internal enum class YuvLayout { I420, NV12 }

internal class YuvFrame(val width: Int, val height: Int, val layout: YuvLayout, val data: ByteArray)

/**
 * ARGB -> limited-range BT.601 YUV 4:2:0. Pure Kotlin so JVM tests can pin exact values;
 * 8 frames at preview resolution is small, so no full-frame raw buffer is involved.
 */
internal object YuvConverter {
    fun convert(pixels: IntArray, width: Int, height: Int, layout: YuvLayout): YuvFrame {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "YUV মাপ জোড় সংখ্যা হতে হবে" }
        require(pixels.size == width * height) { "pixel সংখ্যা মাপের সঙ্গে মেলে না" }
        val frame = width * height
        val chroma = frame / 4
        val y = ByteArray(frame)
        val u = ByteArray(chroma)
        val v = ByteArray(chroma)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val argb = pixels[row * width + col]
                val r = (argb shr 16) and 0xff
                val g = (argb shr 8) and 0xff
                val b = argb and 0xff
                val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                y[row * width + col] = luma.coerceIn(16, 235).toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    val cb = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val cr = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    u[(row / 2) * (width / 2) + col / 2] = cb.coerceIn(16, 240).toByte()
                    v[(row / 2) * (width / 2) + col / 2] = cr.coerceIn(16, 240).toByte()
                }
            }
        }
        val data = when (layout) {
            YuvLayout.I420 -> y + u + v
            YuvLayout.NV12 -> {
                val out = ByteArray(frame + chroma * 2)
                System.arraycopy(y, 0, out, 0, frame)
                for (i in 0 until chroma) {
                    out[frame + i * 2] = u[i]
                    out[frame + i * 2 + 1] = v[i]
                }
                out
            }
        }
        return YuvFrame(width, height, layout, data)
    }
}
