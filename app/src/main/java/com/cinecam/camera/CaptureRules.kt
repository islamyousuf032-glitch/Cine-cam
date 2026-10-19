package com.cinecam.camera

import kotlin.math.exp
import kotlin.math.ln

internal object CaptureRules {
    fun exposureAt(position: Float, minimum: Long, maximum: Long): Long {
        require(minimum > 0 && maximum >= minimum)
        return exp(ln(minimum.toDouble()) + position.coerceIn(0f, 1f) *
            (ln(maximum.toDouble()) - ln(minimum.toDouble()))).toLong().coerceIn(minimum, maximum)
    }
    fun exposurePosition(value: Long, minimum: Long, maximum: Long): Float {
        if (maximum <= minimum) return 0f
        return ((ln(value.coerceIn(minimum, maximum).toDouble()) - ln(minimum.toDouble())) /
            (ln(maximum.toDouble()) - ln(minimum.toDouble()))).toFloat().coerceIn(0f, 1f)
    }
    fun exifOrientation(sensor: Int, display: Int, front: Boolean): Int =
        when (CameraRules.relativeRotation(sensor, display, front)) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }
}
