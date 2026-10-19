package com.cinecam.app

import android.app.Application
import android.util.Log

/**
 * CineCam Application
 * - Initializes NDK engine singleton lazily
 * - Handles global crash logging for RAW pipeline
 */
class CineCamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CineCam RAW v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})")
        Log.i(TAG, "BuildTime: ${BuildConfig.BUILD_TIME}")

        // Uncaught exception handler to ensure RAW files are closed
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught on ${thread.name}", throwable)
            // Best-effort: try to stop recording if engine was running
            try {
                com.cinecam.engine.NativeRawEngine.getInstance().release()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "CineCamApp"
    }
}
