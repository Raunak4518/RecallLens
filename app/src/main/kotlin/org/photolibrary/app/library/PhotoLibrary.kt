package org.photolibrary.app.library

import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.photolibrary.app.index.pixelRevision

internal const val PHOTO_REFERENCE_EDGE = 1600

internal enum class PhotoOrigin { GALLERY, FOLDER, INDIVIDUAL }

internal enum class PhotoDateHintSource { ADDED_TO_GALLERY, FILE_MODIFIED }
internal data class PhotoDateHint(val atMillis: Long, val source: PhotoDateHintSource)

internal data class SelectedPhoto(
    val uri: Uri,
    val name: String?,
    val origin: PhotoOrigin,
    val sourceRevision: String? = null,
    val capturedAtMillis: Long? = null,
    val captureDateHint: PhotoDateHint? = null,
)

internal data class LibrarySources(
    val scope: GalleryAccess,
    val folders: List<PhotoFolder>,
    val photos: List<SelectedPhoto>,
    val unavailableVolumes: Set<String> = emptySet(),
) {
    val unavailableSources get() = UnavailableSources.from(folders, unavailableVolumes)
}

internal class PhotoLibrary(private val context: Context) : PhotoReferenceSource {
    private val resolver = context.contentResolver
    private val gallery = GallerySource(context)
    private val folders = FolderSource(resolver)
    private val imageDecoders = Semaphore(2)
    private val listing = Mutex()
    @Volatile private var unavailableSources = UnavailableSources.NONE

    suspend fun list(): LibrarySources = listing.withLock {
        val scope = GalleryAccess.current(context)
        val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }.sortedByDescending { it.persistedTime }
        val folderList = mutableListOf<PhotoFolder>()
        val photos = linkedMapOf<Uri, SelectedPhoto>()
        val gallerySnapshot = gallery.snapshot(scope)
        for (photo in gallerySnapshot.photos) photos[photo.uri] = photo
        for (grant in grants.filter { DocumentsContract.isTreeUri(it.uri) }) {
            val folder = folders.folder(grant.uri)
            val entries = folders.photos(folder.uri)
            if (!hasReadGrant(folder.uri)) continue
            folderList += folder.copy(available = entries != null)
            for (photo in entries.orEmpty()) photos.putIfAbsent(identity(photo.uri), photo)
        }
        for (grant in grants) {
            if (!DocumentsContract.isTreeUri(grant.uri)) {
                photos.putIfAbsent(identity(grant.uri), SelectedPhoto(grant.uri, displayName(grant.uri), PhotoOrigin.INDIVIDUAL))
            }
        }
        LibrarySources(scope, folderList, photos.values.toList(), gallerySnapshot.unavailableVolumes).also { snapshot ->
            unavailableSources = snapshot.unavailableSources
        }
    }

    private fun hasReadGrant(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }

    suspend fun galleryRevisions(): Map<Uri, String?> =
        gallery.photos(GalleryAccess.current(context)).associate { it.uri to it.sourceRevision }

    fun galleryAccess(): GalleryAccess = GalleryAccess.current(context)

    fun addFolder(uri: Uri): Boolean = try {
        require(DocumentsContract.isTreeUri(uri))
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    fun remove(uri: Uri): Boolean = try {
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }
    }

    private data class ThumbnailKey(val uri: Uri, val revision: String, val edge: Int)

    private val thumbnails = object : android.util.LruCache<ThumbnailKey, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: ThumbnailKey, value: Bitmap) = value.allocationByteCount.coerceAtLeast(1)
    }

    suspend fun loadImage(uri: Uri, maxEdge: Int, software: Boolean = false): Bitmap? = imageDecoders.withPermit {
        if (!canReadSource(uri)) return@withPermit null
        try {
            val key = if (!software && maxEdge <= 512) currentRevision(uri)?.let { ThumbnailKey(uri, it, maxEdge) } else null
            if (key != null) thumbnails.get(key)?.let { return@withPermit it }
            val bitmap = decodePhoto(ImageDecoder.createSource(resolver, uri), maxEdge, software)
            if (!canReadSource(uri) || key != null && currentRevision(uri) != key.revision) {
                bitmap.recycle()
                return@withPermit null
            }
            if (key != null) thumbnails.put(key, bitmap)
            bitmap
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun loadPixels(uri: Uri, maxEdge: Int): Bitmap? = loadImage(uri, maxEdge, software = true)

    fun readBoundedSource(uri: Uri, limit: Int): ByteArray? {
        if (!canReadSource(uri)) return null
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val collected = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > limit - collected.size()) return null
                    collected.write(buffer, 0, count)
                }
                collected.toByteArray()
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    override suspend fun referenceRevision(uri: Uri): String? {
        currentRevision(uri)?.let { return "source:$it" }
        val image = loadPixels(uri, PHOTO_REFERENCE_EDGE) ?: return null
        return try {
            "pixels:${pixelRevision(image)}"
        } finally {
            image.recycle()
        }
    }

    override suspend fun contentDigest(uri: Uri, expectedRevision: String): String? {
        if (!canReadSource(uri)) return null
        return sourceContentDigest(uri, expectedRevision, ::referenceRevision) { source ->
            if (canReadSource(source)) resolver.openInputStream(source) else null
        }
    }

    suspend fun <T> inspectPhoto(
        photo: SelectedPhoto, requiredRevision: String? = null, measure: (Bitmap) -> T,
    ): Pair<String, T>? {
        val providerRevision = currentRevision(photo.uri)
        if (photo.sourceRevision != null && providerRevision != photo.sourceRevision) return null
        val image = loadPixels(photo.uri, PHOTO_REFERENCE_EDGE) ?: return null
        return try {
            val revision = providerRevision?.let { "source:$it" } ?: "pixels:${pixelRevision(image)}"
            if (requiredRevision != null && revision != requiredRevision) return null
            val output = measure(image)
            currentCoroutineContext().ensureActive()
            if (referenceRevision(photo.uri) == revision) revision to output else null
        } finally {
            image.recycle()
        }
    }

    fun currentRevision(uri: Uri): String? = try {
        when {
            !canReadSource(uri) -> null
            uri.authority == MediaStore.AUTHORITY -> gallery.revision(uri)
            DocumentsContract.isTreeUri(uri) -> folders.revision(uri)
            else -> null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun canReadSource(uri: Uri): Boolean {
        if (uri in unavailableSources) return false
        if (uri.authority == MediaStore.AUTHORITY && GalleryAccess.current(context) != GalleryAccess.NONE) return true
        val tree = DocumentsContract.isTreeUri(uri)
        return resolver.persistedUriPermissions.any { grant ->
            grant.isReadPermission && (grant.uri == uri || (tree && grant.uri.authority == uri.authority &&
                DocumentsContract.isTreeUri(grant.uri) && DocumentsContract.getTreeDocumentId(grant.uri) == DocumentsContract.getTreeDocumentId(uri)))
        }
    }

    private fun identity(uri: Uri): Uri = try {
        val media = if (uri.authority == MediaStore.AUTHORITY) uri else MediaStore.getMediaUri(context, uri)
        when {
            media != null -> mediaIdentity(media)
            DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.buildDocumentUri(uri.authority, DocumentsContract.getDocumentId(uri))
            else -> uri
        }
    } catch (_: SecurityException) {
        uri
    } catch (_: IllegalArgumentException) {
        uri
    }

    private fun mediaIdentity(uri: Uri): Uri {
        val id = ContentUris.parseId(uri)
        if (id < 0) return uri
        val volume = MediaStore.getVolumeName(uri)
        val physicalVolume = if (volume == MediaStore.VOLUME_EXTERNAL) {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: return uri
        } else volume
        return ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(physicalVolume), id)
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
