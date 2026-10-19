package com.cinecam.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {
    val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    fun allGranted(context: Context): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun isCameraGranted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
