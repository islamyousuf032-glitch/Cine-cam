package com.cinecam.library

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import com.cinecam.camera.CcrawVerifier
import com.cinecam.engine.NativeRawEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal data class RestoreOutput(
    val name: String, val path: String, val uri: Uri?, val bytes: Long,
    val frames: Int, val width: Int, val height: Int, val blocks: Long, val elapsedMs: Long
)

/**
 * Restores a `.ccraw.bp16` archive into a NEW `.ccraw` in Download/CineCam.
 * The archive and the original capture are never modified or replaced; a failed restore
 * removes its own partial file, so the folder never keeps a half-written RAW.
 */
internal object Bp16Restorer {
    /** The restored RAW plus the export copy need room; keep a margin for provider overhead. */
    const val FREE_MARGIN_BYTES = 96L * 1024 * 1024

    suspend fun restore(
        context: Context, archive: FolderDocument, clipId: String,
        onStage: suspend (String) -> Unit
    ): RestoreOutput = withContext(Dispatchers.IO) {
        val name = RestoreNames.restoredName(clipId, System.currentTimeMillis())
        val info = readHeader(context, archive)
            ?: throw IllegalStateException("Archive header সঠিক নয় বা CRC মেলেনি")
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("Restore staging storage পাওয়া যায়নি")
        val folder = File(root, "restore")
        check(folder.isDirectory || folder.mkdirs()) { "Restore staging folder তৈরি হয়নি" }
        val free = runCatching { StatFs(folder.absolutePath).availableBytes }.getOrDefault(0L)
        val needed = info.sourceBytes + FREE_MARGIN_BYTES
        check(free >= needed) {
            "জায়গা কম: restored CCRAW ${info.sourceBytes / (1024 * 1024)} MiB + নিরাপত্তা মার্জিন দরকার, " +
                "এখন ফাঁকা ${free / (1024 * 1024)} MiB"
        }
        val staged = File(folder, name)
        check(staged.createNewFile()) { "একই নামের restored file আগেই আছে" }
        var mediaUri: Uri? = null
        try {
            onStage("Archive restore হচ্ছে • ${size(info.sourceBytes)}")
            val json = ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_WRITE_ONLY).use { destination ->
                context.contentResolver.openFileDescriptor(archive.uri, "r")?.use { source ->
                    NativeRawEngine.getInstance().nativeRestoreBp16Archive(source.fd, destination.fd)
                } ?: throw IllegalStateException("Archive খোলা যায়নি; folder access আবার দিন")
            }
            val result = JSONObject(json)
            if (!result.optBoolean("ok")) {
                throw IllegalStateException("Restore ব্যর্থ: ${result.optString("error").ifBlank { "অজানা কারণ" }}")
            }
            if (result.optLong("restoredBytes") != info.sourceBytes || staged.length() != info.sourceBytes) {
                throw IllegalStateException("Restore মাপ মেলেনি; ফাইল অসম্পূর্ণ")
            }
            onStage("Restored CCRAW-এর frame/CRC যাচাই হচ্ছে…")
            val verified = CcrawVerifier.verify(staged)
            check(verified.frames.isNotEmpty()) { "Restored file-এ কোনো frame নেই" }
            onStage("Download/CineCam-এ লেখা হচ্ছে • ${verified.frames.size} frame")
            mediaUri = publish(context, staged, name)
            val bytes = mediaUri?.let {
                context.contentResolver.openAssetFileDescriptor(it, "r")?.use { asset -> asset.length } ?: 0L
            } ?: staged.length()
            check(mediaUri == null || bytes == staged.length()) { "Published file মাপ মেলেনি" }
            RestoreOutput(name, mediaUri?.let { "Download/CineCam/$name" } ?: staged.absolutePath, mediaUri,
                bytes, verified.frames.size, verified.width, verified.height,
                result.optLong("blocks"), result.optLong("totalNs") / 1_000_000L)
        } catch (e: Throwable) {
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            runCatching { staged.delete() }
            throw e
        } finally {
            if (mediaUri != null) runCatching { staged.delete() }
        }
    }

    private fun readHeader(context: Context, archive: FolderDocument): Bp16Header.Info? =
        context.contentResolver.openInputStream(archive.uri)?.use { input ->
            val header = ByteArray(Bp16Header.HEADER_BYTES)
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count <= 0) break
                read += count
            }
            if (read == header.size) Bp16Header.parse(header) else null
        }

    /** API29+ publishes through MediaStore; older Android keeps the existing app-specific path. */
    private fun publish(context: Context, staged: File, name: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) {
            val root = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "CineCam")
            check(root.isDirectory || root.mkdirs()) { "Restore folder তৈরি হয়নি" }
            val output = File(root, name)
            check(output.isDirectory.not() && !output.exists()) { "একই নামের file আগেই আছে" }
            staged.copyTo(output, overwrite = false)
            check(output.length() == staged.length()) { "Restore copy অসম্পূর্ণ" }
            return null
        }
        val values = ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/CineCam")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Download-এ restored file entry তৈরি হয়নি")
        try {
            // A provider must not silently rename the restored file: its name is the identity.
            context.contentResolver.query(uri,
                arrayOf(android.provider.MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == name) { "Restore নাম সংঘর্ষ; ফাইল লেখা হয়নি" }
            } ?: throw IllegalStateException("Restore নাম যাচাই হয়নি")
            val detail = context.contentResolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                JSONObject(NativeRawEngine.getInstance().nativeCopyExport(staged.absolutePath, descriptor.fd))
            } ?: throw IllegalStateException("Restored output খোলা যায়নি")
            check(detail.optBoolean("ok") && detail.optLong("bytes") == staged.length()) {
                "Download copy ব্যর্থ: ${detail.optString("error")}"
            }
            check(context.contentResolver.update(uri, ContentValues().apply {
                put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            }, null, null) == 1) { "Restored file publish ব্যর্থ" }
            return uri
        } catch (e: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun size(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
}
