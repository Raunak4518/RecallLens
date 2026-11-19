package org.photolibrary.app.library

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class GalleryAccess {
    NONE, SELECTED, ALL;

    companion object {
        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(READ_MEDIA_IMAGES)
            else -> arrayOf(READ_EXTERNAL_STORAGE)
        }

        fun current(context: Context): GalleryAccess = when {
            PermissionChecker.checkSelfPermission(context, permissions()[0]) == PermissionChecker.PERMISSION_GRANTED -> ALL
            Build.VERSION.SDK_INT >= 34 && PermissionChecker.checkSelfPermission(context, READ_MEDIA_VISUAL_USER_SELECTED) == PermissionChecker.PERMISSION_GRANTED -> SELECTED
            else -> NONE
        }
    }
}

internal data class GallerySnapshot(val photos: List<SelectedPhoto>, val unavailableVolumes: Set<String> = emptySet())

internal class GallerySource(
    private val context: Context,
    private val attachedVolumes: () -> Set<String> = { MediaStore.getExternalVolumeNames(context) },
    private val recentVolumes: () -> Set<String> = {
        context.getSystemService(StorageManager::class.java).recentStorageVolumes
            .mapNotNull { it.mediaStoreVolumeName }.toSet()
    },
    private val queryPhotos: (Array<String>) -> Cursor? = { projection ->
        context.contentResolver.query(Media.getContentUri(MediaStore.VOLUME_EXTERNAL), projection,
            ACTIVE_PHOTO, null, "${Media.DATE_ADDED} DESC, ${Media._ID} DESC")
    },
    private val readVolumeVersion: (String) -> String? = { MediaStore.getVersion(context, it) },
) {
    suspend fun photos(scope: GalleryAccess): List<SelectedPhoto> = snapshot(scope).photos

    suspend fun snapshot(scope: GalleryAccess): GallerySnapshot {
        if (scope == GalleryAccess.NONE) return GallerySnapshot(emptyList())
        val coroutine = currentCoroutineContext()
        return try {
            val attached = attachedVolumes()
            val versions = attached.associateWith(::volumeVersion)
            val cursor = try {
                queryPhotos(arrayOf(Media._ID, Media.DISPLAY_NAME, Media.VOLUME_NAME, *REVISION_COLUMNS, Media.DATE_TAKEN, Media.DATE_ADDED))
            } catch (_: IllegalArgumentException) {
                null
            }
            val photos = cursor?.use { cursor ->
                val capturedAtColumn = cursor.getColumnIndex(Media.DATE_TAKEN)
                val addedColumn = cursor.getColumnIndex(Media.DATE_ADDED)
                buildList {
                    while (cursor.moveToNext()) {
                        coroutine.ensureActive()
                        val volume = cursor.getString(2)
                        val collection = Media.getContentUri(volume)
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        val revision = mediaRevision(versions[volume], cursor, 3)
                        val capturedAt = if (capturedAtColumn >= 0 && !cursor.isNull(capturedAtColumn)) {
                            cursor.getLong(capturedAtColumn).takeIf { it > 0 }
                        } else null
                        val imported = if (addedColumn >= 0 && !cursor.isNull(addedColumn)) {
                            cursor.getLong(addedColumn).takeIf { it in 1..Long.MAX_VALUE / 1000 }
                                ?.let { PhotoDateHint(it * 1000, PhotoDateHintSource.ADDED_TO_GALLERY) }
                        } else null
                        add(SelectedPhoto(uri, cursor.getString(1), PhotoOrigin.GALLERY, revision, capturedAt, imported))
                    }
                }
            }
            val latestVolumes = attachedVolumes()
            val unavailable = if (scope == GalleryAccess.ALL && GalleryAccess.current(context) == GalleryAccess.ALL) {
                (recentVolumes() - latestVolumes) + (attached - latestVolumes) +
                    (if (photos == null) attached else emptySet())
            } else emptySet()
            if (GalleryAccess.current(context) != scope) return GallerySnapshot(emptyList())
            val reset = versions.keys.filterTo(hashSetOf()) { volumeVersion(it) != versions[it] }
            val availablePhotos = photos.orEmpty().filter { MediaStore.getVolumeName(it.uri) in latestVolumes }
            val revised = if (reset.isEmpty()) availablePhotos else availablePhotos.map { photo ->
                if (MediaStore.getVolumeName(photo.uri) in reset) photo.copy(sourceRevision = null, capturedAtMillis = null, captureDateHint = null) else photo
            }
            GallerySnapshot(revised, unavailable)
        } catch (_: SecurityException) {
            GallerySnapshot(emptyList())
        }
    }

    fun revision(uri: Uri): String? {
        val volume = MediaStore.getVolumeName(uri)
        val version = volumeVersion(volume) ?: return null
        val revision = context.contentResolver.query(uri, REVISION_COLUMNS, ACTIVE_PHOTO, null, null)?.use {
            if (it.moveToFirst()) mediaRevision(version, it, 0) else null
        } ?: return null
        return revision.takeIf { version == volumeVersion(volume) }
    }

    private fun volumeVersion(volume: String): String? = try {
        readVolumeVersion(volume)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun mediaRevision(version: String?, row: Cursor, offset: Int): String? {
        if (version == null || row.isNull(offset)) return null
        val generation = row.getLong(offset)
        if (generation <= 0) return null
        val modified = if (row.isNull(offset + 1)) null else row.getLong(offset + 1)
        val size = if (row.isNull(offset + 2)) null else row.getLong(offset + 2)
        return "media:$version:$generation:$modified:$size"
    }

    private companion object {
        const val ACTIVE_PHOTO = "${Media.IS_PENDING} = 0 AND ${Media.IS_TRASHED} = 0"
        val REVISION_COLUMNS = arrayOf(Media.GENERATION_MODIFIED, Media.DATE_MODIFIED, Media.SIZE)
    }
}
