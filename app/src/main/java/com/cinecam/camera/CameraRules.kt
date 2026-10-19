package com.cinecam.camera

/** Camera2 hardware levels are enum values, not an ordered capability scale. */
internal object CameraRules {
    // CameraMetadata: FULL=1, LEGACY=2, LEVEL_3=3, EXTERNAL=4.
    fun cinemaReady(raw: Boolean, manual: Boolean, hardwareLevel: Int): Boolean =
        raw && manual && hardwareLevel in setOf(1, 3)

    fun relativeRotation(sensor: Int, display: Int, front: Boolean): Int =
        ((if (front) sensor + display else sensor - display) % 360 + 360) % 360
}
