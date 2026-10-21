package com.cinecam.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * NativeRawEngine - Kotlin JNI bridge to C++ RAW pipeline
 *
 * Wraps CineRawEngine (C++) which owns:
 * - MemoryPool (zero-copy buffers)
 * - RingBuffer (SPSC lock-free queue)
 * - RawWriter (high-speed disk I/O)
 *
 * All native methods are implemented in app/src/main/cpp/src/jni_bridge.cpp
 */
class NativeRawEngine private constructor() {

    companion object {
        private const val TAG = "NativeRawEngine"
        private var instance: NativeRawEngine? = null

        init {
            try {
                System.loadLibrary("cinecam_raw")
                Log.i(TAG, "libcinccam_raw.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }

        @Synchronized
        fun getInstance(): NativeRawEngine {
            if (instance == null) instance = NativeRawEngine()
            return instance!!
        }
    }

    // -----------------------------------------------------------------------
    // Native methods (implemented in jni_bridge.cpp)
    // -----------------------------------------------------------------------
    external fun nativeInitialize(width: Int, height: Int, bitDepth: Int, poolSize: Int): Boolean
    external fun nativeRelease()
    external fun nativeStartRecording(
        width: Int, height: Int, fps: Int, bitDepth: Int,
        outputPath: String, container: String,
        enableZstd: Boolean, zstdLevel: Int
    ): Boolean
    external fun nativeStopRecording(): Boolean
    external fun nativeIsRecording(): Boolean
    external fun nativePushFrame(
        data: ByteArray, size: Int,
        timestampNs: Long, exposureNs: Long, iso: Int,
        width: Int, height: Int, stride: Int, bitDepth: Int,
        frameNumber: Int
    ): Boolean
    external fun nativePushDirectFrame(
        byteBuffer: ByteBuffer, size: Int,
        timestampNs: Long, exposureNs: Long, iso: Int,
        width: Int, height: Int, stride: Int, bitDepth: Int,
        frameNumber: Int
    ): Boolean
    external fun nativeProbeRaw16Codec(path: String, offset: Long, bytes: Long): String
    external fun nativeGetStatsJson(): String
    external fun nativeRawClipCapacity(): Int
    external fun nativeStartRawClip(width: Int, height: Int, path: String, epochNs: Long, preallocate: Boolean): Boolean
    external fun nativePushRawPlane(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        timestamp: Long, exposure: Long, iso: Int, number: Int, focus: Float, neutralR: Float, neutralG: Float, neutralB: Float): Boolean
    external fun nativeGetLastError(): String
    external fun nativeGetVersion(): String

    // -----------------------------------------------------------------------
    // Friendly Kotlin API
    // -----------------------------------------------------------------------

    /**
     * Initialize engine for given frame dimensions
     */
    fun initialize(width: Int, height: Int, bitDepth: Int = 10, poolSize: Int = 8): Boolean {
        Log.i(TAG, "initialize: ${width}x${height} @${bitDepth}-bit pool=$poolSize")
        return try {
            nativeInitialize(width, height, bitDepth, poolSize)
        } catch (e: Exception) {
            Log.e(TAG, "nativeInitialize failed", e)
            false
        }
    }

    fun release() {
        try { nativeRelease() } catch (e: Exception) { Log.e(TAG, "release error", e) }
    }

    /**
     * Start recording to output folder
     * Internal experimental uncompressed dump only, not MotionCam-compatible.
     * No UI recording path, CinemaDNG video, compression or audio capture yet.
     */
    fun startRecording(
        width: Int, height: Int, fps: Int, bitDepth: Int,
        outputPath: String, container: String = "mcraw",
        enableZstd: Boolean = false, zstdLevel: Int = 3
    ): Boolean {
        Log.i(TAG, "startRecording: $outputPath [$container] ${width}x${height}@${fps}fps")
        return try {
            nativeStartRecording(width, height, fps, bitDepth, outputPath, container, enableZstd, zstdLevel)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e); false
        }
    }

    fun stopRecording(): Boolean {
        Log.i(TAG, "stopRecording()")
        return try { nativeStopRecording() } catch (e: Exception) { Log.e(TAG, "stop error", e); false }
    }

    fun isRecording(): Boolean = try { nativeIsRecording() } catch (_: Exception) { false }

    /**
     * Push unpacked RAW_SENSOR (16-bit storage), not YUV or packed RAW10/RAW12
     */
    fun pushFrame(
        data: ByteArray,
        timestampNs: Long, exposureNs: Long, iso: Int,
        width: Int, height: Int, stride: Int, bitDepth: Int,
        frameNumber: Int
    ): Boolean = try {
        nativePushFrame(data, data.size, timestampNs, exposureNs, iso, width, height, stride, bitDepth, frameNumber)
    } catch (e: Exception) { Log.e(TAG, "pushFrame error", e); false }

    /**
     * Avoids a Java array copy; native ingestion still copies into the bounded pool.
     */
    fun pushDirectFrame(
        buffer: ByteBuffer,
        timestampNs: Long, exposureNs: Long, iso: Int,
        width: Int, height: Int, stride: Int, bitDepth: Int,
        frameNumber: Int
    ): Boolean = try {
        require(buffer.isDirect) { "RAW input must be a direct buffer" }
        val input = buffer.slice() // Native address starts at slice offset, not original buffer position.
        nativePushDirectFrame(input, input.remaining(), timestampNs, exposureNs, iso, width, height, stride, bitDepth, frameNumber)
    } catch (e: Exception) { Log.e(TAG, "pushDirectFrame error", e); false }

    fun getStatsJson(): String = try { nativeGetStatsJson() } catch (_: Exception) { "{}" }
    fun getLastError(): String = try { nativeGetLastError() } catch (_: Exception) { "" }
    fun getVersion(): String = try { nativeGetVersion() } catch (_: Exception) { "unknown" }

    /**
     * Async initializer with coroutine (for app startup)
     */
    suspend fun initializeAsync(width: Int, height: Int, bitDepth: Int = 10, poolSize: Int = 8): Boolean =
        withContext(Dispatchers.Default) { initialize(width, height, bitDepth, poolSize) }
}
