package com.cinecam.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.cinecam.library.PreviewLoader
import com.cinecam.library.PreviewTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class RenderOutput(
    val uri: Uri?, val path: String, val width: Int, val height: Int,
    val frames: Int, val fps: Int, val bytes: Long
)

/**
 * Silent H.264 render of saved RAW-burst preview frames. Preview colour pipeline, not a
 * full RAW grade; sources are only read. Rendering never replaces or deletes capture files.
 */
internal object Mp4Renderer {
    suspend fun render(
        context: Context, clipId: String, targets: List<PreviewTarget>, sensorNs: List<Long>,
        onProgress: suspend (Int, Int) -> Unit
    ): RenderOutput = withContext(Dispatchers.IO) {
        require(targets.isNotEmpty()) { "রেন্ডার করার frame নেই" }
        require(targets.size == sensorNs.size) { "Frame ও timestamp সংখ্যা মেলে না" }
        val first = PreviewLoader.load(context, targets.first(), VideoRenderPlan.MAX_DIMENSION)
        val (width, height) = VideoRenderPlan.dimensions(first.width, first.height)
        val timestamps = VideoRenderPlan.timestampsUs(sensorNs)
        val fps = VideoRenderPlan.nominalFps(timestamps)
        val name = "CineCam_${clipId}_render_${System.currentTimeMillis()}.mp4"

        var uri: Uri? = null
        var file: File? = null
        var muxer: MediaMuxer? = null
        var codec: MediaCodec? = null
        var muxerStarted = false
        try {
            val layout: YuvLayout
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/CineCam")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val inserted = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Download-এ video entry তৈরি হয়নি")
                uri = inserted
                val descriptor = context.contentResolver.openFileDescriptor(inserted, "w")
                    ?: error("Video output খোলা যায়নি")
                descriptor.use { muxer = MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
                layout = createEncoder(width, height, fps) { codec = it }
            } else {
                val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    ?: error("Video storage পাওয়া যায়নি"), "CineCam")
                check(directory.isDirectory || directory.mkdirs()) { "Video folder তৈরি হয়নি" }
                val output = File(directory, name)
                file = output
                muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                layout = createEncoder(width, height, fps) { codec = it }
            }
            val activeCodec = requireNotNull(codec)
            val activeMuxer = requireNotNull(muxer)
            val info = MediaCodec.BufferInfo()
            var track = -1
            val scale: (Bitmap) -> Bitmap = { bitmap ->
                if (bitmap.width == width && bitmap.height == height) bitmap
                else Bitmap.createScaledBitmap(bitmap, width, height, true)
            }
            fun drain(endOfStream: Boolean) {
                while (true) {
                    when (val index = activeCodec.dequeueOutputBuffer(info, 10_000L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            return
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) error("Encoder format দুবার বদলেছে")
                            track = activeMuxer.addTrack(activeCodec.outputFormat)
                            activeMuxer.start()
                            muxerStarted = true
                        }
                        else -> {
                            if (index < 0) continue
                            val buffer = activeCodec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                if (!muxerStarted) error("Encoder track তৈরি হয়নি")
                                activeMuxer.writeSampleData(track, buffer, info)
                            }
                            activeCodec.releaseOutputBuffer(index, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                        }
                    }
                }
            }
            for (index in targets.indices) {
                val bitmap = PreviewLoader.load(context, targets[index], VideoRenderPlan.MAX_DIMENSION)
                val scaled = scale(bitmap)
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                if (scaled !== bitmap) scaled.recycle()
                val frame = YuvConverter.convert(pixels, width, height, layout)
                var queued = false
                var attempts = 0
                while (!queued) {
                    val inputIndex = activeCodec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = activeCodec.getInputBuffer(inputIndex) ?: error("Encoder input buffer নেই")
                        if (buffer.capacity() < frame.data.size) error("Encoder input buffer ছোট")
                        buffer.clear()
                        buffer.put(frame.data)
                        activeCodec.queueInputBuffer(inputIndex, 0, frame.data.size, timestamps[index], 0)
                        queued = true
                    } else if (++attempts > 500) {
                        error("Encoder input নিচ্ছে না")
                    } else {
                        drain(false)
                    }
                }
                drain(false)
                onProgress(index + 1, targets.size)
            }
            val eosIndex = activeCodec.dequeueInputBuffer(10_000L)
            if (eosIndex >= 0) {
                activeCodec.queueInputBuffer(eosIndex, 0, 0, timestamps.last() + VideoRenderPlan.MIN_FRAME_US,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
            activeCodec.stop()
            activeCodec.release()
            codec = null
            if (!muxerStarted) error("Encoder কোনো video frame দেয়নি")
            activeMuxer.stop()
            activeMuxer.release()
            muxer = null
            val finished = uri
            if (finished != null) {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                check(context.contentResolver.update(finished, values, null, null) == 1) { "Video publish ব্যর্থ" }
            }
            val sized = when {
                finished != null -> context.contentResolver.openAssetFileDescriptor(finished, "r")
                    ?.use { it.length.coerceAtLeast(0L) } ?: 0L
                else -> file?.length() ?: 0L
            }
            RenderOutput(finished, finished?.let { "Download/CineCam/$name" } ?: file!!.absolutePath,
                width, height, targets.size, fps, sized)
        } catch (e: Throwable) {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            file?.let { runCatching { it.delete() } }
            throw e
        }
    }

    private fun createEncoder(width: Int, height: Int, fps: Int, assign: (MediaCodec) -> Unit): YuvLayout {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        assign(codec)
        val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val formats = capabilities.colorFormats.toSet()
        val layout = when {
            formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) -> YuvLayout.NV12
            formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) -> YuvLayout.I420
            else -> {
                codec.release()
                throw IllegalStateException("এই ফোনের H.264 encoder YUV 4:2:0 নেয় না")
            }
        }
        val colorFormat = if (layout == YuvLayout.NV12)
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4).coerceIn(2_000_000, 12_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        return layout
    }
}
