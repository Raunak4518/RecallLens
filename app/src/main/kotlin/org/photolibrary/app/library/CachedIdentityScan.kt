package org.photolibrary.app.library

import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.photolibrary.app.identity.IdentityCacheEntry
import org.photolibrary.app.identity.IdentityVector
import org.photolibrary.app.index.IndexedPhoto
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoIndexSession
import org.photolibrary.app.index.pixelRevision
import org.photolibrary.app.subjects.SubjectReference

internal data class IdentityCacheUsage(
    val galleryHits: Int,
    val galleryMisses: Int,
    val referenceHits: Int,
    val referenceMisses: Int,
)

internal class CachedIdentityScan(
    private val library: PhotoLibrary,
    private val store: PhotoIndexSession<IdentityCacheEntry>,
    private val model: ModelRevision,
    accessible: Set<Uri>,
    unavailable: UnavailableSources,
    openScan: () -> IdentityScan,
) : AutoCloseable {
    private val records = store.reconcile(accessible, accessible, unavailable).toMutableMap()
    private val live = lazy(openScan)
    private var galleryRevisions: Map<Uri, String?>? = null
    private var galleryHits = 0
    private var galleryMisses = 0
    private var referenceHits = 0
    private var referenceMisses = 0

    val usage get() = IdentityCacheUsage(galleryHits, galleryMisses, referenceHits, referenceMisses)

    suspend fun reference(photo: SelectedPhoto, reference: SubjectReference): IdentityVector? {
        val saved = current(photo)
        val entry = saved?.output
        if (saved?.sourceRevision == reference.revision && entry != null && entry.references.containsKey(reference.region)) {
            referenceHits++
            return entry.references[reference.region]
        }
        if (!selectionCurrent(photo) || liveRevision(photo) != reference.revision) return null
        referenceMisses++
        val attempt = live.value.readReference(photo, reference) ?: return null
        val matching = saved?.takeIf { it.sourceRevision == attempt.revision }?.output ?: IdentityCacheEntry()
        save(photo.uri, attempt.revision, attempt.pixelRevision, matching.withReference(reference.region, attempt.vector))
        return attempt.vector
    }

    suspend fun describe(photo: SelectedPhoto): IdentityPhotoFeatures? {
        val record = current(photo)
        val gallery = record?.output?.gallery
        if (gallery != null) {
            galleryHits++
            return IdentityPhotoFeatures(photo, checkNotNull(record.sourceRevision), gallery, record.pixelRevision)
        }
        if (!selectionCurrent(photo)) return null
        galleryMisses++
        val inferred = live.value.describe(photo) ?: return null
        val matching = record?.takeIf { it.sourceRevision == inferred.revision }?.output ?: IdentityCacheEntry()
        save(photo.uri, inferred.revision, inferred.pixelRevision, matching.copy(gallery = inferred.regions))
        return inferred
    }

    private suspend fun current(photo: SelectedPhoto): IndexedPhoto<IdentityCacheEntry>? {
        val record = records[photo.uri] ?: return null
        val expected = photo.sourceRevision?.let { "source:$it" }
        if (record.model != model) return discard(photo.uri)
        val revision = liveRevision(photo) ?: return discard(photo.uri)
        if (expected != null && expected != revision) return null
        if (record.sourceRevision == revision) return record
        if (record.pixelRevision != null) {
            val verified = library.inspectPhoto(photo, revision, ::pixelRevision)
            if (verified?.second == record.pixelRevision) {
                currentCoroutineContext().ensureActive()
                val updated = record.copy(sourceRevision = verified.first)
                store.save(photo.uri, updated)
                records[photo.uri] = updated
                return updated
            }
        }
        return discard(photo.uri)
    }

    private fun discard(uri: Uri): IndexedPhoto<IdentityCacheEntry>? {
        val record = records.remove(uri) ?: return null
        store.remove(uri, record.sourceRevision, record.model)
        return null
    }

    fun invalidate(uri: Uri, revision: String) {
        val record = records[uri] ?: return
        if (record.sourceRevision != revision) return
        store.remove(uri, record.sourceRevision, record.model)
        records.remove(uri)
    }

    private suspend fun liveRevision(photo: SelectedPhoto): String? {
        if (photo.origin == PhotoOrigin.GALLERY) {
            val revisions = galleryRevisions ?: library.galleryRevisions().also { galleryRevisions = it }
            if (photo.uri !in revisions) return null
            revisions[photo.uri]?.let { return "source:$it" }
        }
        return library.referenceRevision(photo.uri)
    }

    private suspend fun selectionCurrent(photo: SelectedPhoto): Boolean {
        val revision = liveRevision(photo) ?: return false
        return photo.sourceRevision == null || revision == "source:${photo.sourceRevision}"
    }

    private suspend fun save(uri: Uri, revision: String, pixelRevision: String?, entry: IdentityCacheEntry) {
        currentCoroutineContext().ensureActive()
        val record = IndexedPhoto(revision, pixelRevision, model, entry)
        store.save(uri, record)
        records[uri] = record
    }

    override fun close() {
        if (live.isInitialized()) live.value.close()
    }
}
