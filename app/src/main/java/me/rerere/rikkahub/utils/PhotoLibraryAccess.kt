package me.rerere.rikkahub.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class PhotoLibraryAccess { FULL, PARTIAL, NONE }

internal fun photoLibraryPermissionNames(sdkInt: Int): List<String> = when {
    sdkInt >= 34 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    sdkInt >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun resolvePhotoLibraryAccess(sdkInt: Int, granted: Set<String>): PhotoLibraryAccess = when {
    sdkInt >= 33 && Manifest.permission.READ_MEDIA_IMAGES in granted -> PhotoLibraryAccess.FULL
    sdkInt >= 34 && Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in granted -> PhotoLibraryAccess.PARTIAL
    sdkInt < 33 && Manifest.permission.READ_EXTERNAL_STORAGE in granted -> PhotoLibraryAccess.FULL
    else -> PhotoLibraryAccess.NONE
}

/** Call before every query/request; selected-photo grants can change while the app is hidden. */
fun Context.currentPhotoLibraryAccess(): PhotoLibraryAccess = resolvePhotoLibraryAccess(
    Build.VERSION.SDK_INT,
    photoLibraryPermissionNames(Build.VERSION.SDK_INT).filter {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }.toSet(),
)
