package me.rerere.rikkahub.utils

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoLibraryAccessTest {
    @Test
    fun `Android 14 accepts selected photos without requiring a full library grant`() {
        assertEquals(PhotoLibraryAccess.PARTIAL, resolvePhotoLibraryAccess(34, setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)))
    }

    @Test
    fun `a full Android 14 image grant takes precedence over a selected photo grant`() {
        assertEquals(PhotoLibraryAccess.FULL, resolvePhotoLibraryAccess(34, setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)))
    }

    @Test
    fun `Android 13 uses image permission and does not mistake a selected permission for access`() {
        assertEquals(PhotoLibraryAccess.FULL, resolvePhotoLibraryAccess(33, setOf(Manifest.permission.READ_MEDIA_IMAGES)))
        assertEquals(PhotoLibraryAccess.NONE, resolvePhotoLibraryAccess(33, setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)))
    }

    @Test
    fun `legacy storage grant applies only before Android 13`() {
        assertEquals(PhotoLibraryAccess.FULL, resolvePhotoLibraryAccess(32, setOf(Manifest.permission.READ_EXTERNAL_STORAGE)))
        assertEquals(PhotoLibraryAccess.NONE, resolvePhotoLibraryAccess(34, setOf(Manifest.permission.READ_EXTERNAL_STORAGE)))
    }

    @Test
    fun `revoked or absent grants are read as no access rather than cached access`() {
        assertEquals(PhotoLibraryAccess.PARTIAL, resolvePhotoLibraryAccess(34, setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)))
        assertEquals(PhotoLibraryAccess.NONE, resolvePhotoLibraryAccess(34, emptySet()))
    }

    @Test
    fun `Android 14 requests full and selected access together with older system fallbacks`() {
        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), photoLibraryPermissionNames(34))
        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES), photoLibraryPermissionNames(33))
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), photoLibraryPermissionNames(32))
    }
}
