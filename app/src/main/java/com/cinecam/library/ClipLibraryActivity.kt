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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.cinecam.render.RenderOptions
import com.cinecam.render.RenderOutput
import com.cinecam.render.RenderPacing
import com.cinecam.render.RenderQuality
import com.cinecam.render.RenderResolution
import com.cinecam.ui.theme.CineCamTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Reads at most [ClipCatalog.MAX_REPORT_BYTES]; a report is never loaded whole into memory. */
private fun readReportText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(ClipCatalog.MAX_REPORT_BYTES)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        if (total == 0) null else String(buffer, 0, total, Charsets.UTF_8)
    }
} catch (_: Exception) {
    null
}

private enum class Tab(val label: String) {
    PHOTOS("ছবি"), CLIPS("Clips"), ARCHIVE("Archive (.bp16)"), REPORT("Report")
}

private data class ViewerRequest(
    val targets: List<Pair<String, PreviewTarget>>, val initial: Int, val timestamps: List<Long> = emptyList()
)

private data class PhotoEntry(
    val clipId: String, val file: String, val frameIndex: Int, val document: FolderDocument,
    val target: PreviewTarget
)

/** A real file in the selected folder; the capture id is parsed from its name. */
private data class MediaDoc(val name: String, val clipId: String, val document: FolderDocument)

private const val VIEWER_DIM = 960
private val CLIP_ID = Regex("^(CineCam_[0-9]+_[a-f0-9]{8})_(.+)$")
private val RENDER_NAME = Regex("^(CineCam_[0-9]+_[a-f0-9]{8})_render_([0-9]+)\\.mp4$")

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
        var renderedVideo by remember { mutableStateOf<Pair<String, RenderOutput>?>(null) }
        var pendingRender by remember { mutableStateOf<Pair<String, List<Pair<String, PreviewTarget>>>?>(null) }
        var renderResolution by rememberSaveable {
            mutableStateOf(prefs.getString("render_res", null) ?: RenderResolution.HD.name)
        }
        var renderQuality by rememberSaveable {
            mutableStateOf(prefs.getString("render_quality", null) ?: RenderQuality.STANDARD.name)
        }
        var renderPacing by rememberSaveable {
            mutableStateOf(prefs.getString("render_pacing", null) ?: RenderPacing.REAL.name)
        }
        var headerFrames by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
        var extraMetadata by remember { mutableStateOf<Map<String, ClipMetadata>>(emptyMap()) }
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
        val clipById = remember(clips) { clips.associateBy { it.id } }
        val documents = folder?.documents ?: emptyMap()
        val currentTab = Tab.values().firstOrNull { it.name == tab } ?: Tab.PHOTOS
        val photos = remember(folder) { photosOf(folder) }
        val ccrawDocs = remember(folder) { mediaDocs(documents.values, "_capture.ccraw") }
        val archiveDocs = remember(folder) { mediaDocs(documents.values, "_capture.ccraw.bp16") }
        val reportDocs = remember(folder) { reportDocuments(folder) }
        val videos = remember(folder) {
            documents.values.filter { RENDER_NAME.matches(it.name) }.sortedByDescending { it.name }
        }
        val selectedPhotos = photos.filter { it.clipId == detail }

        // Reports can legitimately be missing (e.g. old/removed sidecars); read the CCRAW
        // header and optional metadata directly so every section still lists real files.
        LaunchedEffect(folder) {
            if (folder == null) { headerFrames = emptyMap(); extraMetadata = emptyMap(); return@LaunchedEffect }
            val pending = ccrawDocs.filter {
                (clipById[it.clipId]?.written ?: 0L) <= 0 && headerFrames[it.name] == null
            }
            val metaPending = documents.keys.filter { name ->
                name.endsWith("_metadata.json") && extraMetadata[clipIdOf(name) ?: ""] == null
            }
            if (pending.isEmpty() && metaPending.isEmpty()) return@LaunchedEffect
            val (frames, metas) = withContext(Dispatchers.IO) {
                val frameMap = mutableMapOf<String, Int>()
                for (doc in pending.take(100)) {
                    try {
                        context.contentResolver.openInputStream(doc.document.uri)?.use { input ->
                            val header = ByteArray(CcrawHeader.HEADER_BYTES)
                            var read = 0
                            while (read < header.size) {
                                val n = input.read(header, read, header.size - read)
                                if (n <= 0) break
                                read += n
                            }
                            if (read == header.size) {
                                CcrawHeader.parse(header, doc.document.bytes)?.let { frameMap[doc.name] = it.frames }
                            }
                        }
                    } catch (_: Exception) { }
                }
                val metaMap = mutableMapOf<String, ClipMetadata>()
                for (name in metaPending.take(100)) {
                    val id = clipIdOf(name) ?: continue
                    val document = documents[name] ?: continue
                    if (document.bytes != null && document.bytes > ClipCatalog.MAX_REPORT_BYTES) continue
                    try {
                        val text = readReportText(context, document.uri)
                        if (text != null) ClipCatalog.parseMetadata(text)?.let { metaMap[id] = it }
                    } catch (_: Exception) { }
                }
                frameMap to metaMap
            }
            if (frames.isNotEmpty()) headerFrames = headerFrames + frames
            if (metas.isNotEmpty()) extraMetadata = extraMetadata + metas
        }

        BackHandler(enabled = detail != null || viewer != null) {
            if (viewer != null) viewer = null else detail = null
        }
        fun openExternal(mime: String, share: Boolean, document: FolderDocument) {
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
        fun frameCount(doc: MediaDoc): Int {
            val fromClip = clipById[doc.clipId]?.written ?: 0L
            return if (fromClip > 0) fromClip.coerceAtMost(CcrawHeader.MAX_FRAMES.toLong()).toInt()
            else (headerFrames[doc.name] ?: 0)
        }
        fun metaFor(clipId: String): ClipMetadata? = clipById[clipId]?.metadata ?: extraMetadata[clipId]
        fun targetsFor(doc: MediaDoc, count: Int): List<Pair<String, PreviewTarget>> {
            val clip = clipById[doc.clipId]
            return (0 until count).map { index ->
                "Frame ${index + 1}/$count" to target(clipId = doc.clipId, frameIndex = index, dng = null,
                    ccraw = doc.document.uri, meta = metaFor(doc.clipId), clip = clip)
            }
        }
        fun renderVideo(clipId: String, targets: List<Pair<String, PreviewTarget>>,
                        options: RenderOptions = RenderOptions()) {
            if (rendering || targets.isEmpty()) return
            rendering = true
            renderedVideo = null
            renderStatus = "ভিডিও রেন্ডার শুরু হচ্ছে • $clipId • লম্বা দিক ≤ ${options.maxDimension} px"
            scope.launch {
                try {
                    val output = Mp4Renderer.render(context.applicationContext, clipId,
                        targets.map { it.second }, timestampsFor(clipById[clipId], targets.size), options) { done, total ->
                        withContext(Dispatchers.Main) { renderStatus = "ভিডিও রেন্ডার $done/$total • $clipId" }
                    }
                    renderedVideo = clipId to output
                    renderStatus = "ভিডিও তৈরি • $clipId: ${output.width}×${output.height}, ${output.frames} frame, " +
                        "${output.fps} fps, ${size(output.bytes)} • শব্দ নেই, preview-grade রঙ"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    renderStatus = "ভিডিও রেন্ডার ব্যর্থ • $clipId: ${e.message ?: "অজানা কারণ"}"
                } finally {
                    rendering = false
                }
            }
        }

        pendingRender?.let { request ->
            val (clipId, targets) = request
            AlertDialog(
                onDismissRequest = { if (!rendering) pendingRender = null },
                title = { Text("ভিডিও সেটিংস") },
                text = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$clipId • ${targets.size} frame", style = MaterialTheme.typography.bodySmall)
                        Text("রেজোলিউশন", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RenderResolution.values().forEach { option ->
                                FilterChip(selected = renderResolution == option.name,
                                    onClick = { renderResolution = option.name }, label = { Text(option.label) })
                            }
                        }
                        Text("কোয়ালিটি (ফাইল সাইজ বনাম বিশদ)", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RenderQuality.values().forEach { option ->
                                FilterChip(selected = renderQuality == option.name,
                                    onClick = { renderQuality = option.name }, label = { Text(option.label) })
                            }
                        }
                        Text("frame-এর সময়", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RenderPacing.values().forEach { option ->
                                FilterChip(selected = renderPacing == option.name,
                                    onClick = { renderPacing = option.name }, label = { Text(option.label) })
                            }
                        }
                        Text("আসল = sensor-এর নিজের burst timing; সমান = প্রতিটি frame সমান সময়ে (ছোট clip)। " +
                            "লম্বা দিক ≤ ${RenderResolution.of(renderResolution).maxDimension} px, শব্দ নেই।",
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !rendering, onClick = {
                        prefs.edit().putString("render_res", renderResolution)
                            .putString("render_quality", renderQuality)
                            .putString("render_pacing", renderPacing).apply()
                        val options = RenderOptions.of(RenderResolution.of(renderResolution),
                            RenderQuality.of(renderQuality), RenderPacing.of(renderPacing))
                        pendingRender = null
                        renderVideo(clipId, targets, options)
                    }) { Text("তৈরি করুন") }
                },
                dismissButton = {
                    TextButton(enabled = !rendering, onClick = { pendingRender = null }) { Text("বাতিল") }
                }
            )
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Tab.values().toList()) { entry ->
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
                    Text(status, color = if (status.contains("ব্যর্থ")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondary)
                    renderedVideo?.let { (id, video) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { shareVideo(video) }) { Text("ভিডিও শেয়ার") }
                            Text("$id • ${video.width}×${video.height} • ${video.frames} frame • ${video.fps} fps",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (folder != null && clips.isEmpty() && documents.isEmpty()) {
                item { Text("এই folder-এ কোনো ফাইল পাওয়া যায়নি। সরাসরি flat CineCam folder বাছুন।") }
            }

            when (currentTab) {
                Tab.PHOTOS -> {
                    item {
                        Text("ছবি — ${photos.size}টি DNG", style = MaterialTheme.typography.titleMedium)
                        Text("প্রতিটি DNG আলাদা। thumbnail চাপলে বড় ছবি ও আগের/পরের frame; DNG না পড়া গেলে verified .ccraw frame থেকে preview।")
                    }
                    if (detail == null) {
                        if (photos.isEmpty()) item { Text("কোনো DNG পাওয়া যায়নি (DNG sequence বন্ধ থাকলে হয় না)।") }
                        items(photos, key = { it.clipId + "/" + it.file }) { entry ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Thumb(entry.target, Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(entry.file, style = MaterialTheme.typography.titleSmall)
                                        Text(DateFormat.getDateTimeInstance().format(Date(epochOf(entry.clipId))))
                                        Text("ছবি ${entry.frameIndex + 1}/${photos.count { it.clipId == entry.clipId }} • " +
                                            (entry.document.bytes?.let { size(it) } ?: "size অজানা"))
                                        Row {
                                            Button(onClick = {
                                                viewer = ViewerRequest(
                                                    photos.filter { it.clipId == entry.clipId }.map { it.file to it.target },
                                                    entry.frameIndex, clipById[entry.clipId]?.frameTimestamps ?: emptyList())
                                            }) { Text("খুলুন") }
                                            TextButton(onClick = {
                                                clipById[entry.clipId]?.let { copyReport(it) }
                                            }, enabled = clipById[entry.clipId] != null) { Text("রিপোর্ট কপি") }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item { Text("ছবি • $detail (${selectedPhotos.size})", style = MaterialTheme.typography.titleMedium) }
                        if (selectedPhotos.isEmpty()) item { Text("এই capture-এ DNG নেই; Clips section থেকে RAW frame দেখা যাবে।") }
                        items(selectedPhotos, key = { it.file }) { entry ->
                            Column {
                                Thumb(entry.target, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)))
                                Text(entry.file)
                                TextButton(onClick = {
                                    viewer = ViewerRequest(selectedPhotos.map { it.file to it.target },
                                        entry.frameIndex, clipById[entry.clipId]?.frameTimestamps ?: emptyList())
                                }) { Text("বড় করে দেখুন (${entry.frameIndex + 1}/${selectedPhotos.size})") }
                                TextButton(enabled = !rendering, onClick = {
                                    pendingRender = entry.clipId to selectedPhotos.map { it.file to it.target }
                                }) { Text(if (rendering) "রেন্ডার…" else "MP4 তৈরি করুন (${selectedPhotos.size})") }
                            }
                        }
                        item { Text("মূল DNG/RAW/EXIF বদলানো হয় না; preview আনুমানিক রঙের।") }
                    }
                }

                Tab.CLIPS -> {
                    item {
                        Text("Clips — ${ccrawDocs.size}টি CCRAW clip", style = MaterialTheme.typography.titleMedium)
                        Text("এগুলোই আসল .ccraw ফাইল; RAW burst container, সরাসরি MP4 নয়। ফাইল তালিকা report-এর উপর নির্ভর করে না।")
                    }
                    if (ccrawDocs.isEmpty()) item { Text("এই folder-এ .ccraw ফাইল নেই।") }
                    items(ccrawDocs, key = { it.name }) { doc ->
                        val count = frameCount(doc)
                        val clip = clipById[doc.clipId]
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(doc.name, style = MaterialTheme.typography.titleSmall)
                                Text(DateFormat.getDateTimeInstance().format(Date(epochOf(doc.clipId))))
                                Text("$count frame • ${doc.document.bytes?.let { size(it) } ?: "size অজানা"}")
                                if (clip == null) Text("Report পাওয়া যায়নি—header থেকে frame সংখ্যা দেখানো হচ্ছে; রঙের calibration ছাড়া preview হবে।",
                                    style = MaterialTheme.typography.bodySmall)
                                else Text("রিপোর্টে saved RAW যাচাই: ${result(clip.readback)}")
                                Row {
                                    TextButton(enabled = count > 0, onClick = {
                                        viewer = ViewerRequest(targetsFor(doc, count), 0,
                                            clip?.frameTimestamps ?: emptyList())
                                    }) { Text("frame preview") }
                                    TextButton(enabled = count > 0 && !rendering, onClick = {
                                        pendingRender = doc.clipId to targetsFor(doc, count)
                                    }) { Text(if (rendering) "রেন্ডার…" else "MP4") }
                                    TextButton(onClick = { openExternal("application/octet-stream", true, doc.document) }) { Text("শেয়ার") }
                                    TextButton(enabled = clip != null, onClick = {
                                        detail = doc.clipId; tab = Tab.REPORT.name
                                    }) { Text("রিপোর্ট") }
                                }
                            }
                        }
                    }
                    item {
                        Text("রেন্ডার করা MP4 — ${videos.size}টি", style = MaterialTheme.typography.titleMedium)
                        Text("নতুন রেন্ডার সবসময় নতুন ফাইল; পুরোনো ভিডিও মুছে বা বদলানো হয় না।")
                    }
                    if (videos.isEmpty()) item { Text("এখনো কোনো রেন্ডার করা ভিডিও নেই।") }
                    items(videos, key = { it.name }) { video ->
                        val match = RENDER_NAME.matchEntire(video.name)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(video.name, style = MaterialTheme.typography.titleSmall)
                                Text("capture: ${match?.groupValues?.get(1) ?: "?"}")
                                Text(video.bytes?.let { size(it) } ?: "size অজানা")
                                Row {
                                    TextButton(onClick = { openExternal("video/mp4", false, video) }) { Text("চালান") }
                                    TextButton(onClick = { openExternal("video/mp4", true, video) }) { Text("শেয়ার") }
                                }
                            }
                        }
                    }
                }

                Tab.ARCHIVE -> {
                    item {
                        Text("Lossless archive — ${archiveDocs.size}টি .ccraw.bp16", style = MaterialTheme.typography.titleMedium)
                        Text("শুধু lossless compressed archive; মূল .ccraw/DNG আলাদা থাকে। এই section-এ .ccraw দেখানো হয় না।")
                    }
                    if (archiveDocs.isEmpty()) item { Text("এই folder-এ .ccraw.bp16 পাওয়া যায়নি (archive চালু থাকলে তৈরি হয়)।") }
                    items(archiveDocs, key = { it.name }) { doc ->
                        val clip = clipById[doc.clipId]
                        val archiveBytes = doc.document.bytes ?: clip?.archiveBytes
                        val rawBytes = clip?.rawBytes ?: 0L
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(doc.name, style = MaterialTheme.typography.titleSmall)
                                Text(DateFormat.getDateTimeInstance().format(Date(epochOf(doc.clipId))))
                                Text("archive ${archiveBytes?.let { size(it) } ?: "size অজানা"}" +
                                    if (rawBytes > 0 && archiveBytes != null)
                                        String.format(Locale.ROOT, " • RAW-এর %.2f%%", 100.0 * archiveBytes / rawBytes)
                                    else "")
                                if (clip != null && rawBytes > 0 && archiveBytes != null)
                                    Text(String.format(Locale.ROOT, "%.2f%% ছোট", 100.0 * (1 - archiveBytes.toDouble() / rawBytes)))
                                clip?.let { Text("রিপোর্টে archive যাচাই: ${result(it.archive)}") }
                                    ?: Text("Report পাওয়া যায়নি; ফাইলের মাপ দেখানো হচ্ছে।", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { openExternal("application/octet-stream", true, doc.document) }) { Text("শেয়ার") }
                                    TextButton(enabled = clip != null, onClick = {
                                        detail = doc.clipId; tab = Tab.REPORT.name
                                    }) { Text("রিপোর্ট") }
                                }
                            }
                        }
                    }
                }

                Tab.REPORT -> {
                    item {
                        Text("Report — ${reportDocs.size}টি sidecar", style = MaterialTheme.typography.titleMedium)
                        Text("report.json/txt, metadata.json ও sequence.json। যাচাইয়ের ফল ও সহজ রিপোর্ট।")
                    }
                    if (reportDocs.isEmpty()) item { Text("কোনো report ফাইল পাওয়া যায়নি।") }
                    items(reportDocs, key = { it.name }) { document ->
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

    private fun clipIdOf(name: String): String? = CLIP_ID.matchEntire(name)?.groupValues?.get(1)

    private fun epochOf(clipId: String): Long =
        clipId.split('_').getOrNull(1)?.toLongOrNull() ?: System.currentTimeMillis()

    private fun mediaDocs(all: Collection<FolderDocument>, suffix: String): List<MediaDoc> =
        all.filter { it.name.endsWith(suffix) }
            .mapNotNull { document ->
                val id = CLIP_ID.matchEntire(document.name)?.groupValues?.get(1) ?: return@mapNotNull null
                MediaDoc(document.name, id, document)
            }
            .sortedByDescending { it.name }

    private fun photosOf(folder: ClipFolder?): List<PhotoEntry> {
        if (folder == null) return emptyList()
        val byClip = folder.clips.associateBy { it.id }
        return folder.documents.values
            .filter { it.name.endsWith(".dng") }
            .mapNotNull { document ->
                val id = CLIP_ID.matchEntire(document.name)?.groupValues?.get(1) ?: return@mapNotNull null
                val clip = byClip[id]
                val index = clip?.files
                    ?.filter { it.original.endsWith(".dng") }
                    ?.indexOfFirst { it.exported == document.name }?.takeIf { it >= 0 } ?: -1
                val frameIndex = if (index >= 0) index else dngIndex(document.name)
                PhotoEntry(id, document.name, frameIndex, document,
                    target(id, frameIndex, document.uri, folder.documents[id + "_capture.ccraw"]?.uri, clip?.metadata, clip))
            }
            .sortedWith(compareByDescending<PhotoEntry> { epochOf(it.clipId) }.thenBy { it.file })
    }

    private fun dngIndex(name: String): Int {
        val match = Regex("_frame_([0-9]{6})\\.dng$").find(name) ?: return 0
        return ((match.groupValues[1].toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
    }

    private fun reportDocuments(folder: ClipFolder?): List<FolderDocument> {
        if (folder == null) return emptyList()
        val pattern = Regex("CineCam_[0-9]+_[a-f0-9]{8}_(report\\.(json|txt)|metadata\\.json|sequence\\.json)")
        return folder.documents.values.filter { pattern.matches(it.name) }.sortedByDescending { it.name }
    }

    private fun target(clipId: String, frameIndex: Int, dng: Uri?, ccraw: Uri?, meta: ClipMetadata?,
                       clip: SavedClip?): PreviewTarget {
        val frame = clip?.framesCalibration?.getOrNull(frameIndex)
        val white = frame?.white ?: meta?.whiteLevel ?: 0.0
        val black = frame?.black ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val neutral = frame?.let { doubleArrayOf(it.neutralR, it.neutralG, it.neutralB) }
        return PreviewTarget(dng, ccraw, frameIndex,
            clip?.sensorOrientation ?: meta?.sensorOrientation ?: 6,
            meta?.cfa ?: 0, neutral, white, black, meta?.colorMatrix, meta?.matrixMode ?: 0)
    }

    /** Real burst spacing; only extrapolates when a report lacks per-frame timestamps. */
    private fun timestampsFor(clip: SavedClip?, count: Int): List<Long> {
        val known = clip?.frameTimestamps ?: emptyList()
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
                        TextButton(onClick = { playing = !playing }) { Text(if (playing) "থামান" else "চালান") }
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
                        Text(if (request.targets.size > 1) "preview • ${spanText(request)}" else "preview; মূল ফাইল বদলায় না",
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
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun size(bytes: Long) = String.format(Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)
    private fun result(flag: Boolean?) = when (flag) { true -> "সফল"; false -> "ব্যর্থ"; null -> "তথ্য নেই" }
}
