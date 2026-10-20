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
        writeFrame(folder, verified, verified.frames.first(), chars, result, orientation, "first_frame.dng")
    }

    fun writeFrame(folder: File, verified: CcrawVerifier.Verified, frame: CcrawVerifier.Frame,
                   chars: CameraCharacteristics, result: TotalCaptureResult, orientation: Int, name: String) {
        require(name == "first_frame.dng" || name.matches(Regex("frame_[0-9]{6}\\.dng")))
        check(result.frameNumber == frame.sourceFrameNumber &&
            result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) == frame.timestampNs) { "Saved frame metadata mismatch" }
        val output = File(folder, name)
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
