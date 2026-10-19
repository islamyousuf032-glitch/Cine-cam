package com.cinecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera/session state is handler-confined; one bounded still is owned by the storage worker. */
class CameraController(context: Context, private val onStatus: (String) -> Unit,
                       private val onCaptureState: (Boolean) -> Unit = {}) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("CineCamCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val storage = Executors.newSingleThreadExecutor()
    private val saving = AtomicBoolean(false)
    private var generation = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var reader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var controls = ManualSettings()
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var capturePending = false
    private var captureSerial = 0L
    private var captureOrientation = 1
    private var timeout: Runnable? = null
    @Volatile private var released = false

    @SuppressLint("MissingPermission")
    fun open(cameraId: String, texture: SurfaceTexture, size: Size) {
        if (released) return
        handler.post {
            closeInternal()
            val token = generation
            try {
                val chars = manager.getCameraCharacteristics(cameraId)
                characteristics = chars
                texture.setDefaultBufferSize(size.width, size.height)
                val target = Surface(texture)
                surface = target
                val rawSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
                val rawCap = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
                if (rawCap && rawSize != null) {
                    reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
                        setOnImageAvailableListener({ source ->
                            try {
                                val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                                if (token != generation || !capturePending) image.close()
                                else {
                                    pendingImage?.close()
                                    pendingImage = image
                                    matchAndSave(token)
                                }
                            } catch (e: Exception) { if (token == generation) cancelCapture("RAW image: ${e.message}") }
                        }, handler)
                    }
                }
                onStatus("ক্যামেরা খোলা হচ্ছে…")
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (token != generation || released) { camera.close(); return }
                        device = camera
                        createSession(camera, target, size, token, includeRaw = reader != null)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close(); fail(token, "ক্যামেরা সংযোগ বিচ্ছিন্ন; আবার চেষ্টা করুন")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close(); fail(token, "Camera2 error $error; আবার চেষ্টা করুন")
                    }
                }, handler)
            } catch (e: Exception) { fail(token, e.message) }
        }
    }

    private fun createSession(camera: CameraDevice, target: Surface, size: Size, token: Int, includeRaw: Boolean) {
        try {
            val targets = mutableListOf(target)
            if (includeRaw) reader?.surface?.let { targets.add(it) }
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (token != generation || released) { configured.close(); return }
                    session = configured
                    try {
                        repeatPreview()
                        onStatus("লাইভ প্রিভিউ • $size • DNG ${if (includeRaw) "প্রস্তুত" else "অনুপলব্ধ"}")
                    } catch (e: Exception) { fail(token, e.message) }
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    failed.close()
                    if (token != generation || released) return
                    if (includeRaw) {
                        retireReader()
                        onStatus("RAW session হয়নি; preview-only চেষ্টা হচ্ছে")
                        createSession(camera, target, size, token, false)
                    } else fail(token, "প্রিভিউ session তৈরি হয়নি")
                }
            }, handler)
        } catch (e: Exception) { fail(token, e.message) }
    }

    fun setManual(settings: ManualSettings) {
        if (released) return
        handler.post {
            controls = settings
            try { repeatPreview() }
            catch (e: Exception) { onStatus("Controls প্রয়োগ হয়নি: ${e.message}") }
        }
    }

    private fun applyControls(builder: CaptureRequest.Builder) {
        val chars = characteristics ?: return
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        val manual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (controls.enabled && manual && isoRange != null && exposureRange != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, controls.iso.coerceIn(isoRange.lower, isoRange.upper))
            val maxFrame = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: exposureRange.upper
            val exposure = controls.exposureNs.coerceIn(exposureRange.lower, minOf(exposureRange.upper, maxFrame))
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            val minimum = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { map ->
                reader?.let { map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, Size(it.width, it.height)) }
            } ?: 33_333_333L
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(minimum, exposure).coerceAtMost(maxFrame))
        } else builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val focus = controls.focusDiopters
        if (focus != null && minFocus > 0 && afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, minFocus))
        } else {
            val mode = if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE else afModes.firstOrNull() ?: CameraMetadata.CONTROL_AF_MODE_OFF
            builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
        }
    }

    private fun repeatPreview() {
        val camera = device ?: return
        val current = session ?: return
        val target = surface ?: return
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(target); applyControls(this)
        }.build()
        current.setRepeatingRequest(request, null, handler)
    }

    fun captureDng(displayDegrees: Int) {
        if (released) return
        handler.post {
            val camera = device
            val current = session
            val raw = reader
            val chars = characteristics
            if (camera == null || current == null || raw == null || chars == null) {
                onStatus("DNG প্রস্তুত নয়; preview-only session হতে পারে"); return@post
            }
            if (!saving.compareAndSet(false, true)) return@post
            onCaptureState(true)
            capturePending = true
            val captureId = ++captureSerial
            val token = generation
            captureOrientation = CaptureRules.exifOrientation(
                chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, displayDegrees,
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
            onStatus("RAW capture হচ্ছে…")
            timeout = Runnable { if (token == generation && captureId == captureSerial && capturePending) cancelCapture("RAW/result timeout; আবার চেষ্টা করুন") }
                .also { handler.postDelayed(it, 8000) }
            try {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(raw.surface); applyControls(this)
                }.build()
                current.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        if (token != generation || captureId != captureSerial || !capturePending) return
                        pendingResult = result
                        matchAndSave(token)
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                        if (token == generation && captureId == captureSerial && capturePending) cancelCapture("RAW capture failed: ${failure.reason}")
                    }
                }, handler)
            } catch (e: Exception) { cancelCapture("RAW capture: ${e.message}") }
        }
    }

    private fun matchAndSave(token: Int) {
        val image = pendingImage ?: return
        val result = pendingResult ?: return
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: run { cancelCapture("Sensor timestamp পাওয়া যায়নি"); return }
        if (image.timestamp != timestamp) {
            // Never write a DNG with metadata from a different exposure.
            pendingImage?.close(); pendingImage = null
            return
        }
        val chars = characteristics ?: return
        val orientation = captureOrientation
        pendingImage = null; pendingResult = null; capturePending = false
        timeout?.let { handler.removeCallbacks(it) }; timeout = null
        onStatus("DNG সংরক্ষণ হচ্ছে…")
        storage.execute {
            val message = try {
                image.use { DngStore.save(app, chars, result, it, orientation) }.let { "DNG সংরক্ষিত: $it" }
            } catch (e: Exception) { "DNG সংরক্ষণ ব্যর্থ: ${e.message}" }
            finally { saving.set(false) }
            handler.post {
                onCaptureState(false)
                if (!released && generation == token) onStatus(message)
            }
        }
    }

    private fun cancelCapture(message: String? = null) {
        captureSerial++ // A timed-out request must never complete/cancel a later still.
        timeout?.let { handler.removeCallbacks(it) }; timeout = null
        pendingImage?.close(); pendingImage = null; pendingResult = null
        if (capturePending) { capturePending = false; saving.set(false); onCaptureState(false) }
        message?.let { onStatus(it) }
    }

    private fun retireReader() {
        val old = reader ?: return
        reader = null
        old.setOnImageAvailableListener(null, null)
        // A queued DNG write owns its Image. Do not invalidate it by closing the reader early.
        storage.execute { old.close() }
    }

    private fun fail(token: Int, message: String?) {
        if (token != generation || released) return
        closeInternal(); onStatus("ত্রুটি: ${message ?: "ক্যামেরা চালু করা যায়নি"}")
    }
    private fun closeInternal() {
        generation++
        cancelCapture()
        session?.close(); session = null
        device?.close(); device = null
        surface?.release(); surface = null
        retireReader(); characteristics = null
    }
    fun close() { if (!released) handler.post { closeInternal() } }
    fun detach(texture: SurfaceTexture) {
        if (!handler.post { closeInternal(); texture.release() }) texture.release()
    }
    fun release() {
        if (released) return
        released = true
        handler.post { closeInternal(); storage.shutdown(); thread.quitSafely() }
    }
}

data class ManualSettings(val enabled: Boolean = false, val iso: Int = 200,
                          val exposureNs: Long = 10_000_000L, val focusDiopters: Float? = null)
