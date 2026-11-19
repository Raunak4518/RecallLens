package org.photolibrary.app.library

import android.Manifest
import android.content.Context
import androidx.core.content.PermissionChecker
import android.location.Location
import android.media.ExifInterface
import android.provider.MediaStore
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.photolibrary.app.index.CachedLocation
import org.photolibrary.app.index.LocationCache
import org.photolibrary.app.index.PhotoCoordinates
import org.photolibrary.app.subjects.NEARBY_KM
import org.photolibrary.app.subjects.NamedSearch

internal class PhotoLocations(
    private val context: Context,
    private val library: PhotoLibrary,
    private val cache: LocationCache? = null,
    private val locationAllowed: (() -> Boolean)? = null,
    private val readCoordinates: ((SelectedPhoto) -> PhotoCoordinates?)? = null,
) {
    private val lock = Mutex()
    private val memory = mutableMapOf<android.net.Uri, CachedLocation>()

    private fun clear() {
        memory.clear()
        cache?.clear()
    }

    private var unavailable = UnavailableSources.NONE

    fun permissionGranted(): Boolean = allowed()

    suspend fun evidencePermitted(): Boolean = lock.withLock {
        if (allowed()) true else {
            clear()
            false
        }
    }

    suspend fun reconcile(photos: List<SelectedPhoto>, unavailable: UnavailableSources = UnavailableSources.NONE) = lock.withLock {
        this.unavailable = unavailable
        if (!allowed()) clear() else reconcileLocations(photos)
    }

    private fun reconcileLocations(photos: List<SelectedPhoto>) {
        val accessible = photos.associate { it.uri to it.sourceRevision }
        memory.keys.retainAll(accessible.keys)
        memory.entries.removeAll { (uri, saved) -> accessible[uri] != saved.revision }
        cache?.reconcile(accessible, unavailable)?.let { memory.putAll(it) }
    }

    suspend fun search(
        query: NamedSearch.Nearby, photos: List<SelectedPhoto>,
        unavailable: UnavailableSources = UnavailableSources.NONE,
    ): NamedSearch = lock.withLock {
        this.unavailable = unavailable
        if (!allowed()) {
            clear()
            return@withLock NamedSearch.LocationPermission
        }
        reconcileLocations(photos)
        if (query.candidates?.isEmpty() == true) return@withLock NamedSearch.NearbyMatches(emptySet())
        val revisions = library.galleryRevisions()
        val anchors = photos.filter { it.uri in query.anchors }.mapNotNull { coordinates(it, revisions) }
        if (anchors.isEmpty()) return@withLock NamedSearch.NoPlaceCoordinates
        val distances = mutableMapOf<android.net.Uri, Float>()
        val distanceBuffer = FloatArray(1)
        for (photo in photos) {
            currentCoroutineContext().ensureActive()
            if (query.candidates != null && photo.uri !in query.candidates) continue
            val point = coordinates(photo, revisions) ?: continue
            val nearest = anchors.minOf { distanceMeters(point, it, distanceBuffer) }
            if (nearest <= NEARBY_KM * 1000) distances[photo.uri] = nearest
        }
        val matches = distances.entries.sortedWith(compareBy<Map.Entry<android.net.Uri, Float>> { it.value }
            .thenBy { it.key.toString() }).mapTo(linkedSetOf()) { it.key }
        if (allowed()) {
            val latest = library.galleryRevisions()
            if (!allowed()) {
                clear()
                return@withLock NamedSearch.LocationPermission
            }
            val stale = photos.any { photo ->
                (photo.uri in matches || photo.uri in query.anchors) && latest[photo.uri] != photo.sourceRevision
            }
            if (stale) {
                reconcileLocations(photos.map { it.copy(sourceRevision = latest[it.uri]) })
                NamedSearch.Unresolved
            } else NamedSearch.NearbyMatches(matches)
        } else {
            clear()
            NamedSearch.LocationPermission
        }
    }

    private fun allowed() =
        locationAllowed?.invoke() ?:
            (PermissionChecker.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PermissionChecker.PERMISSION_GRANTED)

    private fun coordinates(photo: SelectedPhoto, revisions: Map<android.net.Uri, String?>): PhotoCoordinates? {
        val revision = photo.sourceRevision ?: return null
        if (photo.origin != PhotoOrigin.GALLERY || photo.uri.authority != MediaStore.AUTHORITY ||
            revisions[photo.uri] != revision) return null
        val saved = memory[photo.uri]
        if (saved?.revision == revision) return saved.coordinates
        return try {
            val original = MediaStore.setRequireOriginal(photo.uri)
            val point = if (readCoordinates != null) readCoordinates.invoke(photo) else {
                val input = context.contentResolver.openInputStream(original) ?: return null
                input.use {
                val degrees = FloatArray(2)
                if (ExifInterface(it).getLatLong(degrees)) PhotoCoordinates(degrees[0].toDouble(), degrees[1].toDouble()) else null
                }
            }
            if (!allowed() || library.currentRevision(photo.uri) != revision) return null
            val entry = CachedLocation(revision, point)
            cache?.save(photo.uri, entry)
            memory[photo.uri] = entry
            point
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }
}

private fun distanceMeters(first: PhotoCoordinates, second: PhotoCoordinates, result: FloatArray): Float {
    Location.distanceBetween(first.latitude, first.longitude, second.latitude, second.longitude, result)
    return result[0]
}
