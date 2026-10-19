package com.cinecam.ui.screens

import android.Manifest
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import com.cinecam.camera.CameraCapabilities
import com.cinecam.engine.NativeRawEngine
import com.cinecam.ui.components.ManualControlPanel
import com.cinecam.ui.components.FpsSelector
import com.cinecam.ui.components.RecordButton
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * CameraScreen - Main CineCam UI
 * Jetpack Compose + TextureView preview + Manual controls + RAW engine stats
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }

    // Permission state
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    )
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) permissionsState.launchMultiplePermissionRequest()
    }

    // Device capability check (once)
    val capInfos = remember {
        try { CameraCapabilities.checkAllCameras(context) } catch (e: Exception) { Log.e("CameraScreen","cap check failed",e); emptyList() }
    }
    val bestCamera = remember(capInfos) { CameraCapabilities.pickBestRawCamera(capInfos) }

    // Manual controls state
    var iso by remember { mutableIntStateOf(200) }
    var shutterNs by remember { mutableLongStateOf(10_000_000L) } // 1/100s
    var focusDistance by remember { mutableStateOf<Float?>(null) }
    var wbKelvin by remember { mutableIntStateOf(5500) }
    var selectedFps by remember { mutableIntStateOf(30) }
    var isRecording by remember { mutableStateOf(false) }
    var statsJson by remember { mutableStateOf("{}") }

    val engine = remember { NativeRawEngine.getInstance() }

    // Poll engine stats when recording
    LaunchedEffect(isRecording) {
        while (isRecording) {
            statsJson = engine.getStatsJson()
            delay(500)
        }
    }

    // Initial engine init (lazy)
    LaunchedEffect(bestCamera) {
        val info = bestCamera
        if (info != null) {
            val size = info.maxRawSize
            if (size != null) {
                val ok = engine.initialize(size.width, size.height, bitDepth = 10, poolSize = 60)
                Log.i("CameraScreen", "Engine init ${size.width}x${size.height} -> $ok")
            }
        } else {
            // Fallback 1920x1080
            engine.initialize(1920, 1080, 10, 30)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Preview layer (TextureView fallback - will be replaced with GLSurfaceView for Vulkan in Phase 2)
        if (permissionsState.allPermissionsGranted) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        // SurfaceTextureListener will be wired to CameraController in Phase 2
                        // For now placeholder black preview with capability overlay
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Permission prompt
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ক্যামেরা অনুমতি প্রয়োজন", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("RAW ক্যাপচারের জন্য CAMERA + MIC অনুমতি দিন", color = Color(0xFF9A9A9E), fontSize = 13.sp)
                    Button(onClick = { permissionsState.launchMultiplePermissionRequest() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))) {
                        Text("অনুমতি দিন")
                    }
                }
            }
        }

        // Top bar - Device capability
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(12.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("CineCam RAW", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = bestCamera?.let { "${it.hardwareLevelString()} • RAW_SENSOR=${it.supportsRawSensor} • ${it.maxRawSize}" } ?: "ক্যামেরা যাচাই হচ্ছে...",
                        color = if (bestCamera?.isCinemaReady == true) Color(0xFF00E5FF) else Color(0xFFFFB74D),
                        fontSize = 10.sp
                    )
                }
                // FPS Selector compact
                FpsSelector(selectedFps = selectedFps, onFpsSelected = { selectedFps = it })
            }
            Spacer(Modifier.height(6.dp))
            // Stats line (engine)
            Text(
                text = "Engine: ${engine.getVersion()} | Stats: $statsJson",
                color = Color(0xFF9A9A9E), fontSize = 9.sp, maxLines = 2
            )
        }

        // Center - tap to toggle controls
        Box(
            modifier = Modifier.fillMaxSize().padding(top = 80.dp, bottom = 200.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!permissionsState.allPermissionsGranted) {
                // Already handled
            } else if (bestCamera?.supportsRawSensor == false) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)), modifier = Modifier.padding(24.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⚠️ এই ডিভাইস RAW_SENSOR সাপোর্ট করে না", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("• YUV ফলব্যাক মোডে প্রিভিউ চলবে\n• DNG ক্যাপচার সীমিত হতে পারে\n• LEVEL_3 / FULL ডিভাইসে সেরা ফল পাবেন", color = Color.White, fontSize = 11.sp)
                    }
                }
            } else {
                // Preview placeholder with crosshair
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("● PREVIEW", color = Color(0x33FFFFFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            // Manual panel (collapsible)
            if (showControls) {
                ManualControlPanel(
                    iso = iso, onIsoChange = { iso = it },
                    shutterNs = shutterNs, onShutterChange = { shutterNs = it },
                    focusDistance = focusDistance, onFocusChange = { focusDistance = it },
                    wbKelvin = wbKelvin, onWbChange = { wbKelvin = it },
                    isRecording = isRecording
                )
            }

            // Record bar
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0A0A)).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: toggle controls + thumb
                Column {
                    TextButton(onClick = { showControls = !showControls }) {
                        Text(if (showControls) "CONTROLS ▲" else "CONTROLS ▼", color = Color(0xFFE50914), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("${selectedFps}fps • ${wbKelvin}K • ISO $iso", color = Color(0xFF9A9A9E), fontSize = 10.sp)
                }

                // Center: Record button
                RecordButton(isRecording = isRecording, onToggle = {
                    if (!isRecording) {
                        // Start recording
                        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        val sessionName = "CineCam_${sdf.format(Date())}"
                        val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "CineCam/$sessionName")
                        baseDir.mkdirs()
                        val container = "mcraw" // Toggle to "cinemadng" for DNG sequence
                        val ok = engine.startRecording(
                            width = bestCamera?.maxRawSize?.width ?: 1920,
                            height = bestCamera?.maxRawSize?.height ?: 1080,
                            fps = selectedFps, bitDepth = 10,
                            outputPath = baseDir.absolutePath,
                            container = container
                        )
                        if (ok) {
                            isRecording = true
                            Toast.makeText(context, "রেকর্ডিং শুরু: $sessionName", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "রেকর্ডিং ব্যর্থ: ${engine.getLastError()}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        engine.stopRecording()
                        isRecording = false
                        Toast.makeText(context, "রেকর্ডিং সংরক্ষিত", Toast.LENGTH_SHORT).show()
                    }
                })

                // Right: DNG capture + gallery
                Column(horizontalAlignment = Alignment.End) {
                    Button(
                        onClick = {
                            Toast.makeText(context, "DNG ক্যাপচার (Phase 3-এ সক্রিয় হবে)", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("DNG", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(if (isRecording) "REC ●" else "STANDBY", color = if (isRecording) Color.Red else Color(0xFF9A9A9E), fontSize = 10.sp)
                }
            }
        }
    }
}
