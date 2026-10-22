package com.cinecam.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

internal data class FolderDocument(val name: String, val uri: Uri, val bytes: Long?)
internal data class ClipFolder(val clips: List<SavedClip>, val documents: Map<String, FolderDocument>, val notices: List<String>)
internal object ClipFolderReader {
    suspend fun read(context: Context, tree: Uri): ClipFolder = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        fun optionalText(document: FolderDocument?): String? {
            if (document == null) return null
            if (document.bytes != null && document.bytes > ClipCatalog.MAX_REPORT_BYTES) return null
            return try {
                val text = resolver.openInputStream(document.uri)?.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (bytes.size() + n > ClipCatalog.MAX_REPORT_BYTES) return null
                        bytes.write(buffer, 0, n)
                    }
                    bytes.toString("UTF-8")
                } ?: return null
                text
            } catch (_: Exception) { null }
        }
        val docs = mutableMapOf<String, FolderDocument>()
        val duplicates = mutableSetOf<String>()
        val notices = mutableListOf<String>()
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            var rows = 0
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (++rows > ClipCatalog.MAX_DOCUMENTS) {
                    notices.add("Folder বড়: প্রথম ${ClipCatalog.MAX_DOCUMENTS} entry দেখা হয়েছে। তালিকা অসম্পূর্ণ হতে পারে।")
                    break
                }
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(1) ?: continue
                val doc = FolderDocument(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                    if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it >= 0 })
                if (docs.put(name, doc) != null) duplicates.add(name)
            }
        } ?: throw IOException("Folder তালিকা পড়া যায়নি")
        duplicates.forEach { docs.remove(it) }
        if (duplicates.isNotEmpty()) notices.add("একই নামের ${duplicates.size} file বাদ দেওয়া হয়েছে; কোনোটিই পরিবর্তন করা হয়নি।")
        val candidates = docs.keys.filter { ClipCatalog.isReport(it) }.sortedDescending()
        if (candidates.size > ClipCatalog.MAX_REPORTS) notices.add("সর্বশেষ ${ClipCatalog.MAX_REPORTS}টি report দেখানো হচ্ছে।")
        val clips = mutableListOf<SavedClip>()
        for (name in candidates.take(ClipCatalog.MAX_REPORTS)) {
            currentCoroutineContext().ensureActive()
            try {
                val document = docs.getValue(name)
                require(document.bytes == null || document.bytes <= ClipCatalog.MAX_REPORT_BYTES) { "Report খুব বড়" }
                val text = resolver.openInputStream(document.uri)?.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(bytes.size() + n <= ClipCatalog.MAX_REPORT_BYTES) { "Report খুব বড়" }
                        bytes.write(buffer, 0, n)
                    }
                    bytes.toString("UTF-8")
                } ?: throw IOException("Report খোলা যায়নি")
                val clip = ClipCatalog.parse(name, text)
                val metadata = optionalText(docs[clip.id + "_metadata.json"])?.let(ClipCatalog::parseMetadata)
                clips.add(clip.copy(sensorOrientation = metadata?.sensorOrientation ?: clip.sensorOrientation,
                    metadata = metadata))
            } catch (e: org.json.JSONException) {
                notices.add("$name: report JSON সঠিক নয়")
            } catch (e: IllegalArgumentException) {
                notices.add("$name: ${e.message}")
            } catch (e: IllegalStateException) {
                notices.add("$name: ${e.message}")
            } catch (e: IOException) {
                notices.add("$name: report পড়া যায়নি")
            } catch (e: SecurityException) {
                notices.add("$name: access নেই; folder আবার বাছুন")
            }
        }
        ClipFolder(clips.sortedByDescending { it.epochMs }, docs, notices)
    }


}
