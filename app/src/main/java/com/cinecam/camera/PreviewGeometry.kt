package com.cinecam.camera

import kotlin.math.cos
import kotlin.math.sin

/**
 * TextureView's camera producer transform already orients the sensor buffer for the
 * device's natural orientation. Do not apply SENSOR_ORIENTATION a second time.
 * Sensor orientation determines the natural content dimensions, display rotation
 * determines the additional counter-rotation. Saved RAW/EXIF uses a different path.
 */
internal object PreviewGeometry {
    fun corners(viewWidth: Int, viewHeight: Int, bufferWidth: Int, bufferHeight: Int,
                sensorOrientation: Int, displayDegrees: Int, mirror: Boolean): FloatArray {
        require(viewWidth > 0 && viewHeight > 0 && bufferWidth > 0 && bufferHeight > 0)
        require(sensorOrientation in listOf(0, 90, 180, 270))
        require(displayDegrees in listOf(0, 90, 180, 270))
        val naturalWidth = if (sensorOrientation % 180 == 0) bufferWidth else bufferHeight
        val naturalHeight = if (sensorOrientation % 180 == 0) bufferHeight else bufferWidth
        val outputWidth = if (displayDegrees % 180 == 0) naturalWidth else naturalHeight
        val outputHeight = if (displayDegrees % 180 == 0) naturalHeight else naturalWidth
        val scale = minOf(viewWidth.toFloat() / outputWidth, viewHeight.toFloat() / outputHeight)
        val angle = Math.toRadians(-displayDegrees.toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val source = floatArrayOf(-naturalWidth / 2f, -naturalHeight / 2f,
            naturalWidth / 2f, -naturalHeight / 2f, naturalWidth / 2f, naturalHeight / 2f,
            -naturalWidth / 2f, naturalHeight / 2f)
        return FloatArray(8).apply {
            for (i in 0..3) {
                val x = source[i * 2] * c - source[i * 2 + 1] * s
                val y = source[i * 2] * s + source[i * 2 + 1] * c
                this[i * 2] = viewWidth / 2f + x * scale * (if (mirror) -1f else 1f)
                this[i * 2 + 1] = viewHeight / 2f + y * scale
            }
        }
    }
}
