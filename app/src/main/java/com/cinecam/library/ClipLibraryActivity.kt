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
import com.cinecam.ui.theme.CineCamTheme
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class Section(val label: String) {
    PHOTOS("ছবি"), ARCHIVE("Lossless archive"), FILES("ফাইল ও রিপোর্ট")
}

/** Read-only capture browser. Photos and lossless archives have separate sections. */
class ClipLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CineCamTheme { Surface(Modifier.fillMaxSize()) { Library() } } }
    }

    private data class Frame(val exported: String, val index: Int, val target: PreviewTarget)

    @Composable
    private fun Library() {
        val context = LocalContext.current
        val prefs = remember { getSharedPreferences("clip_library", Context.MODE_PRIVATE) }
        var tree by remember { mutableStateOf(prefs.getString("tree", null)) }
        var refresh by remember { mutableIntStateOf(0) }
        var section by rememberSaveable { mutableStateOf(Section.PHOTOS.name) }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var viewerIndex by remember { mutableStateOf<Int?>(null) }
        var folder by remember { mutableStateOf<ClipFolder?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var actionError by remember { mutableStateOf<String?>(null) }
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
                    tree = uri.toString(); selected = null; viewerIndex = null; refresh++
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
        val clip = folder?.clips?.firstOrNull { it.id == selected }
        val frames = remember(clip, folder) { framesOf(clip, folder) }
        BackHandler(enabled = selected != null) { selected = null; viewerIndex = null }
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
        val currentSection = Section.entries.firstOrNull { it.name == section } ?: Section.PHOTOS

        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Manage clips", style = MaterialTheme.typography.headlineSmall)
                Row {
                    TextButton(onClick = {
                        when {
                            viewerIndex != null -> viewerIndex = null
                            selected != null -> selected = null
                            else -> finish()
                        }
                    }) {
                        Text(if (selected != null) "তালিকায় ফিরুন" else "ক্যামেরায় ফিরুন")
                    }
                    TextButton(onClick = { refresh++ }, enabled = tree != null && !loading) { Text("Refresh") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Section.entries.forEach { entry ->
                        FilterChip(selected = currentSection == entry, onClick = {
                            section = entry.name
                            if (entry != Section.PHOTOS) viewerIndex = null
                        }, label = { Text(entry.label) })
                    }
                }
                TextButton(onClick = { picker.launch(tree?.let { Uri.parse(it) }) }) { Text("CineCam folder বাছুন") }
                Text("Folder: Download/CineCam • read-only; কোনো ফাইল সরানো, মুছে ফেলা বা বদলানো হয় না।")
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Capture তালিকা পড়া হচ্ছে…") }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            actionError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            items(folder?.notices ?: emptyList()) { Text(it, color = MaterialTheme.colorScheme.error) }
            if (folder != null && folder!!.clips.isEmpty()) {
                item { Text("এই folder-এ সমর্থিত capture report পাওয়া যায়নি। সরাসরি flat CineCam folder বাছুন।") }
            }

            when (currentSection) {
                Section.PHOTOS -> {
                    if (clip == null) {
                        item {
                            Text("ছবি — প্রতিটি capture-এর RAW still preview", style = MaterialTheme.typography.titleMedium)
                            Text("প্রতিটি DNG-এর পাশাপাশি আমাদের নিজস্ব verified RAW frame থেকেও preview তৈরি হয়, তাই DNG viewer না থাকলেও ছবি দেখা যাবে।")
                        }
                        items(folder?.clips ?: emptyList(), key = { it.id }) { entry ->
                            val entryFrames = framesOf(entry, folder)
                            val present = entry.files.count { folder?.documents?.containsKey(it.exported) == true }
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FrameThumbnail(entryFrames.firstOrNull()?.target, 240,
                                        Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(DateFormat.getDateTimeInstance().format(Date(entry.epochMs)),
                                            style = MaterialTheme.typography.titleMedium)
                                        Text(entry.id)
                                        Text("${entry.written}/${entry.requested} frame • ${entryFrames.size}টি ছবি")
                                        Text("RAW ${size(entry.rawBytes)} • companion $present/${entry.files.size}")
                                        Button(onClick = { selected = entry.id; actionError = null }) {
                                            Text("ছবিগুলো দেখুন (${entryFrames.size})")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            Text(clip.id, style = MaterialTheme.typography.titleMedium)
                            Text("${frames.size}টি RAW still • ${clip.written}/${clip.requested} লেখা frame • drop ${clip.drops}")
                            TextButton(onClick = { viewerIndex = 0 }, enabled = frames.isNotEmpty()) { Text("বড় করে দেখুন (1/${frames.size})") }
                            Text("Preview DNG বা একই capture-এর verified .ccraw frame থেকে; মূল ফাইল অপরিবর্তিত।")
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                frames.chunked(3).forEachIndexed { rowIndex, row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEachIndexed { column, frame ->
                                            val number = rowIndex * 3 + column
                                            OutlinedCard(Modifier.weight(1f)) {
                                                Column(Modifier.padding(6.dp)) {
                                                    FrameThumbnail(frame.target, 260,
                                                        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(6.dp)))
                                                    Text("ছবি ${frame.index + 1}", style = MaterialTheme.typography.labelLarge)
                                                    TextButton(onClick = { viewerIndex = number }) { Text("খুলুন") }
                                                }
                                            }
                                        }
                                        while (row.size < 3) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                                if (frames.isEmpty()) Text("এই capture-এ কোনো DNG পাওয়া যায়নি; archive section দেখুন।")
                            }
                        }
                    }
                }
                Section.ARCHIVE -> {
                    item {
                        Text("Lossless .ccraw.bp16 archive", style = MaterialTheme.typography.titleMedium)
                        Text("এটি মূল CCRAW payload-এর lossless compressed backup—playable video নয়। মূল RAW/DNG আলাদা থাকে; archive চাইলে শেয়ার বা বাইরে রাখা যায়।")
                    }
                    val withArchive = (folder?.clips ?: emptyList()).filter { it.archiveBytes != null }
                    if (withArchive.isEmpty()) item { Text("কোনো capture-এ lossless archive পাওয়া যায়নি।") }
                    items(withArchive, key = { it.id }) { entry ->
                        val document = folder?.documents?.get("${entry.id}_capture.ccraw.bp16")
                        val ratio = entry.archiveBytes?.let { archive ->
                            if (entry.rawBytes > 0) String.format(Locale.ROOT, "%.1f%% ছোট", 100.0 * (1 - archive.toDouble() / entry.rawBytes)) else "—"
                        } ?: "—"
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(DateFormat.getDateTimeInstance().format(Date(entry.epochMs)),
                                    style = MaterialTheme.typography.titleMedium)
                                Text(entry.id)
                                Text("RAW ${size(entry.rawBytes)} → archive ${size(entry.archiveBytes!!)} • $ratio")
                                Text("রিপোর্টে archive যাচাই: ${result(entry.archive)}")
                                Text(if (document == null) "Archive ফাইল পাওয়া যায়নি" else document.bytes?.let { "ফাইলে ${size(it)}" } ?: "Size অজানা")
                                Text("সংরক্ষণ করা archive restore/verify করতে native tool লাগবে; এই screen শুধু দেখায় ও শেয়ার করে।",
                                    style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { selected = entry.id; section = Section.FILES.name },
                                        enabled = document != null) { Text("ফাইল বিস্তারিত") }
                                    TextButton(onClick = { if (document != null) openExternal("application/octet-stream", true, document) },
                                        enabled = document != null) { Text("শেয়ার") }
                                }
                            }
                        }
                    }
                }
                Section.FILES -> {
                    if (clip == null) {
                        item {
                            Text("ফাইল ও রিপোর্ট", style = MaterialTheme.typography.titleMedium)
                            Text("যেকোনো capture-এর সব companion file, saved যাচাইয়ের ফল এবং সহজ বাংলা রিপোর্ট।")
                        }
                        items(folder?.clips ?: emptyList(), key = { it.id }) { entry ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(DateFormat.getDateTimeInstance().format(Date(entry.epochMs)))
                                    Text(entry.id, style = MaterialTheme.typography.titleSmall)
                                    Text("${entry.files.size}টি companion file")
                                    Button(onClick = { selected = entry.id }) { Text("বিস্তারিত দেখুন") }
                                }
                            }
                        }
                    } else {
                        item {
                            Text(clip.id, style = MaterialTheme.typography.titleMedium)
                            Text("Frame ${clip.written}/${clip.requested}; native drop ${clip.drops}")
                            Text("শেষ হওয়ার কারণ: ${clip.reason}")
                            Text("RAW ${size(clip.rawBytes)}; archive ${clip.archiveBytes?.let { size(it) } ?: "নেই"}")
                            Text("রিপোর্টে RAW যাচাই: ${result(clip.readback)}; DNG: ${result(clip.dng)}; archive: ${result(clip.archive)}")
                            Text("উপরের ফল saved report-এর; এখন payload আবার যাচাই করা হয়নি।")
                            TextButton(onClick = {
                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("CineCam capture report", clip.readable))
                            }) { Text("সহজ রিপোর্ট কপি") }
                        }
                        items(clip.files, key = { it.exported }) { file ->
                            val document = folder?.documents?.get(file.exported)
                            val frame = frames.firstOrNull { it.exported == file.exported }
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(file.original, style = MaterialTheme.typography.titleSmall)
                                    Text(if (document == null) "পাওয়া যায়নি / একই নাম একাধিকবার" else document.bytes?.let { size(it) } ?: "Size অজানা")
                                    Row {
                                        if (frame != null) TextButton(onClick = { viewerIndex = frames.indexOf(frame) }) { Text("ছবি দেখুন") }
                                        TextButton(onClick = {
                                            if (document != null) openExternal(mimeOf(file.original), false, document)
                                        }, enabled = document != null) { Text("বাইরে খুলুন") }
                                        TextButton(onClick = {
                                            if (document != null) openExternal(mimeOf(file.original), true, document)
                                        }, enabled = document != null) { Text("শেয়ার") }
                                    }
                                }
                            }
                        }
                        item { Text("Saved সহজ রিপোর্ট", style = MaterialTheme.typography.titleMedium); Text(clip.readable) }
                    }
                }
            }
        }
        val current = viewerIndex
        if (current != null && frames.isNotEmpty()) {
            FrameViewer(frames, current.coerceIn(0, frames.lastIndex)) { viewerIndex = null }
        }
    }

    private fun framesOf(clip: SavedClip?, folder: ClipFolder?): List<Frame> {
        if (clip == null || folder == null) return emptyList()
        val ccraw = folder.documents["${clip.id}_capture.ccraw"]?.uri
        return clip.files.filter { it.original.endsWith(".dng") }.mapIndexedNotNull { position, file ->
            val document = folder.documents[file.exported] ?: return@mapIndexedNotNull null
            Frame(file.exported, position, PreviewTarget(document.uri, ccraw, position, clip.sensorOrientation ?: 6))
        }
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.')) {
        "dng" -> "image/x-adobe-dng"
        "json" -> "application/json"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    @Composable
    private fun FrameThumbnail(target: PreviewTarget?, maxDimension: Int, modifier: Modifier) {
        val context = LocalContext.current
        var bitmap by remember(target, maxDimension) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(target, maxDimension) { mutableStateOf(false) }
        var reason by remember(target, maxDimension) { mutableStateOf<String?>(null) }
        LaunchedEffect(target, maxDimension) {
            bitmap = null; failed = false; reason = null
            if (target == null) return@LaunchedEffect
            try { bitmap = PreviewLoader.load(context, target, maxDimension) }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { failed = true; reason = "memory কম" }
            catch (e: Throwable) { failed = true; reason = e.message }
        }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val image = bitmap
            when {
                image != null -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                failed -> Text(reason ?: "Preview নেই", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
                target != null -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                else -> Text("DNG নেই", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun FrameViewer(frames: List<Frame>, initial: Int, onClose: () -> Unit) {
        val context = LocalContext.current
        var index by rememberSaveable { mutableStateOf(initial.coerceIn(0, frames.lastIndex)) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var failed by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(frames, index) {
            bitmap = null; failed = null
            try { bitmap = PreviewLoader.load(context, frames[index].target, 1600) }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { failed = "এই ছবির preview-এর জন্য memory যথেষ্ট নয়" }
            catch (e: Throwable) { failed = e.message ?: "এই ছবির preview তৈরি করা যায়নি" }
        }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ছবি ${index + 1}/${frames.size}", color = Color.White,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                        Text("preview; মূল ফাইল বদলায় না", color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f))
                        TextButton(enabled = index < frames.lastIndex, onClick = { index++ }) { Text("পরের ▶") }
                    }
                }
            }
        }
    }

    private fun size(bytes: Long) = String.format(Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)
    private fun result(flag: Boolean?) = when (flag) { true -> "সফল"; false -> "ব্যর্থ"; null -> "তথ্য নেই" }
}
