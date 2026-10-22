package com.cinecam.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cinecam.render.Mp4Renderer
import com.cinecam.render.RenderOutput
import com.cinecam.ui.theme.CineCamTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) {
    PHOTOS("ছবি"), CLIPS("Clips"), ARCHIVE("Lossless archive"), REPORT("Report")
}

private data class PhotoEntry(
    val clip: SavedClip, val file: ClipFile, val frameIndex: Int,
    val document: FolderDocument, val target: PreviewTarget
)

private data class ViewerRequest(
    val targets: List<Pair<String, PreviewTarget>>, val initial: Int, val timestamps: List<Long> = emptyList()
)

private const val VIEWER_DIM = 960

/** Read-only four-section browser: DNG photos, CCRAW clips, BP16 archives, reports. */
class ClipLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CineCamTheme { Surface(Modifier.fillMaxSize()) { Library() } } }
    }

    @Composable
    private fun Library() {
        val context = LocalContext.current
        val prefs = remember { getSharedPreferences("clip_library", Context.MODE_PRIVATE) }
        var tree by remember { mutableStateOf(prefs.getString("tree", null)) }
        var refresh by remember { mutableIntStateOf(0) }
        var tab by rememberSaveable { mutableStateOf(Tab.PHOTOS.name) }
        var detail by rememberSaveable { mutableStateOf<String?>(null) }
        var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
        var folder by remember { mutableStateOf<ClipFolder?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var actionError by remember { mutableStateOf<String?>(null) }
        var rendering by remember { mutableStateOf(false) }
        var renderStatus by remember { mutableStateOf<String?>(null) }
        var renderedVideo by remember { mutableStateOf<RenderOutput?>(null) }
        val scope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            var first = true
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (first) first = false else refresh++
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val old = tree
                    prefs.edit().putString("tree", uri.toString()).apply()
                    tree = uri.toString(); detail = null; viewer = null; refresh++
                    if (old != null && old != tree) {
                        try { contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        catch (_: SecurityException) { }
                    }
                } catch (_: Exception) { actionError = "Folder access রাখা যায়নি; আবার folder বাছুন।" }
            }
        }
        LaunchedEffect(tree, refresh) {
            val location = tree
            error = null; actionError = null
            if (location == null) { folder = null; return@LaunchedEffect }
            loading = true
            try { folder = ClipFolderReader.read(context.applicationContext, Uri.parse(location)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { folder = null; error = "Folder পড়া যায়নি। Access বদলে গেলে আবার folder বাছুন।" }
            finally { loading = false }
        }
        val clips = folder?.clips ?: emptyList()
        val currentTab = Tab.values().firstOrNull { it.name == tab } ?: Tab.PHOTOS
        val photos = remember(folder) { photosOf(folder) }
        val reports = remember(folder) { reportDocuments(folder) }
        val selectedPhotos = photos.filter { it.clip.id == detail }
        BackHandler(enabled = detail != null || viewer != null) {
            if (viewer != null) viewer = null else detail = null
        }
        fun renderVideo(clip: SavedClip, targets: List<Pair<String, PreviewTarget>>) {
            if (rendering || targets.isEmpty()) return
            rendering = true
            renderedVideo = null
            renderStatus = "ভিডিও রেন্ডার শুরু হচ্ছে…"
            scope.launch {
                try {
                    val output = Mp4Renderer.render(context.applicationContext, clip.id,
                        targets.map { it.second }, timestampsFor(clip, targets.size)) { done, total ->
                        withContext(Dispatchers.Main) { renderStatus = "ভিডিও রেন্ডার $done/$total…" }
                    }
                    renderedVideo = output
                    renderStatus = "ভিডিও তৈরি: ${output.width}×${output.height}, ${output.frames} frame, " +
                        "${output.fps} fps, ${size(output.bytes)} • শব্দ নেই, preview-grade রঙ"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    renderStatus = "ভিডিও রেন্ডার ব্যর্থ: ${e.message ?: "অজানা কারণ"}"
                } finally {
                    rendering = false
                }
            }
        }
        val openExternal: (String, Boolean, FolderDocument) -> Unit = { mime, share, document ->
            try {
                val intent = if (share) Intent(Intent.ACTION_SEND).apply {
                    type = mime; putExtra(Intent.EXTRA_STREAM, document.uri)
                } else Intent(Intent.ACTION_VIEW).setDataAndType(document.uri, mime)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("CineCam file", document.uri)
                startActivity(Intent.createChooser(intent, if (share) "File শেয়ার করুন" else "অন্য অ্যাপে খুলুন"))
            } catch (_: Exception) { actionError = "File খোলার app বা access পাওয়া যায়নি। Folder refresh করে দেখুন।" }
        }
        fun shareVideo(video: RenderOutput) {
            val media = video.uri ?: run {
                actionError = "এই Android সংস্করণে video app-specific folder-এ আছে: ${video.path}"
                return
            }
            openExternal("video/mp4", true, FolderDocument(video.path.substringAfterLast('/'), media, video.bytes))
        }

        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Manage clips", style = MaterialTheme.typography.headlineSmall)
                Row {
                    TextButton(onClick = {
                        when {
                            viewer != null -> viewer = null
                            detail != null -> detail = null
                            else -> finish()
                        }
                    }) { Text(if (detail != null || viewer != null) "তালিকায় ফিরুন" else "ক্যামেরায় ফিরুন") }
                    TextButton(onClick = { refresh++ }, enabled = tree != null && !loading) { Text("Refresh") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Tab.values().forEach { entry ->
                        FilterChip(selected = currentTab == entry, onClick = {
                            tab = entry.name; detail = null; viewer = null
                        }, label = { Text(entry.label) })
                    }
                }
                TextButton(onClick = { picker.launch(tree?.let { Uri.parse(it) }) }) { Text("CineCam folder বাছুন") }
                Text("Download/CineCam • read-only; ফাইল সরানো, মুছে ফেলা বা বদলানো হয় না।")
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Capture তালিকা পড়া হচ্ছে…") }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            actionError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            items(folder?.notices ?: emptyList()) { Text(it, color = MaterialTheme.colorScheme.error) }
            renderStatus?.let { status ->
                item {
                    Text(status, color = if (status.contains("ব্যর্থ")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                    renderedVideo?.let { video ->
                        Row {
                            if (video.uri != null) TextButton(onClick = { shareVideo(video) }) { Text("ভিডিও শেয়ার") }
                            Text("${video.width}×${video.height} • ${video.frames} frame • ${video.fps} fps",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (folder != null && clips.isEmpty()) {
                item { Text("এই folder-এ সমর্থিত capture report পাওয়া যায়নি। সরাসরি flat CineCam folder বাছুন।") }
            }

            when (currentTab) {
                Tab.PHOTOS -> {
                    item {
                        Text("ছবি — ${photos.size}টি DNG", style = MaterialTheme.typography.titleMedium)
                        Text("প্রতিটি DNG আলাদা। thumbnail চাপলে বড় ছবি ও আগের/পরের frame; DNG না পড়া গেলে verified .ccraw frame থেকে preview।")
                    }
                    if (detail == null) {
                        if (photos.isEmpty()) item { Text("এখনো কোনো DNG ছবি পাওয়া যায়নি (DNG sequence বন্ধ থাকলে হয় না)।") }
                        items(photos, key = { it.clip.id + "/" + it.file.exported }) { entry ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Thumb(entry.target, Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(entry.file.exported, style = MaterialTheme.typography.titleSmall)
                                        Text(DateFormat.getDateTimeInstance().format(Date(entry.clip.epochMs)))
                                        Text("ছবি ${entry.frameIndex + 1}/${entry.clip.written} • ${entry.document.bytes?.let { size(it) } ?: "size অজানা"}")
                                        Row {
                                            Button(onClick = {
                                                viewer = ViewerRequest(
                                                    photos.filter { it.clip.id == entry.clip.id }
                                                        .map { it.file.exported to it.target },
                                                    entry.frameIndex, entry.clip.frameTimestamps
                                                )
                                            }) { Text("খুলুন") }
                                            TextButton(onClick = { copyReport(entry.clip) }) { Text("রিপোর্ট কপি") }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item { Text("ছবি • $detail (${selectedPhotos.size})", style = MaterialTheme.typography.titleMedium) }
                        if (selectedPhotos.isEmpty()) item { Text("এই capture-এ DNG নেই; Clips section-এ RAW frame preview দেখুন।") }
                        items(selectedPhotos, key = { it.file.exported }) { entry ->
                            Column {
                                Thumb(entry.target, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)))
                                Text(entry.file.exported)
                                TextButton(onClick = {
                                    viewer = ViewerRequest(selectedPhotos.map { it.file.exported to it.target },
                                        entry.frameIndex, entry.clip.frameTimestamps)
                                }) { Text("বড় করে দেখুন (${entry.frameIndex + 1}/${selectedPhotos.size})") }
                                TextButton(enabled = !rendering, onClick = {
                                    renderVideo(entry.clip, selectedPhotos.map { it.file.exported to it.target })
                                }) { Text(if (rendering) "রেন্ডার…" else "MP4 তৈরি করুন (${selectedPhotos.size})") }
                            }
                        }
                        item { Text("মূল DNG/RAW/EXIF বদলানো হয় না; preview আনুমানিক রঙের।") }
                    }
                }

                Tab.CLIPS -> {
                    item {
                        Text("Clips — ${clips.count { folder?.documents?.containsKey(it.id + "_capture.ccraw") == true }}টি CCRAW clip",
                            style = MaterialTheme.typography.titleMedium)
                        Text("RAW burst container; সরাসরি MP4 video নয়। preview আগের/পরের RAW frame দেখায়।")
                    }
                    val withClip = clips.filter { folder?.documents?.containsKey(it.id + "_capture.ccraw") == true }
                    if (withClip.isEmpty()) item { Text("কোনো .ccraw clip পাওয়া যায়নি।") }
                    items(withClip, key = { it.id }) { entry ->
                        val document = folder?.documents?.get(entry.id + "_capture.ccraw")!!
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.id + "_capture.ccraw", style = MaterialTheme.typography.titleSmall)
                                Text(DateFormat.getDateTimeInstance().format(Date(entry.epochMs)))
                                Text("${entry.written}/${entry.requested} frame • ${document.bytes?.let { size(it) } ?: "size অজানা"}")
                                Text("রিপোর্টে saved RAW যাচাই: ${result(entry.readback)}")
                                Row {
                                    TextButton(onClick = {
                                        val count = (entry.written.takeIf { it > 0 } ?: 1L).coerceAtMost(64L).toInt()
                                        viewer = ViewerRequest(ccrawTargets(entry, document.uri, count), 0,
                                            entry.frameTimestamps)
                                    }) { Text("frame preview") }
                                    TextButton(onClick = {
                                        val count = (entry.written.takeIf { it > 0 } ?: 1L).coerceAtMost(64L).toInt()
                                        renderVideo(entry, ccrawTargets(entry, document.uri, count))
                                    }, enabled = !rendering) { Text(if (rendering) "রেন্ডার…" else "MP4") }
                                    TextButton(onClick = { openExternal("application/octet-stream", true, document) }) { Text("শেয়ার") }
                                    TextButton(onClick = { detail = entry.id; tab = Tab.REPORT.name }) { Text("রিপোর্ট") }
                                }
                            }
                        }
                    }
                }

                Tab.ARCHIVE -> {
                    item {
                        Text("Lossless archive — .ccraw.bp16", style = MaterialTheme.typography.titleMedium)
                        Text("মূল CCRAW-এর lossless backup; playable video নয়। মূল RAW/DNG আলাদা থাকে।")
                    }
                    val withArchive = clips.filter { folder?.documents?.containsKey(it.id + "_capture.ccraw.bp16") == true }
                    if (withArchive.isEmpty()) item { Text("কোনো .ccraw.bp16 archive পাওয়া যায়নি।") }
                    items(withArchive, key = { it.id }) { entry ->
                        val document = folder?.documents?.get(entry.id + "_capture.ccraw.bp16")!!
                        val archiveBytes = entry.archiveBytes ?: document.bytes
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.id + "_capture.ccraw.bp16", style = MaterialTheme.typography.titleSmall)
                                Text(DateFormat.getDateTimeInstance().format(Date(entry.epochMs)))
                                Text("RAW ${size(entry.rawBytes)} → archive ${archiveBytes?.let { size(it) } ?: "size অজানা"}")
                                if (archiveBytes != null && entry.rawBytes > 0) {
                                    Text(String.format(Locale.ROOT, "%.2f%% ছোট", 100.0 * (1 - archiveBytes.toDouble() / entry.rawBytes)))
                                }
                                Text("রিপোর্টে archive যাচাই: ${result(entry.archive)}")
                                Row {
                                    TextButton(onClick = {
                                        val count = (entry.written.takeIf { it > 0 } ?: 1L).coerceAtMost(64L).toInt()
                                        renderVideo(entry, ccrawTargets(entry, document.uri, count))
                                    }, enabled = !rendering) { Text(if (rendering) "রেন্ডার…" else "MP4") }
                                    TextButton(onClick = { openExternal("application/octet-stream", true, document) }) { Text("শেয়ার") }
                                    TextButton(onClick = { detail = entry.id; tab = Tab.REPORT.name }) { Text("রিপোর্ট") }
                                }
                            }
                        }
                    }
                }

                Tab.REPORT -> {
                    item {
                        Text("Report — capture sidecar ফাইল", style = MaterialTheme.typography.titleMedium)
                        Text("report.json/txt, metadata.json ও sequence.json; যাচাইয়ের ফল ও সহজ রিপোর্ট।")
                    }
                    if (reports.isEmpty()) item { Text("কোনো report ফাইল পাওয়া যায়নি।") }
                    items(reports, key = { it.name }) { document ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(document.name, style = MaterialTheme.typography.titleSmall)
                                Text(document.bytes?.let { size(it) } ?: "size অজানা")
                                Row {
                                    TextButton(onClick = { openExternal(mimeOf(document.name), false, document) }) { Text("খুলুন") }
                                    TextButton(onClick = { openExternal(mimeOf(document.name), true, document) }) { Text("শেয়ার") }
                                }
                            }
                        }
                    }
                    val selected = clips.firstOrNull { it.id == detail }
                    if (selected != null) {
                        item {
                            Text("সহজ রিপোর্ট • ${selected.id}", style = MaterialTheme.typography.titleMedium)
                            Text("Frame ${selected.written}/${selected.requested}; drop ${selected.drops}")
                            TextButton(onClick = { copyReport(selected) }) { Text("রিপোর্ট কপি") }
                            Text(selected.readable)
                        }
                    }
                }
            }
        }
        val request = viewer
        if (request != null && request.targets.isNotEmpty()) {
            Viewer(request, request.initial.coerceIn(0, request.targets.lastIndex)) { viewer = null }
        }
    }

    private fun photosOf(folder: ClipFolder?): List<PhotoEntry> {
        if (folder == null) return emptyList()
        return folder.clips.sortedByDescending { it.epochMs }.flatMap { clip ->
            val ccraw = folder.documents[clip.id + "_capture.ccraw"]?.uri
            clip.files.filter { it.original.endsWith(".dng") }.mapIndexedNotNull { index, file ->
                val document = folder.documents[file.exported] ?: return@mapIndexedNotNull null
                PhotoEntry(clip, file, index, document, target(clip, index, document.uri, ccraw))
            }
        }
    }

    private fun reportDocuments(folder: ClipFolder?): List<FolderDocument> {
        if (folder == null) return emptyList()
        val pattern = Regex("CineCam_[0-9]+_[a-f0-9]{8}_(report\\.(json|txt)|metadata\\.json|sequence\\.json)")
        return folder.documents.values.filter { pattern.matches(it.name) }.sortedByDescending { it.name }
    }

    private fun target(clip: SavedClip, frameIndex: Int, dng: Uri?, ccraw: Uri?): PreviewTarget {
        val frame = clip.framesCalibration.getOrNull(frameIndex)
        val metadata = clip.metadata
        val white = frame?.white ?: metadata?.whiteLevel ?: 0.0
        val black = frame?.black ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val neutral = frame?.let { doubleArrayOf(it.neutralR, it.neutralG, it.neutralB) }
        return PreviewTarget(dng, ccraw, frameIndex,
            clip.sensorOrientation ?: metadata?.sensorOrientation ?: 6,
            metadata?.cfa ?: 0, neutral, white, black, metadata?.colorMatrix,
            metadata?.matrixMode ?: 0)
    }

    private fun ccrawTargets(clip: SavedClip, uri: Uri, count: Int): List<Pair<String, PreviewTarget>> =
        (0 until count).map { index -> "Frame ${index + 1}/$count" to target(clip, index, null, uri) }

    /** Real burst spacing; only extrapolates when a report lacks per-frame timestamps. */
    private fun timestampsFor(clip: SavedClip, count: Int): List<Long> {
        val known = clip.frameTimestamps
        if (known.size >= count) return known.take(count)
        val out = known.toMutableList()
        val step = if (known.size >= 2) ((known.last() - known.first()) / (known.size - 1)).coerceAtLeast(33_333_333L)
        else 333_333_333L
        var last = out.lastOrNull() ?: 1_000_000_000L
        while (out.size < count) { last += step; out.add(last) }
        return out
    }

    private fun copyReport(clip: SavedClip) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("CineCam capture report", clip.readable))
    }

    @Composable
    private fun Thumb(target: PreviewTarget, modifier: Modifier) {
        val context = LocalContext.current
        var bitmap by remember(target) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(target) { mutableStateOf<String?>(null) }
        LaunchedEffect(target) {
            bitmap = null; failed = null
            try { bitmap = PreviewLoader.load(context, target, 320) }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { failed = "memory কম" }
            catch (e: Throwable) { failed = e.message ?: "preview নেই" }
        }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val image = bitmap
            when {
                image != null -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                failed != null -> Text(failed!!, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(4.dp))
                else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }

    @Composable
    private fun Viewer(request: ViewerRequest, initial: Int, onClose: () -> Unit) {
        val context = LocalContext.current
        var index by remember(request) { mutableIntStateOf(initial.coerceIn(0, request.targets.lastIndex)) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var failed by remember { mutableStateOf<String?>(null) }
        var playing by remember(request) { mutableStateOf(false) }
        LaunchedEffect(request, index) {
            bitmap = null; failed = null
            try { bitmap = PreviewLoader.load(context, request.targets[index].second, VIEWER_DIM) }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { failed = "এই ছবির জন্য memory যথেষ্ট নয়" }
            catch (e: Throwable) { failed = e.message ?: "preview তৈরি করা যায়নি" }
            if (failed == null) request.targets.getOrNull(index + 1)?.let { next ->
                try { PreviewLoader.load(context, next.second, VIEWER_DIM) } catch (_: Throwable) { }
            }
        }
        // Burst playback uses real sensor timestamps, not an invented fixed FPS.
        LaunchedEffect(playing, index, request) {
            if (!playing) return@LaunchedEffect
            val stamps = request.timestamps
            val stepMs = if (index + 1 < stamps.size && index + 1 < request.targets.size)
                ((stamps[index + 1] - stamps[index]) / 1_000_000L).coerceIn(50L, 800L) else 400L
            kotlinx.coroutines.delay(stepMs)
            index = if (index + 1 < request.targets.size) index + 1 else 0
        }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${request.targets[index].first}  (${index + 1}/${request.targets.size})",
                            color = Color.White, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { playing = !playing }) {
                            Text(if (playing) "থামান" else "চালান")
                        }
                        TextButton(onClick = onClose) { Text("বন্ধ") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val image = bitmap
                        when {
                            image != null -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            failed != null -> Text(failed!!, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
                            else -> CircularProgressIndicator()
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = index > 0, onClick = { index-- }) { Text("◀ আগের") }
                        Text(
                            if (request.targets.size > 1) "preview • ${spanText(request)}" else "preview; মূল ফাইল বদলায় না",
                            color = Color.Gray, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        TextButton(enabled = index < request.targets.lastIndex, onClick = { index++ }) { Text("পরের ▶") }
                    }
                }
            }
        }
    }

    /** Average rate over the real timestamps; this is a burst, not sustained FPS. */
    private fun spanText(request: ViewerRequest): String {
        val stamps = request.timestamps
        if (stamps.size < 2) return "preview; মূল ফাইল বদলায় না"
        val spanMs = (stamps.last() - stamps.first()) / 1_000_000L
        if (spanMs <= 0) return "preview; মূল ফাইল বদলায় না"
        val fps = (stamps.size - 1) * 1000.0 / spanMs
        return String.format(Locale.ROOT, "%.1f fps burst • %.2f s", fps, spanMs / 1000.0)
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.')) {
        "dng" -> "image/x-adobe-dng"
        "json" -> "application/json"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun size(bytes: Long) = String.format(Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)
    private fun result(flag: Boolean?) = when (flag) { true -> "সফল"; false -> "ব্যর্থ"; null -> "তথ্য নেই" }
}
