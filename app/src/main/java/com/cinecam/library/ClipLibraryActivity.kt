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

/** Read-only capture browser with native DNG previews. No migration, deletion, MP4 or audio. */
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
                } catch (_: Exception) { actionError = "Folder access রাখা যায়নি; আবার folder বাছুন।" }
            }
        }
        LaunchedEffect(tree, refresh) {
            val location = tree
            error = null; actionError = null
            if (location == null) { folder = null; return@LaunchedEffect }
            loading = true
            try { folder = ClipFolderReader.read(context.applicationContext, Uri.parse(location)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { folder = null; error = "Folder পড়া যায়নি। Access বদলে গেলে আবার folder বাছুন।" }
            finally { loading = false }
        }
        val clip = folder?.clips?.firstOrNull { it.id == selected }
        val dngUris = remember(clip, folder) {
            clip?.files?.filter { it.original.endsWith(".dng") }
                ?.mapNotNull { folder?.documents?.get(it.exported)?.uri } ?: emptyList()
        }
        BackHandler(enabled = selected != null) { selected = null; viewerIndex = null }
        fun openExternal(mime: String, share: Boolean, document: FolderDocument) {
            try {
                val intent = if (share) Intent(Intent.ACTION_SEND).apply {
                    type = mime; putExtra(Intent.EXTRA_STREAM, document.uri)
                } else Intent(Intent.ACTION_VIEW).setDataAndType(document.uri, mime)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("CineCam file", document.uri)
                startActivity(Intent.createChooser(intent, if (share) "File শেয়ার করুন" else "অন্য অ্যাপে খুলুন"))
            } catch (_: Exception) { actionError = "File খোলার app বা access পাওয়া যায়নি। Folder refresh করে দেখুন।" }
        }
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Manage clips", style = MaterialTheme.typography.headlineSmall)
                Row {
                    TextButton(onClick = { if (selected != null) { selected = null; viewerIndex = null } else finish() }) {
                        Text(if (selected != null) "তালিকায় ফিরুন" else "ক্যামেরায় ফিরুন")
                    }
                    TextButton(onClick = { refresh++ }, enabled = tree != null && !loading) { Text("Refresh") }
                }
                Text("Download/CineCam folder একবার বাছুন। পুরোনো flat capture-ও দেখা যাবে; ফাইল সরানো/মুছে ফেলা হবে না।")
                TextButton(onClick = { picker.launch(tree?.let { Uri.parse(it) }) }) { Text("CineCam folder বাছুন") }
                Text("DNG thumbnail চাপলে অ্যাপের ভিতর RAW ছবি দেখা যাবে। RAW/archive ফাইল নিঃশব্দে photo হিসেবে খোলা হয় না; এখনো continuous video বা MP4 renderer নেই।")
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Capture তালিকা পড়া হচ্ছে…") }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            actionError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (clip == null) {
                if (folder != null && folder!!.clips.isEmpty()) item { Text("এই folder-এ সমর্থিত capture report পাওয়া যায়নি। সরাসরি flat CineCam folder বাছুন।") }
                items(folder?.notices ?: emptyList()) { Text(it, color = MaterialTheme.colorScheme.error) }
                items(folder?.clips ?: emptyList(), key = { it.id }) { entry ->
                    val cover = folder?.documents?.get(entry.files.firstOrNull { it.original.endsWith(".dng") }?.exported)
                    val present = entry.files.count { folder?.documents?.containsKey(it.exported) == true }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DngThumbnail(cover?.uri, maxDimension = 220,
                                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(DateFormat.getDateTimeInstance().format(Date(entry.epochMs)), style = MaterialTheme.typography.titleMedium)
                                Text(entry.id)
                                Text("${entry.written}/${entry.requested} frame • drop ${entry.drops} • RAW ${size(entry.rawBytes)}")
                                entry.archiveBytes?.let { Text("Archive ${size(it)}") }
                                Text("Companion files: $present/${entry.files.size} পাওয়া গেছে")
                                Button(onClick = { selected = entry.id; actionError = null }) { Text("বিস্তারিত দেখুন") }
                            }
                        }
                    }
                }
            } else {
                item {
                    Text(clip.id, style = MaterialTheme.typography.titleMedium)
                    val cover = folder?.documents?.get(clip.files.firstOrNull { it.original.endsWith(".dng") }?.exported)
                    DngThumbnail(cover?.uri, maxDimension = 900,
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)))
                    Row {
                        TextButton(onClick = { viewerIndex = 0 }, enabled = dngUris.isNotEmpty()) {
                            Text("DNG ছবি দেখুন (1/${dngUris.size})")
                        }
                        TextButton(onClick = {
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("CineCam capture report", clip.readable))
                        }) { Text("সহজ রিপোর্ট কপি") }
                    }
                    Text("Frame ${clip.written}/${clip.requested}; native drop ${clip.drops}")
                    Text("শেষ হওয়ার কারণ: ${clip.reason}")
                    Text("RAW ${size(clip.rawBytes)}; archive ${clip.archiveBytes?.let { size(it) } ?: "নেই"}")
                    Text("রিপোর্টে RAW যাচাই: ${result(clip.readback)}; DNG: ${result(clip.dng)}; archive: ${result(clip.archive)}")
                    Text("উপরের ফল saved report-এর; এখন payload আবার যাচাই করা হয়নি। তালিকায় file থাকা মানে bytes অক্ষত যাচাই নয়।")
                    Text("RAW/archive সাধারণ video নয়। preview app-এর ভিতরের native DNG render; সম্পূর্ণ RAW editor নয়।")
                }
                items(clip.files, key = { it.exported }) { file ->
                    val document = folder?.documents?.get(file.exported)
                    val dngIndex = if (file.original.endsWith(".dng")) {
                        clip.files.filter { it.original.endsWith(".dng") }.indexOfFirst { it.exported == file.exported }
                    } else -1
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(file.original, style = MaterialTheme.typography.titleSmall)
                            Text(file.exported)
                            Text(if (document == null) "পাওয়া যায়নি / একই নাম একাধিকবার আছে" else document.bytes?.let { size(it) } ?: "Size অজানা")
                            Row {
                                if (dngIndex >= 0) {
                                    TextButton(onClick = { viewerIndex = dngIndex }, enabled = document != null) { Text("অ্যাপে দেখুন") }
                                }
                                val mime = when (file.original.substringAfterLast('.')) {
                                    "dng" -> "image/x-adobe-dng"
                                    "json" -> "application/json"
                                    "txt" -> "text/plain"
                                    else -> "application/octet-stream"
                                }
                                TextButton(onClick = { if (document != null) openExternal(mime, false, document) },
                                    enabled = document != null) { Text("বাইরে খুলুন") }
                                TextButton(onClick = { if (document != null) openExternal(mime, true, document) },
                                    enabled = document != null) { Text("শেয়ার") }
                            }
                        }
                    }
                }
                item { Text("Saved সহজ রিপোর্ট", style = MaterialTheme.typography.titleMedium); Text(clip.readable) }
            }
        }
        val current = viewerIndex
        if (current != null && dngUris.isNotEmpty()) {
            DngViewer(dngUris, current.coerceIn(0, dngUris.lastIndex)) { viewerIndex = null }
        }
    }

    @Composable
    private fun DngThumbnail(uri: Uri?, maxDimension: Int, modifier: Modifier) {
        val context = LocalContext.current
        var bitmap by remember(uri, maxDimension) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(uri, maxDimension) { mutableStateOf(false) }
        LaunchedEffect(uri, maxDimension) {
            bitmap = null; failed = false
            if (uri == null) return@LaunchedEffect
            try { bitmap = DngPreviewLoader.load(context, uri, maxDimension) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { failed = true }
        }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val image = bitmap
            when {
                image != null -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                failed -> Text("Preview নেই", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                uri != null -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                else -> Text("DNG নেই", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun DngViewer(uris: List<Uri>, initial: Int, onClose: () -> Unit) {
        val context = LocalContext.current
        var index by rememberSaveable { mutableStateOf(initial.coerceIn(0, uris.lastIndex)) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var failed by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(uris, index) {
            bitmap = null; failed = null
            try { bitmap = DngPreviewLoader.load(context, uris[index], 1600) }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { failed = "এই ছবির preview-এর জন্য memory যথেষ্ট নয়" }
            catch (_: Throwable) { failed = "এই DNG-এর preview তৈরি করা যায়নি" }
        }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DNG ছবি ${index + 1}/${uris.size}", color = Color.White,
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
                        Text("preview; মূল DNG বদলায় না", color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f))
                        TextButton(enabled = index < uris.lastIndex, onClick = { index++ }) { Text("পরের ▶") }
                    }
                }
            }
        }
    }

    private fun size(bytes: Long) = String.format(Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)
    private fun result(flag: Boolean?) = when (flag) { true -> "সফল"; false -> "ব্যর্থ"; null -> "করা হয়নি / তথ্য নেই" }
}
