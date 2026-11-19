package org.photolibrary.app.library

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.detection.RegionDetector
import org.photolibrary.app.detection.area
import org.photolibrary.app.detection.intersection
import org.photolibrary.app.identity.IdentityEncoder
import org.photolibrary.app.identity.IdentityModelFactory
import org.photolibrary.app.identity.IdentityRegion
import org.photolibrary.app.identity.IdentityVector
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.pixelRevision
import org.photolibrary.app.subjects.IdentityRejection
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.identityKey

internal val suggestionLimits = listOf(3, 5, 10)

internal data class RankedPhoto(val photo: SelectedPhoto, val revision: String, val region: PhotoRegion?, val score: Float) {
    fun rejection() = IdentityRejection(photo.uri, revision, region.identityKey())
}

internal data class IdentityReview(
    val subject: NamedSubject,
    val scanning: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    val result: IdentityRanking? = null,
    val skipped: Set<Uri> = emptySet(),
    val rejected: Set<IdentityRejection> = emptySet(),
    val lastRejection: IdentityRejection? = null,
    val editingRejection: Boolean = false,
    val canUndoGroupEdit: Boolean = false,
    val limit: Int = 5,
    val error: Int? = null,
) {
    fun visibleSuggestions(confirmed: Set<Uri>): List<RankedPhoto> =
        (result as? IdentityRanking.Candidates)?.photos.orEmpty().asSequence()
            .filterNot { it.photo.uri in confirmed || it.photo.uri in skipped || it.rejection() in rejected }.take(limit).toList()
}

internal sealed interface IdentityRanking {
    data class Candidates(val photos: List<RankedPhoto>, val cacheUsage: IdentityCacheUsage? = null) : IdentityRanking
    data object NoReference : IdentityRanking
}

internal class IdentityMatcher(
    private val library: PhotoLibrary,
    private val detectors: OnnxRegionModels,
    private val models: IdentityModelFactory,
    private val storage: EncryptedPhotoIndex? = null,
) {
    private val cacheOperations = Mutex()

    fun available(kind: SubjectKind) = detectors.available && models.available(kind)

    suspend fun reconcile(photos: List<SelectedPhoto>, unavailable: UnavailableSources = UnavailableSources.NONE) = cacheOperations.withLock {
        storage?.pruneIdentity(photos.associate { it.uri to it.sourceRevision }, unavailable)
        Unit
    }

    suspend fun rank(
        kind: SubjectKind,
        references: List<SubjectReference>,
        photos: List<SelectedPhoto>,
        unavailable: UnavailableSources = UnavailableSources.NONE,
        progress: suspend (Int, Int) -> Unit,
    ): IdentityRanking {
        val accessible = photos.mapTo(hashSetOf()) { it.uri }
        if (references.none { it.uri in accessible }) return IdentityRanking.NoReference
        val index = storage
        if (index == null) return open(kind).use { scan ->
            rankPhotos(references, photos, scan::reference, scan::describe, progress)
        }
        return cacheOperations.withLock {
            val model = ModelRevision(kind.name, "android-identity-2:${models.fingerprint(kind)}", PHOTO_REFERENCE_EDGE)
            index.openIdentity(kind).use { store ->
                CachedIdentityScan(library, store, model, accessible, unavailable) { open(kind) }.use { cache ->
                    val ranking = rankPhotos(references, photos, cache::reference, cache::describe, progress, cache::invalidate)
                    if (ranking is IdentityRanking.Candidates) ranking.copy(cacheUsage = cache.usage) else ranking
                }
            }
        }
    }

    private suspend fun rankPhotos(
        references: List<SubjectReference>,
        photos: List<SelectedPhoto>,
        reference: suspend (SelectedPhoto, SubjectReference) -> IdentityVector?,
        describe: suspend (SelectedPhoto) -> IdentityPhotoFeatures?,
        progress: suspend (Int, Int) -> Unit,
        invalidate: (Uri, String) -> Unit = { _, _ -> },
    ): IdentityRanking {
        val byUri = photos.associateBy { it.uri }
        val confirmed = references.mapTo(hashSetOf()) { it.uri }
        val candidates = photos.filterNot { it.uri in confirmed }
        val anchors = references.mapNotNull { ref ->
            currentCoroutineContext().ensureActive()
            byUri[ref.uri]?.let { reference(it, ref) }
        }
        if (anchors.isEmpty()) return IdentityRanking.NoReference
        progress(0, candidates.size)
        val ranked = candidates.mapIndexedNotNull { index, photo ->
            currentCoroutineContext().ensureActive()
            val match = describe(photo)?.match(anchors)
            if ((index + 1) % 8 == 0 || index + 1 == candidates.size) progress(index + 1, candidates.size)
            match
        }
        currentCoroutineContext().ensureActive()
        val galleryAccess = library.galleryAccess()
        val latestGallery = library.galleryRevisions()
        suspend fun current(photo: SelectedPhoto, revision: String): Boolean {
            val actual = if (photo.origin == PhotoOrigin.GALLERY) {
                if (photo.uri !in latestGallery) null
                else latestGallery[photo.uri]?.let { "source:$it" } ?: library.referenceRevision(photo.uri)
            } else library.referenceRevision(photo.uri)
            if (actual == revision) return true
            invalidate(photo.uri, revision)
            return false
        }
        var anchorsCurrent = true
        for (reference in references) {
            currentCoroutineContext().ensureActive()
            val photo = byUri[reference.uri]
            if (photo == null || !current(photo, reference.revision)) anchorsCurrent = false
        }
        val verified = ranked.filter { candidate ->
            currentCoroutineContext().ensureActive()
            current(candidate.photo, candidate.revision)
        }
        if (library.galleryAccess() != galleryAccess) return IdentityRanking.NoReference
        return if (anchorsCurrent) IdentityRanking.Candidates(verified.sortedWith(identityOrder)) else IdentityRanking.NoReference
    }

    fun open(kind: SubjectKind): IdentityScan {
        val detector = detectors.open()
        return try {
            IdentityScan(library, kind, detector, models.open(kind))
        } catch (failure: Exception) {
            detector.close()
            throw failure
        }
    }
}

internal val identityOrder = compareByDescending<RankedPhoto> { it.score }.thenBy { it.photo.uri.toString() }

internal class IdentityScan(
    private val library: PhotoLibrary,
    private val kind: SubjectKind,
    private val detector: RegionDetector,
    private val encoder: IdentityEncoder,
) : AutoCloseable {
    suspend fun reference(photo: SelectedPhoto, reference: SubjectReference): IdentityVector? =
        readReference(photo, reference)?.vector

    suspend fun readReference(photo: SelectedPhoto, reference: SubjectReference): IdentityReferenceFeatures? =
        library.inspectPhoto(photo, reference.revision) { image ->
            pixelRevision(image) to referenceVector(kind, image, reference.region, detector, encoder)
        }?.let { (revision, result) -> IdentityReferenceFeatures(revision, result.first, result.second) }

    suspend fun describe(photo: SelectedPhoto): IdentityPhotoFeatures? =
        library.inspectPhoto(photo) { image ->
            val fingerprint = pixelRevision(image)
            val detections = detector.detect(image, kind)
            val candidates = if (kind == SubjectKind.PERSON) detections else detections + listOf(null)
            val regions = candidates.mapNotNull { detection ->
                encoder.encode(image, detection?.region, detection)?.let { IdentityRegion(detection?.region, it) }
            }
            fingerprint to regions
        }?.let { (revision, result) -> IdentityPhotoFeatures(photo, revision, result.second, result.first) }

    override fun close() = try {
        encoder.close()
    } finally {
        detector.close()
    }
}

internal data class IdentityReferenceFeatures(val revision: String, val pixelRevision: String, val vector: IdentityVector?)

internal class IdentityPhotoFeatures(
    private val photo: SelectedPhoto,
    val revision: String,
    val regions: List<IdentityRegion>,
    val pixelRevision: String?,
) {
    fun match(anchors: List<IdentityVector>): RankedPhoto? {
        if (anchors.isEmpty()) return null
        var best: RankedPhoto? = null
        for (candidate in regions) {
            var first = -1f
            var second = -1f
            for (anchor in anchors) {
                val score = anchor.similarity(candidate.vector)
                if (score > first) {
                    second = first
                    first = score
                } else if (score > second) second = score
            }
            val score = if (anchors.size == 1) first else first * .85f + second * .15f
            if (best == null || score > best.score) best = RankedPhoto(photo, revision, candidate.region, score)
        }
        return best
    }
}

private fun referenceVector(
    kind: SubjectKind, image: Bitmap, region: PhotoRegion?, detector: RegionDetector, encoder: IdentityEncoder,
): IdentityVector? {
    if (kind != SubjectKind.PERSON) return encoder.encode(image, region)
    val face = detector.detect(image, kind).filter {
        region == null || intersection(region, it.region) / it.region.area() >= .55f
    }.singleOrNull() ?: return null
    return encoder.encode(image, face.region, face)
}


