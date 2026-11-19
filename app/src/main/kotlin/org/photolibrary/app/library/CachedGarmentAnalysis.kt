package org.photolibrary.app.library

import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.photolibrary.app.detection.GarmentModels
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.detection.PersonGarmentEvidence
import org.photolibrary.app.detection.describePersonGarment
import org.photolibrary.app.index.GarmentDependencies
import org.photolibrary.app.index.GarmentEvidenceStore
import org.photolibrary.app.index.pixelRevision
import org.photolibrary.app.subjects.SubjectKind

internal data class GarmentAnalysisSummary(
    val cachedPhotos: Int,
    val processedPhotos: Int,
    val unavailablePhotos: Int,
    val evidence: Map<Uri, List<PersonGarmentEvidence>>,
)

internal class CachedGarmentAnalysis(
    private val library: PhotoLibrary,
    private val store: GarmentEvidenceStore,
    private val models: GarmentModels,
    private val detectors: OnnxRegionModels,
) {
    private val verifiedStore = VerifiedGarmentEvidence(library, store)
    val available get() = models.available && detectors.available
    val dependencies get() = GarmentDependencies(
        detectors.faceRevision,
        models.personDetectorRevision,
        models.revision,
    )

    suspend fun scan(
        photos: List<SelectedPhoto>,
        progress: suspend (Int, Int) -> Unit,
    ): GarmentAnalysisSummary {
        var cached = 0
        var processed = 0
        var unavailable = 0
        val evidence = linkedMapOf<Uri, List<PersonGarmentEvidence>>()
        val revisions = linkedMapOf<Uri, String>()
        val cachedUris = mutableSetOf<Uri>()
        val faces = lazy { detectors.open() }
        val people = lazy { models.openPeople() }
        val parser = lazy { models.open() }
        try {
            for ((position, photo) in photos.withIndex()) {
                currentCoroutineContext().ensureActive()
                val revision = library.referenceRevision(photo.uri)
                val selectedCurrent = revision != null && (photo.sourceRevision == null || revision == "source:${photo.sourceRevision}")
                var restored = if (selectedCurrent) store.read(photo.uri, checkNotNull(revision), dependencies) else null
                if (selectedCurrent && restored == null) {
                    val fingerprint = store.pendingFingerprint(photo.uri, dependencies)
                    if (fingerprint != null) {
                        val verified = library.inspectPhoto(photo, revision, ::pixelRevision)
                        currentCoroutineContext().ensureActive()
                        if (verified?.second == fingerprint && verifiedStore.rebind(photo.uri, verified.first, dependencies, fingerprint)) {
                            restored = store.read(photo.uri, verified.first, dependencies)
                        }
                    }
                }
                if (restored != null) {
                    currentCoroutineContext().ensureActive()
                    if (library.referenceRevision(photo.uri) == revision) {
                        evidence[photo.uri] = restored
                        revisions[photo.uri] = checkNotNull(revision)
                        cachedUris += photo.uri
                        cached++
                    } else unavailable++
                } else if (selectedCurrent) {
                    val measured = library.inspectPhoto(photo, revision) { image ->
                        val fingerprint = pixelRevision(image)
                        val faceRegions = faces.value.detect(image, SubjectKind.PERSON).map { it.region }
                        val personRegions = people.value.detect(image)
                        fingerprint to faceRegions.map { face -> describePersonGarment(image, face, faceRegions, personRegions, parser.value) }
                    }
                    currentCoroutineContext().ensureActive()
                    val valid = measured != null && verifiedStore.save(photo.uri, measured.first, dependencies,
                        measured.second.second, measured.second.first)
                    if (valid) {
                        evidence[photo.uri] = checkNotNull(measured).second.second
                        revisions[photo.uri] = measured.first
                        processed++
                    } else unavailable++
                } else unavailable++
                progress(position + 1, photos.size)
            }
        } finally {
            try {
                if (parser.isInitialized()) parser.value.close()
            } finally {
                try {
                    if (people.isInitialized()) people.value.close()
                } finally {
                    if (faces.isInitialized()) faces.value.close()
                }
            }
        }
        val current = PhotoEvidenceValidation(library).current(photos, revisions)
        for (uri in evidence.keys.toList()) {
            if (uri in current) continue
            evidence.remove(uri)
            if (uri in cachedUris) cached-- else processed--
            unavailable++
        }
        return GarmentAnalysisSummary(cached, processed, unavailable, evidence)
    }
}
