package com.cinecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
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

/** How long a writer may refuse every buffer before the session is stopped as storage-bound. */
private const val STORAGE_STALL_LIMIT_NS = 3_000_000_000L
private const val STORAGE_STALL_LIMIT_MS = 3000L

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
    private var clipArchive = false
    private var clipVerify = true
    /** Why a direct-to-Download session fell back to staging ("" = it did not). */
    private var clipDirectDisabledReason = ""
    private var clipSequence = true
    private val clipAcceptedResults = mutableMapOf<Long, TotalCaptureResult>()
    private var clipSampleOrientation = 1
    private var clipPreallocate = false
    private var internalStagingRequested = true
    private var clipPrepared: RawClipStore.PreparedClip? = null
    private var clipGate: PacedRequestGate? = null
    private var clipPlan: RecordingPlan? = null
    private var clipSlot = 0
    private var clipSkippedSlots = 0
    private var clipStallSinceNs = 0L
    private var clipStallCount = 0
    private var clipPump: Runnable? = null
    private var clipFailedRequests = 0
    private var clipCapacityWaitStartNs = 0L
    private var clipCapacityWaitNs = 0L
    private var clipSerial = 0L
    private var clipFolder: File? = null
    private var clipDirect: DirectClipStore? = null
    private var clipDirectFd: ParcelFileDescriptor? = null
    private var clipDirectFds: List<ParcelFileDescriptor> = emptyList()
    private var clipPartCount = 1
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
    // Depth 1 is the measured default: depth 2 gave 2.1 fps versus 3.94 fps on the F23.
    fun startRawClip(displayRotation: Int, paced: Boolean = true, preallocate: Boolean = false, internalStaging: Boolean = true, verifyAndSample: Boolean = true, allFramesDng: Boolean = true, losslessArchive: Boolean = false, sessionSeconds: Int = 5, targetFps: Int = 3, pipelineDepth: Int = 1, directToDownload: Boolean = true) {
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
            // A new session decides its own storage mode; never inherit the previous one.
            clipDirect = null; clipDirectFd = null; clipDirectFds = emptyList(); clipPartCount = 1
            clipDirectDisabledReason = ""
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
                // Direct mode needs a provider row; the archive needs real paths, so it keeps staging.
                val directAllowed = directToDownload && Build.VERSION.SDK_INT >= 29 && !(verifyAndSample && losslessArchive)
                val directDisabled = when {
                    !directToDownload -> "off"
                    Build.VERSION.SDK_INT < 29 -> "api"
                    verifyAndSample && losslessArchive -> "archive"
                    else -> ""
                }
                val prepared = RawClipStore.prepare(app, chars, raw.width, raw.height, orientation, if (paced) "paced" else "burst",
                    JSONObject(engine.getStatsJson()).getJSONObject("writerTimings").getString("crcBackend"),
                    internalStaging, verifyAndSample && allFramesDng, verifyAndSample && losslessArchive, directAllowed)
                clipPrepared = prepared; clipFolder = prepared.folder
                clipDirectDisabledReason = directDisabled
                clipStartNs = SystemClock.elapsedRealtimeNanos()
                // Optional DNG copies and the archive are written after recording, into the same free space.
                val copyFactor = 1.0 + (if (verifyAndSample && allFramesDng) 1.0 else 0.0) +
                    (if (verifyAndSample && losslessArchive) 0.6 else 0.0)
                // A direct session has no export copy, so it may exceed the 8 GiB export cap.
                val directCap = directAllowed && !(verifyAndSample && losslessArchive)
                val plan = RecordingPlanner.plan(raw.width, raw.height,
                    (if (paced) targetFps else minOf(targetFps, 8)).coerceIn(RecordingPlanner.MIN_FPS, RecordingPlanner.MAX_FPS),
                    sessionSeconds.coerceIn(RecordingPlanner.MIN_SECONDS, RecordingPlanner.MAX_SECONDS),
                    prepared.captureAvailableBytes, copyFactor,
                    maxSessionBytes = if (directCap) RecordingPlanner.MAX_DIRECT_SESSION_BYTES else RecordingPlanner.MAX_SESSION_BYTES,
                    maxFramesCap = if (directCap) RecordingPlanner.MAX_DIRECT_FRAMES else RecordingPlanner.MAX_FRAMES)
                clipPlan = plan
                val wanted = if (paced) plan.maxFrames else minOf(8, plan.maxFrames)
                check(wanted > 0) {
                    "এই session-এর জন্য ফাঁকা জায়গা নেই: অন্তত ${(plan.frameBytes * copyFactor).toLong() / (1024 * 1024)} MiB " +
                        "প্রয়োজন, ${RecordingPlanner.RESERVE_BYTES / (1024 * 1024)} MiB reserve রাখা হয়"
                }
                val sessionBytes = plan.sessionBytes.coerceAtLeast(
                    RecordingPlanner.CcrawFrameHeaderBytes + raw.width.toLong() * raw.height * 2)
                val parts = if (directAllowed) RecordingPlanner.partCount(sessionBytes) else 1
                val started = if (directAllowed && parts > 1) {
                    // Long session: complete CCRAW parts, each written once into its own Download row.
                    val store = DirectClipStore(app, prepared.folder.name)
                    val descriptors = store.createCcrawParts(parts)
                    clipDirect = store; clipDirectFds = descriptors
                    engine.nativeStartRawClipFds(raw.width, raw.height, descriptors.map { it.fd }.toIntArray(),
                        clipStartNs, preallocate, wanted, sessionBytes, RecordingPlanner.PART_BYTES)
                } else if (directAllowed) {
                    // Writes the CCRAW once, into its final Download row: no second full-size copy.
                    val store = DirectClipStore(app, prepared.folder.name)
                    val descriptor = store.createCcraw()
                    clipDirect = store; clipDirectFd = descriptor
                    engine.nativeStartRawClipFd(raw.width, raw.height, descriptor.fd, clipStartNs, preallocate, wanted, sessionBytes)
                } else {
                    engine.nativeStartRawClip(raw.width, raw.height, clipFolder!!.absolutePath, clipStartNs, preallocate,
                        wanted, sessionBytes)
                }
                if (!started) {
                    clipDirectFd?.close(); clipDirectFd = null
                    clipDirectFds.forEach { runCatching { it.close() } }; clipDirectFds = emptyList()
                    clipDirect?.discard(); clipDirect = null
                    error(engine.getLastError())
                }
                clipPartCount = if (directAllowed) parts else 1
                clipLimit = wanted
                clipSlot = 0; clipSkippedSlots = 0; clipStallSinceNs = 0L; clipStallCount = 0
                internalStagingRequested = internalStaging
                clipArchive = verifyAndSample && losslessArchive
                clipVerify = verifyAndSample; clipSequence = verifyAndSample && allFramesDng; clipAcceptedResults.clear(); clipSampleOrientation = orientation
                clipPreallocate = preallocate
                clipPaced = paced
                val depth = if (paced) pipelineDepth.coerceIn(1, PacedRequestGate.MAX_DEPTH) else 1
                clipGate = if (paced) PacedRequestGate(clipLimit, depth) else null
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
                    if (clipActive && id == clipSerial) stopClipInternal("session safety timeout", true)
                }.also { handler.postDelayed(it, plan.safetyTimeoutMs) }
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
                    val pump = object : Runnable {
                        override fun run() {
                            if (!clipActive || generation != token || id != clipSerial) return
                            val gate = clipGate ?: return
                            if (gate.exhausted) { stopClipInternal("session frame budget reached", true); return }
                            // Depth 2 keeps the next request queued while the previous frame is matched.
                            if (gate.inFlightCount >= gate.depth) return
                            val activePlan = clipPlan ?: return
                            val now = SystemClock.elapsedRealtimeNanos()
                            if (now - clipStartNs >= activePlan.durationMs * 1_000_000L + RecordingPlanner.GRACE_MS * 1_000_000L) {
                                stopClipInternal("session duration reached", true); return
                            }
                            if (clipSlot >= activePlan.slotFrames) { stopClipInternal("session duration reached", true); return }
                            val due = RecordingPlanner.slotDueNs(clipStartNs, clipSlot, activePlan.fps)
                            if (now < due) {
                                handler.postDelayed(this, ((due - now) / 1_000_000L).coerceIn(1L, 20L))
                                return
                            }
                            val missed = RecordingPlanner.missedSlots(due, now, activePlan.fps)
                            if (missed > 0) {
                                clipSkippedSlots += missed
                                clipSlot = (clipSlot + missed).coerceAtMost(activePlan.slotFrames)
                            }
                            val capacity = engine.nativeRawClipCapacity()
                            if (capacity < 0) { stopClipInternal("native writer unavailable", true); return }
                            if (capacity == 0) {
                                if (clipCapacityWaitStartNs == 0L) clipCapacityWaitStartNs = now
                                if (clipStallSinceNs == 0L) clipStallSinceNs = now
                                // A writer that never frees a buffer means storage cannot keep up.
                                if (now - clipStallSinceNs >= STORAGE_STALL_LIMIT_NS) {
                                    clipStallCount++
                                    stopClipInternal("storage $STORAGE_STALL_LIMIT_MS ms ধরে frame নিচ্ছে না", true)
                                    return
                                }
                                handler.postDelayed(this, 10)
                                return
                            }
                            clipStallSinceNs = 0L
                            finishCapacityWait()
                            if (gate.trySubmit(capacity)) {
                                clipSlot++
                                try { current.capture(request, callback, handler) }
                                catch (e: Exception) { stopClipInternal("paced request error: ${e.message}", true) }
                            }
                        }
                    }
                    clipPump = pump
                    handler.post(pump)
                } else current.captureBurst(List(clipLimit) { request }, callback, handler)
                onStatus("RAW ${if (clipPaced) "session ${plan.seconds}s @ ${plan.fps}fps target" else "burst"}: " +
                    "সর্বোচ্চ $clipLimit frame • আসল rate storage-নির্ভর • no audio" +
                    if (plan.storageLimited) " • জায়গা অনুযায়ী frame কমেছে" else "")
            } catch (e: Exception) {
                if (clipActive) stopClipInternal("Burst error: ${e.message}", true)
                else {
                    engine.stopRecording(); engine.release()
                    clipDirectFd?.close(); clipDirectFd = null
                    clipDirectFds.forEach { runCatching { it.close() } }; clipDirectFds = emptyList()
                    clipDirect?.discard(); clipDirect = null
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
                            if (clipAccepted % 10 == 0) onStatus("RAW session: ${clipAccepted} frame লেখা হয়েছে • " +
                                "${clipPlan?.let { "${it.seconds}s @ ${it.fps}fps target" } ?: ""}")
                            if (clipFirstNs == 0L) clipFirstNs = timestamp
                            if (clipVerify) clipAcceptedResults[timestamp] = result
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
        val planForReport = clipPlan
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
            clipPrepared?.let {
                put("stagingLocation", it.stagingLocation)
                put("captureAvailableBytesAtPrepare", it.captureAvailableBytes)
                put("exportAvailableBytesAtPrepare", it.exportAvailableBytes)
            }
            put("internalStagingRequested", internalStagingRequested)
            put("storageMode", if (clipDirect != null) "direct-download" else "staging-export")
            put("ccrawParts", clipPartCount)
            if (clipDirectDisabledReason.isNotEmpty()) put("directDisabledReason", clipDirectDisabledReason)
            put("preallocationRequested", clipPreallocate)
            put("captureMode", if (clipPaced) "paced" else "burst")
            put("pipelineDepth", clipGate?.depth ?: 1)
            planForReport?.let { plan ->
                put("recordingPlan", JSONObject().apply {
                    put("fps", plan.fps); put("seconds", plan.seconds)
                    put("maxFrames", plan.maxFrames); put("slotFrames", plan.slotFrames)
                    put("frameBytes", plan.frameBytes); put("sessionBytes", plan.sessionBytes)
                    put("requiredBytes", plan.requiredBytes); put("storageLimited", plan.storageLimited)
                    put("reserveBytes", RecordingPlanner.RESERVE_BYTES)
                })
            }
            put("skippedSlots", clipSkippedSlots)
            put("storageStallStops", clipStallCount)
            put("submittedRequests", if (clipPaced) clipGate?.submitted ?: 0 else clipLimit)
            put("failedRequests", clipFailedRequests); put("pacedCapacityWaitNs", clipCapacityWaitNs)
            put("requestedFrames", clipLimit); put("imagesReceived", clipImagesReceived); put("resultsReceived", clipResultsReceived)
            put("matchedFrames", clipMatched); put("acceptedFrames", clipAccepted)
            put("unmatchedImages", clipUnmatched); put("pendingResultsAtStop", pendingResults)
            put("matchedButRejected", clipMatched - clipAccepted)
            put("firstAcceptedSensorNs", clipFirstNs); put("lastAcceptedSensorNs", clipLastNs)
            put("epochBoottimeNs", clipStartNs)
            put("sensorSpanNs", if (clipAccepted > 1) clipLastNs - clipFirstNs else 0)
            put("achievedFps", if (clipAccepted > 1 && clipLastNs > clipFirstNs)
                String.format(java.util.Locale.ROOT, "%.2f", (clipAccepted - 1) * 1e9 / (clipLastNs - clipFirstNs)).toDouble() else 0.0)
            put("captureWindowNs", SystemClock.elapsedRealtimeNanos() - clipStartNs)
            put("warning", "Bounded RAW session; the real rate depends on storage and sensor speed. No fixed-FPS promise, audio, MP4 or MotionCam compatibility")
        }
        clipPlan = null
        try {
            session?.abortCaptures()
            if (resumePreview) repeatPreview()
        } catch (_: Exception) {}
        onClipState("finalizing"); onStatus("RAW writer drain ও Download export হচ্ছে…")
        val archiveRequested = clipArchive
        val direct = clipDirect
        val directFd = clipDirectFd
        val directFds = clipDirectFds
        val partCount = clipPartCount
        val verifySample = clipVerify
        val sequenceDng = clipSequence
        val savedResults = clipAcceptedResults.toMap(); clipAcceptedResults.clear()
        val sampleChars = characteristics
        val sampleOrientation = clipSampleOrientation
        val statusGeneration = generation
        fun postStorageStatus(text: String) {
            handler.post { if (!released && generation == statusGeneration) onStatus(text) }
        }
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
                report.put("fileBytes", if (direct != null) (1..partCount).sumOf { direct.rowBytes(direct.partName(it)) }
                    else File(folder, "capture.ccraw").length())
                report.put("partialFileBytes", if (direct != null) 0L
                    else File(folder, "capture.ccraw.partial").length())
                val written = stats.optLong("framesWritten")
                engine.release() // Saved-file work no longer needs the 3 native buffers.
                report.put("dngExportMode", if (!verifySample) "off" else if (sequenceDng) "all-saved-frames" else "first-frame")
                report.put("readbackRequested", verifySample)
                report.put("losslessArchiveRequested", archiveRequested)
                if (archiveRequested) report.put("losslessArchiveError", "Archive তৈরি হয়নি: successful saved RAW যাচাই প্রয়োজন")
                if (verifySample && ok) {
                    postStorageStatus("Saved RAW CRC/structure যাচাই হচ্ছে…")
                    val verifyBegin = SystemClock.elapsedRealtimeNanos()
                    var verified: CcrawVerifier.Verified? = null
                    try {
                        // Direct mode verifies through the very descriptors the writer used (pread).
                        // Every part is a complete container; part 1 also feeds DNG/preview.
                        val partFrames = JSONArray()
                        if (direct != null) {
                            var total = 0L
                            direct.openCcrawParts().forEachIndexed { index, descriptor ->
                                val part = descriptor.use { CcrawVerifier.verify(it) }
                                partFrames.put(part.frames.size)
                                total += part.frames.size
                                if (index == 0) verified = part
                            }
                            report.put("ccrawPartFrames", partFrames)
                            check(total == written) { "Written/readback frame count mismatch (${total + 0} vs $written)" }
                        } else {
                            verified = CcrawVerifier.verify(File(folder, "capture.ccraw"))
                            check(verified!!.frames.size.toLong() == written) { "Written/readback frame count mismatch" }
                        }
                        val primary = verified ?: error("কোনো RAW part যাচাই হয়নি")
                        report.put("readbackOk", true); report.put("readbackFrames", primary.frames.size)
                        report.put("readbackPayloadBytes", primary.payloadBytes)
                        report.put("readbackBackend", if (direct != null) "java.util.zip.CRC32 / 64KiB pread on provider fd"
                            else "java.util.zip.CRC32 / 64KiB streaming")
                    } catch (e: Exception) {
                        verified = null; report.put("readbackOk", false); report.put("readbackError", e.message)
                    }
                    report.put("readbackNs", SystemClock.elapsedRealtimeNanos() - verifyBegin)
                    // A captured value: the lambda above prevents a smart cast on the mutable local.
                    val settled: CcrawVerifier.Verified? = verified
                    if (settled != null && settled.frames.isNotEmpty()) {
                        // Read-only codec feasibility check on the first verified saved payload.
                        // It needs a real path, so a direct session skips it instead of faking it.
                        if (direct == null) {
                            postStorageStatus("Lossless RAW16 codec roundtrip পরীক্ষা হচ্ছে…")
                            try {
                                report.put("losslessProbe", JSONObject(engine.nativeProbeRaw16Codec(
                                    File(folder, "capture.ccraw").absolutePath, settled.frames.first().payloadOffset,
                                    settled.width.toLong() * settled.height * 2)))
                            } catch (e: Exception) {
                                report.put("losslessProbeError", e.message)
                            }
                        } else report.put("losslessProbeSkipped", "direct-download")
                        if (archiveRequested) {
                            report.remove("losslessArchiveError")
                            postStorageStatus("Lossless archive তৈরি ও পুরো file byte-compare হচ্ছে…")
                            try {
                                report.put("losslessArchive", JSONObject(engine.nativeCreateBp16Archive(
                                    File(folder, "capture.ccraw").absolutePath, File(folder, "capture.ccraw.bp16").absolutePath)))
                            } catch (e: Exception) { report.put("losslessArchiveError", e.message) }
                        }
                        val dngBegin = SystemClock.elapsedRealtimeNanos()
                        try {
                            check(sampleChars != null) { "Capture calibration metadata missing" }
                            if (sequenceDng) {
                                val plan = DngSequencePlan.entries(settled)
                                // Validate ALL calibration mappings before creating sequence files.
                                plan.forEach {
                                    val result = savedResults[it.frame.timestampNs]
                                    check(result != null && result.frameNumber == it.frame.sourceFrameNumber) { "Saved sequence metadata missing/mismatched" }
                                }
                                val completed = java.util.Collections.synchronizedList(mutableListOf<String>())
                                val dngDone = java.util.concurrent.atomic.AtomicInteger()
                                // Staging writes files; direct mode writes each DNG once into its Download row.
                                val dngTarget: ClipDngTarget = direct ?: FolderDngTarget(folder)
                                try {
                                    val entries = JSONArray()
                                    // Independent files: a bounded pool keeps encode/fsync stages overlapping.
                                    // The manifest order is fixed by index, never by completion order.
                                    BoundedParallel.map(EXPORT_WORKERS, plan.size) { index ->
                                        val entry = plan[index]
                                        ClipDngSample.writeFrame(dngTarget, settled, entry.frame, sampleChars,
                                            savedResults.getValue(entry.frame.timestampNs), sampleOrientation, entry.name)
                                        completed.add(entry.name)
                                        postStorageStatus("Saved DNG sequence: ${dngDone.incrementAndGet()}/${plan.size}…")
                                        entry
                                    }.forEach { entry ->
                                        entries.put(JSONObject().apply {
                                            put("file", entry.name); put("timestampNs", entry.frame.timestampNs)
                                            put("ptsNs", entry.ptsNs); put("sourceFrameNumber", entry.frame.sourceFrameNumber)
                                        })
                                    }
                                    val manifest = JSONObject().apply {
                                        put("schema", "cinecam-dng-sequence-1"); put("frames", entries)
                                        put("clock", "BOOTTIME"); put("epochNs", settled.epochNs)
                                        put("fixedFps", JSONObject.NULL); put("audio", false)
                                        put("warning", "Numbered DNG still sequence; no certified CinemaDNG/NLE timing conformance, no synthetic missing frames")
                                    }
                                    File(folder, "sequence.json").writeText(manifest.toString(2))
                                    report.put("dngSequenceOk", true); report.put("dngSequenceFrames", plan.size)
                                    report.put("dngSequenceWorkers", EXPORT_WORKERS)
                                    if (partCount > 1) report.put("dngSequenceSourceParts", 1)
                                    report.put("dngSequenceBytes", completed.sumOf { name ->
                                        if (direct != null) direct.rowBytes(name) else File(folder, name).length() })
                                } catch (e: Exception) {
                                    completed.forEach { dngTarget.delete(it) }; File(folder, "sequence.json").delete()
                                    throw e
                                }
                            } else {
                                val sampleResult = savedResults[settled.frames.first().timestampNs]
                                check(sampleResult != null) { "First-frame calibration metadata missing" }
                                val sampleTarget: ClipDngTarget = direct ?: FolderDngTarget(folder)
                                ClipDngSample.write(sampleTarget, settled, sampleChars, sampleResult, sampleOrientation)
                                report.put("firstFrameDngOk", true)
                                report.put("firstFrameDngBytes", if (direct != null) direct.rowBytes("first_frame.dng")
                                    else File(folder, "first_frame.dng").length())
                                report.put("firstFrameDngTimestampNs", settled.frames.first().timestampNs)
                            }
                        } catch (e: Exception) {
                            report.put(if (sequenceDng) "dngSequenceOk" else "firstFrameDngOk", false)
                            report.put(if (sequenceDng) "dngSequenceError" else "firstFrameDngError", e.message)
                        }
                        report.put(if (sequenceDng) "dngSequenceNs" else "firstFrameDngNs", SystemClock.elapsedRealtimeNanos() - dngBegin)
                    }
                }
                directFd?.let { runCatching { it.close() } }
                directFds.forEach { runCatching { it.close() } }
                if (direct != null) {
                    // Only a drained and verified session is published; a partial clip stays hidden
                    // and is deleted, so the gallery never shows a truncated RAW.
                    val publishable = ok && (!verifySample || report.optBoolean("readbackOk"))
                    if (publishable) {
                        // Verified files are final: publish now so a later report failure cannot
                        // cost the user the clip. Only the small sidecars still go through export.
                        postStorageStatus("Download-এ publish হচ্ছে…")
                        val failed = direct.publish()
                        report.put("directPublished", true)
                        report.put("directPublishFailures", JSONArray(failed))
                        report.put("directInPlace", JSONArray(direct.localNames()))
                    } else {
                        direct.discard()
                        report.put("directDiscarded", "session incomplete বা যাচাই ব্যর্থ — কোনো ফাইল publish হয়নি")
                    }
                }
                postStorageStatus("Download/CineCam-এ report export হচ্ছে…")
                val target = RawClipStore.export(app, folder, report, direct?.exportedMapping() ?: emptyMap()) {
                    postStorageStatus(it)
                }
                message = "RAW test ${if (ok && written > 0 && (!verifySample || report.optBoolean("readbackOk"))) "সংরক্ষিত" else "অসম্পূর্ণ/যাচাই ব্যর্থ"}: $written frames • $target"
                if (verifySample && written > 0 && report.optBoolean("readbackOk") &&
                    !report.optBoolean(if (sequenceDng) "dngSequenceOk" else "firstFrameDngOk"))
                    message += " • DNG export ব্যর্থ (report দেখুন)"
            } catch (e: Exception) {
                direct?.discard() // A failed session publishes nothing and leaves no hidden rows.
                report.put("exportError", e.message)
                try { File(folder, "report.json").writeText(report.toString(2)) } catch (_: Exception) { }
                message = "RAW test error: ${e.message}"
            } finally {
                engine.release()
                runCatching { directFd?.close() }
                directFds.forEach { runCatching { it.close() } }
                clipDirect = null; clipDirectFd = null; clipDirectFds = emptyList(); clipPartCount = 1
            }
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
