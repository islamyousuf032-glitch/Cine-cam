package com.cinecam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Range

/**
 * CameraCapabilities - Device RAW capability checker
 * Determines if device supports RAW_SENSOR, RAW10, RAW12 and manual controls
 *
 * This is critical for UX: unsupported devices show warning + fallback to YUV
 */
data class RawSupportInfo(
    val cameraId: String,
    val facing: Int, // LENS_FACING
    val hardwareLevel: Int,
    val supportsRawSensor: Boolean,
    val supportsRaw10: Boolean,
    val supportsRaw12: Boolean,
    val supportsFullManual: Boolean,
    val supportsManualSensor: Boolean,
    val maxRawSize: android.util.Size?,
    val availableFpsRanges: List<Range<Int>>,
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?,
    val availableApertures: FloatArray?,
    val focalLengths: FloatArray?,
    val model: String
) {
    val isCinemaReady: Boolean
        get() = supportsRawSensor && hardwareLevel >= CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL

    fun hardwareLevelString(): String = when (hardwareLevel) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($hardwareLevel)"
    }
}

object CameraCapabilities {

    private const val TAG = "CameraCaps"

    /**
     * Scan all cameras and return RAW support per camera
     */
    fun checkAllCameras(context: Context): List<RawSupportInfo> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.mapNotNull { id ->
            try {
                checkCamera(manager, id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check camera $id", e)
                null
            }
        }
    }

    fun checkCamera(manager: CameraManager, cameraId: String): RawSupportInfo {
        val chars = manager.getCameraCharacteristics(cameraId)

        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
        val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val supportsRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val supportsManualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val supportsManualPost = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)

        // Stream configurations
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        var maxRawSize: android.util.Size? = null
        var supportsRaw10 = false
        var supportsRaw12 = false

        map?.let {
            // RAW_SENSOR = 0x20 (32), RAW10 = 0x25 (37), RAW12 = 0x26 (38)
            // Use reflection-safe ints to avoid API level issues
            try {
                val rawSizes = it.getOutputSizes(32) // RAW_SENSOR
                maxRawSize = rawSizes?.maxByOrNull { s -> s.width * s.height }
                supportsRaw10 = it.getOutputSizes(37) != null
                supportsRaw12 = it.getOutputSizes(38) != null
            } catch (_: Exception) {
                Log.w(TAG, "RAW format query failed for $cameraId")
            }
        }

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList() ?: emptyList()

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

        val model = "${Build.MANUFACTURER} ${Build.MODEL} (cam $cameraId)"

        Log.i(TAG, "Camera $cameraId: level=${levelString(level)} RAW=$supportsRaw FULL_MANUAL=${supportsManualSensor} maxRaw=$maxRawSize")

        return RawSupportInfo(
            cameraId = cameraId,
            facing = facing,
            hardwareLevel = level,
            supportsRawSensor = supportsRaw,
            supportsRaw10 = supportsRaw10,
            supportsRaw12 = supportsRaw12,
            supportsFullManual = supportsManualSensor && supportsManualPost,
            supportsManualSensor = supportsManualSensor,
            maxRawSize = maxRawSize,
            availableFpsRanges = fpsRanges,
            isoRange = isoRange,
            exposureTimeRange = exposureRange,
            availableApertures = apertures,
            focalLengths = focalLengths,
            model = model
        )
    }

    private fun levelString(level: Int): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        else -> "UNKNOWN"
    }

    /**
     * Pick best back camera for RAW video
     */
    fun pickBestRawCamera(infos: List<RawSupportInfo>): RawSupportInfo? {
        // Prefer back-facing FULL/LEVEL_3 with RAW
        return infos.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .sortedWith(compareByDescending<RawSupportInfo> { it.supportsRawSensor }
                .thenByDescending { it.hardwareLevel }
                .thenByDescending { it.maxRawSize?.let { s -> s.width * s.height } ?: 0 })
            .firstOrNull()
            ?: infos.firstOrNull()
    }
}
