package com.cinecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import java.io.File

internal object ClipDngSample {
    /** Uses the verified first SAVED payload, not a newly captured Image. Platform calibration path. */
    fun write(folder: File, verified: CcrawVerifier.Verified, chars: CameraCharacteristics,
              result: TotalCaptureResult, orientation: Int) {
        val frame = verified.frames.first()
        check(result.frameNumber == frame.sourceFrameNumber &&
            result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) == frame.timestampNs) { "Saved frame metadata mismatch" }
        val output = File(folder, "first_frame.dng")
        try {
            DngCreator(chars, result).use { creator ->
                creator.setOrientation(orientation)
                File(folder, "capture.ccraw").inputStream().use { input ->
                    output.outputStream().use { out ->
                        creator.writeInputStream(out, Size(verified.width, verified.height), input, frame.payloadOffset)
                    }
                }
            }
        } catch (e: Exception) { output.delete(); throw e }
    }
}
