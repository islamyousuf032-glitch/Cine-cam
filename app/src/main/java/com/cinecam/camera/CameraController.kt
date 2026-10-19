package com.cinecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface

/** Preview-only session. All Camera2 state is confined to one handler thread. */
class CameraController(context: Context, private val onStatus: (String) -> Unit) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("CineCamCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private var generation = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    @Volatile private var released = false

    @SuppressLint("MissingPermission")
    fun open(cameraId: String, texture: SurfaceTexture, size: Size) {
        if (released) return
        handler.post {
            closeInternal()
            val token = generation
            try {
                texture.setDefaultBufferSize(size.width, size.height)
                val target = Surface(texture)
                surface = target
                onStatus("ক্যামেরা খোলা হচ্ছে…")
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (token != generation || released) { camera.close(); return }
                        device = camera
                        try {
                            camera.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    if (token != generation || released) { configured.close(); return }
                                    session = configured
                                    try {
                                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(target)
                                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                        }.build()
                                        configured.setRepeatingRequest(request, null, handler)
                                        onStatus("লাইভ প্রিভিউ • ${size.width}×${size.height}")
                                    } catch (e: Exception) { fail(token, e.message) }
                                }
                                override fun onConfigureFailed(failed: CameraCaptureSession) {
                                    failed.close()
                                    fail(token, "প্রিভিউ session তৈরি হয়নি")
                                }
                            }, handler)
                        } catch (e: Exception) { fail(token, e.message) }
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        fail(token, "ক্যামেরা সংযোগ বিচ্ছিন্ন; আবার চেষ্টা করুন")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        fail(token, "Camera2 error $error; অন্য camera app বন্ধ করে আবার চেষ্টা করুন")
                    }
                }, handler)
            } catch (e: Exception) { fail(token, e.message) }
        }
    }

    private fun fail(token: Int, message: String?) {
        if (token != generation || released) return
        closeInternal()
        onStatus("ত্রুটি: ${message ?: "ক্যামেরা চালু করা যায়নি"}")
    }

    private fun closeInternal() {
        generation++ // Invalidates pending open/session callbacks.
        session?.close(); session = null
        device?.close(); device = null
        surface?.release(); surface = null
    }

    fun close() { if (!released) handler.post { closeInternal() } }

    fun detach(texture: SurfaceTexture) {
        if (!handler.post { closeInternal(); texture.release() }) texture.release()
    }

    fun release() {
        if (released) return
        released = true
        handler.post { closeInternal(); thread.quitSafely() }
    }
}
