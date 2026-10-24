package com.cinecam.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream

/**
 * Session output written straight into `Download/CineCam` as MediaStore pending rows.
 *
 * The old flow wrote a session to app staging and then copied every byte again to Download — for a
 * 1.3 GB clip that is a second 1.3 GB write plus dozens of per-file fsyncs. Here the CCRAW and every
 * DNG are created once and published (IS_PENDING cleared) as soon as they are verified. A failed
 * session deletes its pending rows, so a half-written clip never appears in the gallery.
 */
internal class DirectClipStore(private val context: Context, private val session: String) : ClipDngTarget {
    private data class Row(val localName: String, val displayName: String, val uri: Uri)

    private val rows = LinkedHashMap<String, Row>()
    private var sourceName: String? = null
    private var published = false

    val ccrawName: String = RawExportNames.name(session, "capture.ccraw")

    /** Creates the pending CCRAW row and returns its writable descriptor; the writer owns the bytes. */
    fun createCcraw(): ParcelFileDescriptor {
        sourceName = "capture.ccraw"
        return openDescriptor("capture.ccraw", RawExportNames.mimeOf("capture.ccraw"), "rw")
    }

    private fun openDescriptor(localName: String, mime: String, mode: String): ParcelFileDescriptor {
        val row = rows[localName] ?: create(localName, mime).also { rows[localName] = it }
        return context.contentResolver.openFileDescriptor(row.uri, mode)
            ?: error("Download descriptor খোলা যায়নি: ${row.displayName}")
    }

    private fun create(localName: String, mime: String): Row {
        val displayName = RawExportNames.name(session, localName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/CineCam")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Download entry তৈরি হয়নি: $displayName")
        // A provider must not silently rename a companion file or sequence references break.
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == displayName) {
                context.contentResolver.delete(uri, null, null)
                "Export name collision; source retained: $displayName"
            }
        } ?: error("Export name যাচাই হয়নি: $displayName")
        return Row(localName, displayName, uri)
    }

    // ClipDngTarget: DNG frames read the pending CCRAW and write their own pending rows.
    override fun openSource(): InputStream {
        val local = sourceName ?: error("RAW source নেই")
        val row = rows[local] ?: error("RAW source row নেই")
        // A descriptor stream keeps `skip(payloadOffset)` an lseek instead of a full re-read.
        val descriptor = context.contentResolver.openFileDescriptor(row.uri, "r")
            ?: error("RAW source খোলা যায়নি")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    override fun openOutput(name: String): OutputStream {
        val descriptor = openDescriptor(name, RawExportNames.mimeOf(name), "w")
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    }

    override fun delete(name: String) {
        rows.remove(name)?.let { runCatching { context.contentResolver.delete(it.uri, null, null) } }
    }

    fun openReportOutput(localName: String): OutputStream {
        val descriptor = openDescriptor(localName, RawExportNames.mimeOf(localName), "w")
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    }

    fun rowBytes(localName: String): Long = context.contentResolver.openFileDescriptor(rows.getValue(localName).uri, "r")?.use {
        it.statSize
    } ?: 0L

    fun displayNameOf(localName: String): String = rows[localName]?.displayName ?: RawExportNames.name(session, localName)

    /** local name → already-published Download name, for the report mapping. */
    fun exportedMapping(): Map<String, String> = rows.mapValues { it.value.displayName }

    fun localNames(): List<String> = rows.keys.toList()

    /** Clears IS_PENDING for every row; a row that fails is deleted and reported. */
    fun publish(): List<String> {
        val failed = mutableListOf<String>()
        rows.values.forEach { row ->
            val cleared = runCatching {
                context.contentResolver.update(row.uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1
            }.getOrDefault(false)
            if (!cleared) {
                // A row that cannot be published would stay invisible forever: remove it.
                runCatching { context.contentResolver.delete(row.uri, null, null) }
                failed += row.displayName
            }
        }
        published = true
        return failed
    }

    /** Removes every pending row of a failed session; already-published rows are kept. */
    fun discard() {
        if (published) return
        rows.values.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
        rows.clear()
    }

}
