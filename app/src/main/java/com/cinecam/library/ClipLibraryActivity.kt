package com.cinecam.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cinecam.ui.theme.CineCamTheme
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Read-only capture browser. No file migration, deletion, playback or rendering. */
class ClipLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CineCamTheme { Surface(Modifier.fillMaxSize()) { Library() } } }
    }

    @Composable
    private fun Library() {
        val prefs = remember { getSharedPreferences("clip_library", Context.MODE_PRIVATE) }
        var tree by remember { mutableStateOf(prefs.getString("tree", null)) }
        var refresh by remember { mutableIntStateOf(0) }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var folder by remember { mutableStateOf<ClipFolder?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var actionError by remember { mutableStateOf<String?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val old = tree
                    prefs.edit().putString("tree", uri.toString()).apply()
                    tree = uri.toString(); selected = null; refresh++
                    if (old != null && old != tree) {
                        try { contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        catch (_: SecurityException) { }
                    }
                } catch (_: Exception) { actionError = "Folder access রাখা যায়নি; আবার folder বাছুন।" }
            }
        }
        LaunchedEffect(tree, refresh) {
            val location = tree
            folder = null; error = null; actionError = null
            if (location == null) return@LaunchedEffect
            loading = true
            try { folder = ClipFolderReader.read(applicationContext, Uri.parse(location)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Folder পড়া যায়নি। Access বদলে গেলে আবার folder বাছুন।" }
            finally { loading = false }
        }
        val clip = folder?.clips?.firstOrNull { it.id == selected }
        BackHandler(enabled = selected != null) { selected = null }
        fun external(document: FolderDocument, share: Boolean) {
            try {
                val mime = when (document.name.substringAfterLast('.')) {
                    "dng" -> "image/x-adobe-dng"
                    "json" -> "application/json"
                    "txt" -> "text/plain"
                    else -> "application/octet-stream"
                }
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
                    TextButton(onClick = { if (selected != null) selected = null else finish() }) {
                        Text(if (selected != null) "তালিকায় ফিরুন" else "ক্যামেরায় ফিরুন")
                    }
                    TextButton(onClick = { refresh++ }, enabled = tree != null && !loading) { Text("Refresh") }
                }
                Text("Download/CineCam folder একবার বাছুন। পুরোনো flat capture-ও দেখা যাবে; ফাইল সরানো/মুছে ফেলা হবে না।")
                TextButton(onClick = { picker.launch(tree?.let { Uri.parse(it) }) }) { Text("CineCam folder বাছুন") }
                Text("এটি saved RAW test capture-এর তালিকা; এখনো continuous video player বা MP4 renderer নয়।")
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Capture তালিকা পড়া হচ্ছে…") }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            actionError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (clip == null) {
                if (folder != null && folder!!.clips.isEmpty()) item { Text("এই folder-এ সমর্থিত capture report পাওয়া যায়নি। সরাসরি flat CineCam folder বাছুন।") }
                items(folder?.notices ?: emptyList()) { Text(it, color = MaterialTheme.colorScheme.error) }
                items(folder?.clips ?: emptyList(), key = { it.id }) { entry ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(DateFormat.getDateTimeInstance().format(Date(entry.epochMs)), style = MaterialTheme.typography.titleMedium)
                            Text(entry.id)
                            Text("${entry.written}/${entry.requested} frame • drop ${entry.drops} • RAW ${size(entry.rawBytes)}")
                            entry.archiveBytes?.let { Text("Archive ${size(it)}") }
                            val present = entry.files.count { folder?.documents?.containsKey(it.exported) == true }
                            Text("Companion files: $present/${entry.files.size} পাওয়া গেছে")
                            Button(onClick = { selected = entry.id; actionError = null }) { Text("বিস্তারিত দেখুন") }
                        }
                    }
                }
            } else {
                item {
                    Text(clip.id, style = MaterialTheme.typography.titleMedium)
                    Text("Frame ${clip.written}/${clip.requested}; native drop ${clip.drops}")
                    Text("শেষ হওয়ার কারণ: ${clip.reason}")
                    Text("RAW ${size(clip.rawBytes)}; archive ${clip.archiveBytes?.let { size(it) } ?: "নেই"}")
                    Text("রিপোর্টে RAW যাচাই: ${result(clip.readback)}; DNG: ${result(clip.dng)}; archive: ${result(clip.archive)}")
                    Text("উপরের ফল saved report-এর; এখন payload আবার যাচাই করা হয়নি। তালিকায় file থাকা মানে bytes অক্ষত যাচাই নয়।")
                    TextButton(onClick = {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("CineCam capture report", clip.readable))
                    }) { Text("সহজ রিপোর্ট কপি") }
                    Text("RAW/archive সাধারণ video নয়। DNG বাইরের compatible app-এ খুলতে পারবেন; in-app playback পরের ধাপ।")
                }
                items(clip.files, key = { it.exported }) { file ->
                    val document = folder?.documents?.get(file.exported)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(file.original, style = MaterialTheme.typography.titleSmall)
                            Text(file.exported)
                            Text(if (document == null) "পাওয়া যায়নি / একই নাম একাধিকবার আছে" else document.bytes?.let { size(it) } ?: "Size অজানা")
                            Row {
                                TextButton(onClick = { document?.let { external(it, false) } }, enabled = document != null) { Text("অন্য অ্যাপে খুলুন") }
                                TextButton(onClick = { document?.let { external(it, true) } }, enabled = document != null) { Text("শেয়ার") }
                            }
                        }
                    }
                }
                item { Text("Saved সহজ রিপোর্ট", style = MaterialTheme.typography.titleMedium); Text(clip.readable) }
            }
        }
    }
    private fun size(bytes: Long) = String.format(Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)
    private fun result(flag: Boolean?) = when (flag) { true -> "সফল"; false -> "ব্যর্থ"; null -> "করা হয়নি / তথ্য নেই" }
}
