package com.cinecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Where a verified CCRAW is read from and where DNG frames go. A staging folder and a MediaStore
 * pending row behave the same, so a direct session writes each DNG once (no export copy).
 */
internal interface ClipDngTarget {
    fun openSource(): InputStream
    fun openOutput(name: String): OutputStream
    fun delete(name: String)
}

internal class FolderDngTarget(private val folder: File) : ClipDngTarget {
    override fun openSource(): InputStream = File(folder, "capture.ccraw").inputStream()
    override fun openOutput(name: String): OutputStream = File(folder, name).outputStream()
    override fun delete(name: String) { File(folder, name).delete() }
}

internal object ClipDngSample {
    /** 1 MiB buffers; the DNG payload itself is unchanged. */
    private const val BUFFER_BYTES = 1024 * 1024
    /** Uses the verified first SAVED payload, not a newly captured Image. Platform calibration path. */
    fun write(target: ClipDngTarget, verified: CcrawVerifier.Verified, chars: CameraCharacteristics,
              result: TotalCaptureResult, orientation: Int) {
        writeFrame(target, verified, verified.frames.first(), chars, result, orientation, "first_frame.dng")
    }

    fun writeFrame(target: ClipDngTarget, verified: CcrawVerifier.Verified, frame: CcrawVerifier.Frame,
                   chars: CameraCharacteristics, result: TotalCaptureResult, orientation: Int, name: String) {
        require(name == "first_frame.dng" || name.matches(Regex("frame_[0-9]{6}\\.dng")))
        check(result.frameNumber == frame.sourceFrameNumber &&
            result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) == frame.timestampNs) { "Saved frame metadata mismatch" }
        try {
            DngCreator(chars, result).use { creator ->
                creator.setOrientation(orientation)
                // Large buffers: DngCreator copies ~25 MB per frame and the default 8 KiB
                // streams added measurable overhead to a 40-frame sequence.
                BufferedInputStream(target.openSource(), BUFFER_BYTES).use { input ->
                    BufferedOutputStream(target.openOutput(name), BUFFER_BYTES).use { out ->
                        creator.writeInputStream(out, Size(verified.width, verified.height), input, frame.payloadOffset)
                    }
                }
            }
        } catch (e: Exception) { target.delete(name); throw e }
    }
}
