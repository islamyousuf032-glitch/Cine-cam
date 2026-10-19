package com.cinecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface

/**
 * CameraController - Low-level Camera2 wrapper for CineCam
 *
 * Responsibilities:
 * - Open / close CameraDevice
 * - Create preview + RAW capture sessions
 * - Apply manual controls (ISO, exposure, focus, WB)
 * - Deliver RAW ImageReader frames to NDK engine
 */
class CameraController(
    private val context: Context,
    private val onRawFrame: (android.media.Image) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var previewSurface: Surface? = null
    private var rawImageReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    var currentCameraId: String? = null
        private set

    // Manual controls state
    var manualIso: Int? = null
    var manualExposureNs: Long? = null
    var manualFocusDistance: Float? = null
    var manualWhiteBalanceK: Int = 5500

    companion object {
        private const val TAG = "CameraController"
        const val RAW_FORMAT = 0x20 // ImageFormat.RAW_SENSOR
    }

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CineCamCameraBG").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        Log.i(TAG, "Background thread started")
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Stop BG thread interrupted", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String, previewSurface: Surface) {
        this.currentCameraId = cameraId
        this.previewSurface = previewSurface

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(cameraId)

        // Check max RAW size to size ImageReader
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = try { map?.getOutputSizes(RAW_FORMAT) } catch (_: Exception) { null }
        val bestRawSize = rawSizes?.maxByOrNull { it.width * it.height }

        Log.i(TAG, "Opening camera $cameraId, best RAW size: $bestRawSize")

        // RAW ImageReader: 4 buffers for pipeline (double-buffer + ring)
        if (bestRawSize != null) {
            rawImageReader = ImageReader.newInstance(bestRawSize.width, bestRawSize.height, RAW_FORMAT, 4).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage()
                    if (image != null) {
                        try { onRawFrame(image) } catch (e: Exception) {
                            Log.e(TAG, "onRawFrame error", e)
                            image.close()
                        }
                    }
                }, backgroundHandler)
            }
        } else {
            Log.w(TAG, "RAW_SENSOR not supported for $cameraId, fallback: no RAW reader")
        }

        try {
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed", e)
        }
    }

    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            rawImageReader?.close()
            rawImageReader = null
            Log.i(TAG, "Camera closed")
        } catch (e: Exception) {
            Log.e(TAG, "closeCamera error", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            Log.i(TAG, "Camera opened: ${camera.id}")
            createCaptureSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected: ${camera.id}")
            camera.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error $error for ${camera.id}")
            camera.close()
            cameraDevice = null
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return

        val targets = mutableListOf<Surface>(preview)
        rawImageReader?.surface?.let { targets.add(it) }

        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                // RAW target is added but we use separate capture for RAW bursts; preview keeps running
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                applyManualControls(this)
            }

            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(previewRequestBuilder!!.build(), captureCallback, backgroundHandler)
                        Log.i(TAG, "Preview session configured")
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "setRepeatingRequest failed", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Session configure failed")
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession failed", e)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            // Can extract per-frame metadata here for logging / DNG tags
        }
    }

    /**
     * Apply manual ISO, exposure, focus, WB to builder
     */
    private fun applyManualControls(builder: CaptureRequest.Builder) {
        val iso = manualIso
        val exp = manualExposureNs
        val focus = manualFocusDistance

        if (iso != null || exp != null) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            exp?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        }

        focus?.let {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }

        // White balance: map Kelvin 2500-9000 to CONTROL_AWB_MODE + manual gains in Phase 3
        // For now use AUTO; manual WB gains require SENSOR calibration
    }

    /**
     * Trigger single RAW DNG capture (still)
     */
    fun captureRawDng() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val readerSurface = rawImageReader?.surface ?: run {
            Log.w(TAG, "No RAW reader, cannot capture DNG")
            return
        }

        try {
            val stillBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                // Copy preview's manual settings
                previewRequestBuilder?.let { preview ->
                    // Transfer relevant tags
                }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }
            session.capture(stillBuilder.build(), object : CameraCaptureSession.CaptureCallback() {}, backgroundHandler)
            Log.i(TAG, "RAW DNG capture triggered")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "captureRawDng failed", e)
        }
    }

    /**
     * Update manual controls live
     */
    fun setManualExposure(iso: Int?, exposureNs: Long?) {
        manualIso = iso
        manualExposureNs = exposureNs
        refreshPreview()
    }

    fun setManualFocus(distance: Float?) {
        manualFocusDistance = distance
        refreshPreview()
    }

    fun setFpsRange(range: Range<Int>) {
        previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        refreshPreview()
    }

    private fun refreshPreview() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            applyManualControls(builder)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "refreshPreview failed", e)
        }
    }
}
