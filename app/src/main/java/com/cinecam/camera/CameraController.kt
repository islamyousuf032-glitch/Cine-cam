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
import java.io.File
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import com.cinecam.engine.NativeRawEngine
import com.cinecam.app.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Camera/session state is handler-confined; one bounded still is owned by the storage worker. */
class CameraController(context: Context, private val onStatus: (String) -> Unit,
                       private val onCaptureState: (Boolean) -> Unit = {},
                       private val onClipState: (String) -> Unit = {},
                       private val onClipReport: (String) -> Unit = {}) {
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
    private var clipActive = false
    private var clipPaced = true
    private var clipPreallocate = true
    private var clipGate: PacedRequestGate? = null
    private var clipPump: Runnable? = null
    private var clipFailedRequests = 0
    private var clipCapacityWaitStartNs = 0L
    private var clipCapacityWaitNs = 0L
    private var clipSerial = 0L
    private var clipFolder: File? = null
    private var clipLimit = 8
    private var clipMatched = 0
    private var clipAccepted = 0
    private var clipImagesReceived = 0
    private var clipResultsReceived = 0
    private var clipUnmatched = 0
    private var clipFirstNs = 0L
    private var clipLastNs = 0L
    private var clipStartNs = 0L
    private var clipTimeout: Runnable? = null
    private val clipFrameMetadata = mutableListOf<JSONObject>()
    private val clipPairs = TimestampPairingWindow<Image, TotalCaptureResult>()
    private var clipAcquisitionDeferrals = 0
    private val clipImageTimestamps = mutableListOf<Long>()
    private val clipResultTimestamps = mutableListOf<Long>()
    private val clipEvictedResultTimestamps = mutableListOf<Long>()
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
                    reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3).apply {
                        setOnImageAvailableListener({ source ->
                            try {
                                if (token == generation && clipActive) {
                                    drainClipImages(source, token)
                                    return@setOnImageAvailableListener
                                }
                                val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                                if (token != generation) image.close()
                                else if (!capturePending) image.close()
                                else {
                                    pendingImage?.close()
                                    pendingImage = image
                                    matchAndSave(token)
                                }
                            } catch (e: Exception) {
                                if (token == generation) {
                                    if (clipActive) stopClipInternal("RAW image error: ${e.message}", true)
                                    else cancelCapture("RAW image: ${e.message}")
                                }
                            }
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

    /** Finite 8-request RAW burst, not a sustained FPS/recording claim. */
    fun startRawClip(displayRotation: Int, paced: Boolean = true, preallocate: Boolean = true) {
        if (released) return
        handler.post {
            val camera = device; val current = session; val raw = reader; val chars = characteristics
            if (camera == null || current == null || raw == null || chars == null) {
                onStatus("RAW session প্রস্তুত নয়"); return@post
            }
            if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) != CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                onStatus("এই test-এর জন্য REALTIME sensor clock প্রয়োজন"); return@post
            }
            if (!saving.compareAndSet(false, true)) return@post
            onCaptureState(true); onClipState("starting")
            val engine = NativeRawEngine.getInstance()
            try {
                val displayDegrees = when (displayRotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val orientation = CaptureRules.exifOrientation(chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                    displayDegrees, chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                clipFolder = RawClipStore.prepare(app, chars, raw.width, raw.height, orientation, if (paced) "paced" else "burst",
                    JSONObject(engine.getStatsJson()).getJSONObject("writerTimings").getString("crcBackend"))
                clipStartNs = SystemClock.elapsedRealtimeNanos()
                check(engine.nativeStartRawClip(raw.width, raw.height, clipFolder!!.absolutePath, clipStartNs, preallocate)) {
                    engine.getLastError()
                }
                clipLimit = minOf(8, (192L * 1024 * 1024 / (raw.width.toLong() * raw.height * 2)).toInt())
                check(clipLimit > 0) { "RAW resolution exceeds burst storage budget" }
                clipPreallocate = preallocate
                clipPaced = paced
                clipGate = if (paced) PacedRequestGate(clipLimit) else null
                clipFailedRequests = 0; clipCapacityWaitNs = 0; clipCapacityWaitStartNs = 0
                clipAcquisitionDeferrals = 0
                clipImageTimestamps.clear(); clipResultTimestamps.clear(); clipEvictedResultTimestamps.clear()
                clipFrameMetadata.clear()
                clipMatched = 0; clipAccepted = 0; clipUnmatched = 0
                clipImagesReceived = 0; clipResultsReceived = 0; clipFirstNs = 0; clipLastNs = 0
                val id = ++clipSerial
                val token = generation
                clipActive = true
                onClipState("recording")
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(raw.surface)
                    surface?.let { addTarget(it) }
                    applyControls(this)
                    setTag(id)
                }.build()
                clipTimeout = Runnable {
                    if (clipActive && id == clipSerial) stopClipInternal("12-second safety timeout", true)
                }.also { handler.postDelayed(it, 12000) }
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        if (!clipActive || generation != token || id != clipSerial) return
                        clipResultsReceived++
                        result.get(CaptureResult.SENSOR_TIMESTAMP)?.let {
                            clipResultTimestamps.add(it)
                            clipPairs.addResult(it, result)?.let { evicted -> clipEvictedResultTimestamps.add(evicted) }
                        }
                        matchClipFrames()
                        // Resume unread ImageReader buffers when metadata frees a held Image slot.
                        if (clipActive) reader?.let { drainClipImages(it, token) }
                    }
                    override fun onCaptureSequenceCompleted(s: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                        if (!clipPaced) handler.postDelayed({
                            if (clipActive && generation == token && id == clipSerial) stopClipInternal("burst completed; late-image grace elapsed", true)
                        }, 1200)
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                        if (!clipActive || generation != token || id != clipSerial) return
                        clipFailedRequests++
                        onStatus("RAW request failed: ${failure.reason}")
                        if (clipPaced) advancePaced()
                    }
                    override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
                        if (clipActive && generation == token && id == clipSerial) stopClipInternal("camera sequence aborted", true)
                    }
                }
                if (clipPaced) {
                    clipPump = Runnable {
                        if (!clipActive || generation != token || id != clipSerial) return@Runnable
                        val gate = clipGate ?: return@Runnable
                        if (gate.inFlight) return@Runnable
                        if (gate.exhausted) { stopClipInternal("paced request limit reached", true); return@Runnable }
                        val capacity = engine.nativeRawClipCapacity()
                        if (capacity < 0) { stopClipInternal("native writer unavailable", true); return@Runnable }
                        if (capacity == 0) {
                            if (clipCapacityWaitStartNs == 0L) clipCapacityWaitStartNs = SystemClock.elapsedRealtimeNanos()
                            clipPump?.let { handler.postDelayed(it, 10) }
                            return@Runnable
                        }
                        finishCapacityWait()
                        if (gate.trySubmit(capacity)) {
                            try { current.capture(request, callback, handler) }
                            catch (e: Exception) { stopClipInternal("paced request error: ${e.message}", true) }
                        }
                    }
                    clipPump?.let { handler.post(it) }
                } else current.captureBurst(List(clipLimit) { request }, callback, handler)
                onStatus("RAW ${if (clipPaced) "Paced" else "Burst"}: সর্বোচ্চ $clipLimit frames • variable FPS • no audio")
            } catch (e: Exception) {
                if (clipActive) stopClipInternal("Burst error: ${e.message}", true)
                else {
                    engine.stopRecording(); engine.release()
                    clipFolder?.deleteRecursively(); clipFolder = null
                    saving.set(false); onCaptureState(false); onClipState("idle")
                    onStatus("RAW test শুরু হয়নি: ${e.message}")
                }
            }
        }
    }

    private fun finishCapacityWait() {
        if (clipCapacityWaitStartNs != 0L) {
            clipCapacityWaitNs += SystemClock.elapsedRealtimeNanos() - clipCapacityWaitStartNs
            clipCapacityWaitStartNs = 0L
        }
    }

    private fun advancePaced() {
        if (!clipActive || !clipPaced) return
        clipGate?.finish()
        if (clipGate?.exhausted == true) stopClipInternal("paced request limit reached", true)
        else clipPump?.let { handler.post(it) }
    }

    fun stopRawClip() { if (!released) handler.post { stopClipInternal("user stop", true) } }

    private fun drainClipImages(source: ImageReader, token: Int) {
        if (!clipActive || generation != token || source !== reader) return
        if (!clipPairs.canAcquire) { clipAcquisitionDeferrals++; return }
        try {
            // At most 8 requests exist. Keep <=2 acquired Images, leaving a driver slot free.
            while (clipActive && clipPairs.canAcquire) {
                val image = source.acquireNextImage() ?: break
                clipImagesReceived++
                clipImageTimestamps.add(image.timestamp)
                clipPairs.addImage(image.timestamp, image)?.let { it.close(); clipUnmatched++ }
                matchClipFrames()
            }
        } catch (e: Exception) { stopClipInternal("RAW image drain error: ${e.message}", true) }
    }

    private fun matchClipFrames() {
        while (clipActive) {
            val pair = clipPairs.pollMatch() ?: break
            val timestamp = pair.timestamp
            val result = pair.result
            val image = pair.image
            clipMatched++
            var wasAccepted = false
            var rejectionReason = "missing sensor metadata or unsupported format"
            var sourceRowStride = 0
            var sourcePixelStride = 0
            var nativeIngestNs = 0L
            val pairingAtNs = SystemClock.elapsedRealtimeNanos()
            try {
                image.use {
                    val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                    if (exposure != null && iso != null && image.format == ImageFormat.RAW_SENSOR) {
                        val plane = image.planes.single()
                        sourceRowStride = plane.rowStride; sourcePixelStride = plane.pixelStride
                        require(plane.pixelStride == 2 && plane.buffer.isDirect) { "RAW16 needs direct buffer and pixelStride=2" }
                        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                        val ingestBegin = SystemClock.elapsedRealtimeNanos()
                        val accepted = NativeRawEngine.getInstance().nativePushRawPlane(plane.buffer.slice(), image.width, image.height,
                            plane.rowStride, plane.pixelStride, timestamp, exposure, iso, result.frameNumber.toInt(),
                            result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                            neutral?.getOrNull(0)?.toFloat() ?: 0f, neutral?.getOrNull(1)?.toFloat() ?: 0f,
                            neutral?.getOrNull(2)?.toFloat() ?: 0f)
                        nativeIngestNs = SystemClock.elapsedRealtimeNanos() - ingestBegin
                        rejectionReason = if (accepted) "" else "native rejected; see native.dropReasons"
                        if (accepted) {
                            wasAccepted = true
                            clipAccepted++
                            if (clipFirstNs == 0L) clipFirstNs = timestamp
                            clipLastNs = timestamp
                        }
                    }
                }
            } catch (e: Exception) { rejectionReason = e.message ?: "frame error"; onStatus("RAW frame reject: ${e.message}") }
            try { clipFrameMetadata.add(JSONObject().apply {
                put("timestampNs", timestamp); put("sourceFrameNumber", result.frameNumber); put("accepted", wasAccepted)
                put("rejectionReason", rejectionReason); put("sourceRowStride", sourceRowStride); put("sourcePixelStride", sourcePixelStride)
                put("nativeIngestNs", nativeIngestNs); put("pairingLatencyNs", pairingAtNs - timestamp)
                put("exposureNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY))
                put("neutralColorPoint", result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { JSONArray(it.map { value -> value.toString() }) } ?: JSONObject.NULL)
                put("dynamicBlackLevel", result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                put("dynamicWhiteLevel", result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL))
                put("cropRegion", result.get(CaptureResult.SCALER_CROP_REGION)?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
            }) } catch (e: Exception) {
                clipFrameMetadata.add(JSONObject().put("timestampNs", timestamp).put("accepted", wasAccepted)
                    .put("metadataError", e.message))
            }
            if (clipPaced) {
                advancePaced()
                if (!clipActive) return
            } else if (clipMatched >= clipLimit) { stopClipInternal("frame limit reached", true); return }
        }
    }

    private fun stopClipInternal(reason: String, resumePreview: Boolean) {
        if (!clipActive) return
        clipActive = false
        clipPump?.let { handler.removeCallbacks(it) }; clipPump = null
        finishCapacityWait()
        clipTimeout?.let { handler.removeCallbacks(it) }; clipTimeout = null
        val pendingImageTimestamps = clipPairs.imageTimestamps
        val pendingResultTimestamps = clipPairs.resultTimestamps
        clipUnmatched += pendingImageTimestamps.size
        clipPairs.clearImages().forEach { it.close() }
        val pendingResults = pendingResultTimestamps.size; clipPairs.clearResults()
        val folder = clipFolder ?: return
        clipFolder = null
        val report = JSONObject().apply {
            put("frames", JSONArray(clipFrameMetadata.toList()))
            put("schema", "cinecam-raw-test-2"); put("reason", reason)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("pairingPolicy", "bounded-acquisition-no-eviction")
            put("imageAcquisitionDeferrals", clipAcquisitionDeferrals)
            put("imageTimestamps", JSONArray(clipImageTimestamps.toList()))
            put("resultTimestamps", JSONArray(clipResultTimestamps.toList()))
            put("pendingImageTimestampsAtStop", JSONArray(pendingImageTimestamps))
            put("pendingResultTimestampsAtStop", JSONArray(pendingResultTimestamps))
            put("evictedResultTimestamps", JSONArray(clipEvictedResultTimestamps.toList()))
            put("preallocationRequested", clipPreallocate)
            put("captureMode", if (clipPaced) "paced" else "burst")
            put("submittedRequests", if (clipPaced) clipGate?.submitted ?: 0 else clipLimit)
            put("failedRequests", clipFailedRequests); put("pacedCapacityWaitNs", clipCapacityWaitNs)
            put("requestedFrames", clipLimit); put("imagesReceived", clipImagesReceived); put("resultsReceived", clipResultsReceived)
            put("matchedFrames", clipMatched); put("acceptedFrames", clipAccepted)
            put("unmatchedImages", clipUnmatched); put("pendingResultsAtStop", pendingResults)
            put("matchedButRejected", clipMatched - clipAccepted)
            put("firstAcceptedSensorNs", clipFirstNs); put("lastAcceptedSensorNs", clipLastNs)
            put("epochBoottimeNs", clipStartNs)
            put("sensorSpanNs", if (clipAccepted > 1) clipLastNs - clipFirstNs else 0)
            put("captureWindowNs", SystemClock.elapsedRealtimeNanos() - clipStartNs)
            put("warning", "Short diagnostic RAW burst only; not sustained FPS, playable video, audio sync or MotionCam compatibility")
        }
        try {
            session?.abortCaptures()
            if (resumePreview) repeatPreview()
        } catch (_: Exception) {}
        onClipState("finalizing"); onStatus("RAW writer drain ও Download export হচ্ছে…")
        storage.execute {
            val engine = NativeRawEngine.getInstance()
            var message: String
            try {
                val stopBeginNs = SystemClock.elapsedRealtimeNanos()
                val ok = engine.stopRecording()
                report.put("stopDrainNs", SystemClock.elapsedRealtimeNanos() - stopBeginNs)
                val stats = JSONObject(engine.getStatsJson())
                report.put("nativeStopOk", ok); report.put("native", stats)
                report.put("nativeError", if (ok) "" else engine.getLastError())
                report.put("wallUntilDrainNs", SystemClock.elapsedRealtimeNanos() - report.getLong("epochBoottimeNs"))
                report.put("fileBytes", File(folder, "capture.ccraw").length())
                report.put("partialFileBytes", File(folder, "capture.ccraw.partial").length())
                val written = stats.optLong("framesWritten")
                val target = RawClipStore.export(app, folder, report)
                message = "RAW test ${if (ok && written > 0) "সংরক্ষিত" else "অসম্পূর্ণ"}: $written frames • $target"
            } catch (e: Exception) {
                report.put("exportError", e.message)
                message = "RAW test error: ${e.message}"
            } finally { engine.release() }
            val posted = handler.post {
                saving.set(false)
                onCaptureState(false); onClipState("idle"); onClipReport(report.toString(2))
                if (!released) onStatus(message)
            }
            if (!posted) saving.set(false)
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
        stopClipInternal("camera closed/background", false)
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
