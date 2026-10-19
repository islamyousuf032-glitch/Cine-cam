package com.cinecam.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
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
import com.cinecam.camera.CameraRules
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
    val controller = remember { CameraController(context.applicationContext) { message ->
        main.post { if (!disposed) status = message }
    } }
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
    LaunchedEffect(width, height, size, camera, resumed) {
        val target = view
        if (target != null && size != null && camera != null && width > 0 && height > 0) {
            transformPreview(target, size, camera)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("CineCam • ধাপ ২", color = Color.White)
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
            Text("Manual controls, DNG ও RAW recording পরবর্তী ধাপে সক্রিয় হবে।", color = Color.LightGray)
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
}

/** Map the camera buffer to an aspect-fit, sensor-corrected TextureView, without stretching. */
private fun transformPreview(view: TextureView, size: Size, camera: RawSupportInfo) {
    val displayDegrees = when (view.display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    val front = camera.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
    val degrees = CameraRules.relativeRotation(camera.sensorOrientation, displayDegrees, front)
    val angle = Math.toRadians(degrees.toDouble())
    val cos = kotlin.math.cos(angle).toFloat()
    val sin = kotlin.math.sin(angle).toFloat()
    val rotatedWidth = if (degrees % 180 == 0) size.width else size.height
    val rotatedHeight = if (degrees % 180 == 0) size.height else size.width
    val scale = minOf(view.width.toFloat() / rotatedWidth, view.height.toFloat() / rotatedHeight)
    val source = floatArrayOf(0f, 0f, view.width.toFloat(), 0f, view.width.toFloat(), view.height.toFloat(), 0f, view.height.toFloat())
    val buffer = floatArrayOf(-size.width / 2f, -size.height / 2f, size.width / 2f, -size.height / 2f,
        size.width / 2f, size.height / 2f, -size.width / 2f, size.height / 2f)
    val destination = FloatArray(8)
    for (i in 0..3) {
        val x = buffer[i * 2] * cos - buffer[i * 2 + 1] * sin
        val y = buffer[i * 2] * sin + buffer[i * 2 + 1] * cos
        destination[i * 2] = view.width / 2f + x * scale * if (front) -1 else 1
        destination[i * 2 + 1] = view.height / 2f + y * scale
    }
    view.setTransform(Matrix().apply { setPolyToPoly(source, 0, destination, 0, 4) })
}
