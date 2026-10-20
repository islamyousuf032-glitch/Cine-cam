package com.cinecam.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cinecam.camera.CameraCapabilities
import com.cinecam.camera.CameraController
import com.cinecam.camera.RawSupportInfo
import com.cinecam.camera.PreviewGeometry
import com.cinecam.camera.ManualSettings
import com.cinecam.camera.CaptureRules
import kotlinx.coroutines.delay
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Phase 2 deliberately does not initialize the RAW engine or allocate capture buffers. */
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun cameraGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(cameraGranted()) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var infos by remember { mutableStateOf(emptyList<RawSupportInfo>()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("অনুমতির অপেক্ষায়") }
    var retry by remember { mutableIntStateOf(0) }
    var showReport by remember { mutableStateOf(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var disposed by remember { mutableStateOf(false) }
    var clipState by remember { mutableStateOf("idle") }
    var clipReport by remember { mutableStateOf("") }
    var allSavedFramesDng by remember { mutableStateOf(true) }
    var verifyRawSample by remember { mutableStateOf(true) }
    var internalRawStaging by remember { mutableStateOf(android.os.Build.VERSION.SDK_INT >= 29) }
    var preallocateRawTest by remember { mutableStateOf(false) }
    var pacedRawTest by remember { mutableStateOf(true) }
    var showRawWarning by remember { mutableStateOf(false) }
    var showClipReport by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(ManualSettings()) }
    var showControls by remember { mutableStateOf(false) }
    val controller = remember { CameraController(context.applicationContext, onStatus = { message ->
        main.post { if (!disposed) status = message }
    }, onCaptureState = { busy -> main.post { if (!disposed) capturing = busy } },
        onClipState = { state -> main.post { if (!disposed) clipState = state } },
        onClipReport = { report -> main.post { if (!disposed) clipReport = report } }) }
    LaunchedEffect(manual) { delay(150); controller.setManual(manual) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { granted = cameraGranted(); resumed = true }
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); disposed = true; controller.release() }
    }
    LaunchedEffect(granted, retry) {
        if (granted) {
            scanError = null
            try { infos = withContext(Dispatchers.IO) { CameraCapabilities.checkAllCameras(context) } }
            catch (e: Exception) { scanError = e.message; infos = emptyList() }
        }
    }
    val camera = remember(infos) { CameraCapabilities.pickBestRawCamera(infos) }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var view by remember { mutableStateOf<TextureView?>(null) }
    var displayRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    DisposableEffect(view) {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        fun updateRotation() { displayRotation = view?.display?.rotation ?: Surface.ROTATION_0 }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id == view?.display?.displayId) updateRotation()
            }
        }
        updateRotation()
        manager.registerDisplayListener(listener, main)
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    val size = camera?.previewSizes?.filter { it.width <= 1920 && it.height <= 1080 }
        ?.maxByOrNull { it.width.toLong() * it.height }
        ?: camera?.previewSizes?.minByOrNull { it.width.toLong() * it.height }

    DisposableEffect(granted, resumed, camera?.cameraId, texture, size, retry) {
        val target = texture
        if (granted && resumed && camera != null && target != null && size != null) {
            controller.open(camera.cameraId, target, size)
        }
        onDispose { controller.close() }
    }
    LaunchedEffect(width, height, size, camera, resumed, displayRotation) {
        val target = view
        if (target != null && size != null && camera != null && width > 0 && height > 0) {
            transformPreview(target, size, camera)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("CineCam • RAW test", color = Color.White)
            Text(camera?.let { "Camera ${it.cameraId} • ${it.hardwareLevelString()} • RAW=${it.supportsRawSensor}" }
                ?: "ক্যামেরা নির্বাচন হয়নি", color = Color.LightGray)
            Text(scanError ?: if (granted) status else "ক্যামেরা অনুমতি প্রয়োজন", color = Color.LightGray)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (granted) {
                AndroidView(factory = { ctx -> TextureView(ctx).apply {
                    view = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) { texture = t; width = w; height = h }
                        override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) { width = w; height = h }
                        override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                            texture = null
                            controller.detach(t)
                            return false // Controller releases texture after closing Camera2 targets.
                        }
                        override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
                    }
                } }, modifier = Modifier.fillMaxSize())
            } else {
                Column(Modifier.padding(20.dp)) {
                    Text("প্রিভিউয়ের জন্য শুধু CAMERA অনুমতি লাগবে। এখন microphone বা storage অনুমতি লাগবে না।", color = Color.White)
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("ক্যামেরা অনুমতি দিন") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("অ্যাপ Settings খুলুন") }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (granted && camera == null) Text("কোনো accessible camera পাওয়া যায়নি। আবার চেষ্টা করুন।", color = Color.Yellow)
            if (camera != null && !camera.supportsRawSensor) Text("RAW_SENSOR পাওয়া যায়নি; সাধারণ preview চলতে পারে।", color = Color.Yellow)
            Row {
                TextButton(onClick = { retry++ }, enabled = granted) { Text("আবার চেষ্টা") }
                TextButton(onClick = { showReport = true }, enabled = infos.isNotEmpty()) { Text("Capability report") }
            }
            Row {
                TextButton(onClick = { showControls = !showControls }, enabled = camera != null) {
                    Text(if (showControls) "Controls বন্ধ" else "Manual controls")
                }
                Button(onClick = {
                    val degrees = when (view?.display?.rotation) {
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                    controller.captureDng(degrees)
                }, enabled = granted && resumed && camera?.supportsRawSensor == true && !capturing) {
                    Text(if (capturing) "সংরক্ষণ হচ্ছে…" else "RAW DNG তুলুন")
                }
            }
            Row {
                Button(onClick = {
                    if (clipState == "recording") controller.stopRawClip() else showRawWarning = true
                }, enabled = clipState == "recording" || (clipState == "idle" && !capturing && granted && resumed &&
                    camera?.supportsRawSensor == true && camera.timestampSource == 1)) {
                    Text(when (clipState) {
                        "recording" -> "RAW test বন্ধ করুন"
                        "starting" -> "RAW প্রস্তুতি…"
                        "finalizing" -> "RAW export হচ্ছে…"
                        else -> "RAW test • সর্বোচ্চ 8 frames"
                    })
                }
            }
            TextButton(onClick = { showClipReport = true }, enabled = clipReport.isNotEmpty()) { Text("শেষ RAW test report") }
            if (showControls && camera != null) {
                ManualStillControls(camera, manual, !capturing) { manual = it }
            }
            Text("WB: Auto • RAW test: no audio/MP4 • DNG: DCIM/CineCam", color = Color.LightGray)
        }
    }
    if (showReport) {
        val report = CameraCapabilities.report(infos)
        AlertDialog(onDismissRequest = { showReport = false }, title = { Text("Camera2 capability report") },
            text = { Text(report, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CineCam capabilities", report))
            }) { Text("কপি করুন") } },
            dismissButton = { TextButton(onClick = { showReport = false }) { Text("বন্ধ") } })
    }
    if (showRawWarning) {
        AlertDialog(onDismissRequest = { showRawWarning = false }, title = { Text("পরীক্ষামূলক RAW test") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("সর্বোচ্চ 8 RAW frames (~200 MB), DNG sequence-এ আরও প্রায় 200 MB। নিচের mode অনুযায়ী free-space guard প্রযোজ্য। MP4/audio/compression নেই; fixed FPS নয়। Download/CineCam-এ export হবে।")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Paced mode", Modifier.weight(1f))
                        Switch(checked = pacedRawTest, onCheckedChange = { pacedRawTest = it })
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Internal staging", Modifier.weight(1f))
                        Switch(checked = internalRawStaging, onCheckedChange = { internalRawStaging = it },
                            enabled = android.os.Build.VERSION.SDK_INT >= 29)
                    }
                    Text("ON: app-private internal temporary file। OFF: আগের app-external path। দুটোই শেষে Download/CineCam-এ export হবে (Android 10+)।")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("আগে file space reserve", Modifier.weight(1f))
                        Switch(checked = preallocateRawTest, onCheckedChange = { preallocateRawTest = it })
                    }
                    Text("ON: capture শুরুর আগে space reserve; setup বেশি সময় নিতে পারে। OFF: আগের allocation। Flush বন্ধ নয়।")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Saved RAW যাচাই + DNG export", Modifier.weight(1f))
                        Switch(checked = verifyRawSample, onCheckedChange = { verifyRawSample = it })
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("সব saved frame-এর DNG sequence", Modifier.weight(1f))
                        Switch(checked = allSavedFramesDng, onCheckedChange = { allSavedFramesDng = it }, enabled = verifyRawSample)
                    }
                    Text(if (verifyRawSample && allSavedFramesDng)
                        "Sequence: অন্তত 1 GiB free দরকার। Saved RAW যাচাইয়ের পরে frame_000001.dng… + sequence.json; DNG-তেই আরও প্রায় 200 MB। Extra সময় লাগবে; fixed FPS/audio নয়।"
                        else "যাচাই ON + sequence OFF: আগের first_frame.dng sample (~25 MB)। 650 MiB free প্রয়োজন।")
                    Text(if (pacedRawTest) "Paced: একবারে একটি request; buffer ফাঁকা হলে পরের frame। ধীর/variable FPS, কম drop-এর পরীক্ষা।"
                         else "Burst: আগের মতো দ্রুত 8 requests। একই 3-buffer pool-এ throughput/drop তুলনা।")
                }
            },
            confirmButton = { TextButton(onClick = {
                showRawWarning = false
                controller.startRawClip(view?.display?.rotation ?: Surface.ROTATION_0, pacedRawTest, preallocateRawTest, internalRawStaging, verifyRawSample, allSavedFramesDng)
            }, enabled = granted && resumed && !capturing) { Text("টেস্ট শুরু করুন") } },
            dismissButton = { TextButton(onClick = { showRawWarning = false }) { Text("বাতিল") } })
    }
    if (showClipReport) {
        AlertDialog(onDismissRequest = { showClipReport = false }, title = { Text("শেষ RAW test report") },
            text = { Text(clipReport, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("CineCam RAW test", clipReport))
            }) { Text("কপি করুন") } },
            dismissButton = { TextButton(onClick = { showClipReport = false }) { Text("বন্ধ") } })
    }

}

@Composable
private fun ManualStillControls(info: RawSupportInfo, settings: ManualSettings, enabled: Boolean,
                                onChange: (ManualSettings) -> Unit) {
    Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
        Row {
            Text("Manual exposure", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = settings.enabled, enabled = enabled && info.supportsManualSensor,
                onCheckedChange = { onChange(settings.copy(enabled = it)) })
        }
        val iso = info.isoRange
        val exposure = info.exposureTimeRange
        if (info.supportsManualSensor && iso != null && exposure != null) {
            Text("ISO ${settings.iso.coerceIn(iso.lower, iso.upper)}", color = Color.White)
            Slider(value = settings.iso.toFloat().coerceIn(iso.lower.toFloat(), iso.upper.toFloat()),
                valueRange = iso.lower.toFloat()..maxOf(iso.lower + 1, iso.upper).toFloat(),
                enabled = enabled && settings.enabled,
                onValueChange = { onChange(settings.copy(iso = it.toInt().coerceIn(iso.lower, iso.upper))) })
            Text("Shutter ${String.format(java.util.Locale.US, "%.2f", settings.exposureNs / 1e6)} ms", color = Color.White)
            Slider(value = CaptureRules.exposurePosition(settings.exposureNs, exposure.lower, exposure.upper),
                enabled = enabled && settings.enabled,
                onValueChange = { onChange(settings.copy(exposureNs = CaptureRules.exposureAt(it, exposure.lower, exposure.upper))) })
        } else Text("এই ক্যামেরায় manual sensor control নেই", color = Color.Yellow)
        if (info.minimumFocusDistance > 0) {
            Row {
                Text("Auto focus", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = settings.focusDiopters == null, enabled = enabled,
                    onCheckedChange = { onChange(settings.copy(focusDiopters = if (it) null else 0f)) })
            }
            Text("Focus ${settings.focusDiopters?.let { String.format(java.util.Locale.US, "%.2f D", it) } ?: "Auto"} • 0 D = ∞", color = Color.White)
            Slider(value = settings.focusDiopters ?: 0f, valueRange = 0f..info.minimumFocusDistance,
                enabled = enabled && settings.focusDiopters != null,
                onValueChange = { onChange(settings.copy(focusDiopters = it)) })
        }
    }
}

/** Correct producer-transformed content; never rotate by sensor orientation twice. */
private fun transformPreview(view: TextureView, size: Size, camera: RawSupportInfo) {
    val displayDegrees = when (view.display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    val source = floatArrayOf(0f, 0f, view.width.toFloat(), 0f,
        view.width.toFloat(), view.height.toFloat(), 0f, view.height.toFloat())
    val destination = PreviewGeometry.corners(view.width, view.height, size.width, size.height,
        camera.sensorOrientation, displayDegrees,
        camera.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT)
    view.setTransform(Matrix().apply { setPolyToPoly(source, 0, destination, 0, 4) })
}
