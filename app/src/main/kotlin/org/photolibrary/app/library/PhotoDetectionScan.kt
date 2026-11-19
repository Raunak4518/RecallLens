package org.photolibrary.app.library

import android.net.Uri
import org.photolibrary.app.identity.IdentityEncoder
import org.photolibrary.app.identity.IdentityModelFactory
import org.photolibrary.app.identity.IdentityVector
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexedPhoto
import org.photolibrary.app.index.ModelRevision
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.photolibrary.app.detection.DetectedRegion
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.detection.area
import org.photolibrary.app.detection.intersection
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.SubjectKind

internal data class NameSuggestion(val photo: SelectedPhoto, val revision: String, val detection: DetectedRegion,
    val descriptor: IdentityVector? = null,
) {
    fun isConfirmed(names: NamedLibrary): Boolean = names.referencesIn(photo.uri).any { reference ->
        if (reference.revision != revision) return@any false
        val region = reference.region ?: return@any false
        val isPerson = names.kindOf(reference.subjectId) == SubjectKind.PERSON
        if (isPerson != (detection.kind == SubjectKind.PERSON)) return@any false
        val candidate = detection.region
        intersection(region, candidate) / maxOf(region.area(), candidate.area()) >= .55f
    }
}

internal data class SuggestionReview(
    val open: Boolean = false,
    val scanning: Boolean = false,
    val failed: Boolean = false,
    val scanned: Set<Uri> = emptySet(),
    val candidates: List<NameSuggestion> = emptyList(),
    val selected: NameSuggestion? = null,
    val selectedGroup: List<NameSuggestion> = emptyList(),
)

internal class PhotoDetectionScan(
    private val library: PhotoLibrary,
    private val models: OnnxRegionModels,
    private val identity: IdentityModelFactory,
    private val storage: EncryptedPhotoIndex,
) {
    private fun revision() = ModelRevision("suggested-regions", "v1:" + models.revision +
        listOf(SubjectKind.PERSON, SubjectKind.OBJECT).joinToString(":") {
            if (identity.available(it)) identity.fingerprint(it) else "unavailable"
        }, PHOTO_REFERENCE_EDGE)

    suspend fun dismiss(suggestion: NameSuggestion) {
        val uri = suggestion.photo.uri
        storage.openSuggestions().use { store ->
            val model = revision()
            val row = store.readOne(uri) ?: return
            if (row.sourceRevision != suggestion.revision || row.model != model) return
            if (library.referenceRevision(uri) != suggestion.revision) return
            store.save(uri, row.copy(output = row.output.map {
                if (it.detection == suggestion.detection) it.copy(dismissed = true) else it
            }))
        }
    }

    suspend fun scan(photos: List<SelectedPhoto>, unavailable: UnavailableSources,
        publish: suspend (Map<Uri, List<NameSuggestion>>) -> Unit,
    ) {
        val model = revision()
        val scope = photos.mapTo(hashSetOf()) { it.uri }
        storage.openSuggestions().use { store ->
            val cached = store.reconcile(scope, scope, unavailable)
            val pending = mutableListOf<SelectedPhoto>()
            val ready = linkedMapOf<Uri, List<NameSuggestion>>()
            for (photo in photos) {
                currentCoroutineContext().ensureActive()
                val row = cached[photo.uri]
                val actual = photo.sourceRevision?.let { "source:$it" }
                    ?: if (row != null) library.referenceRevision(photo.uri) else null
                if (row != null && row.model == model && actual != null && row.sourceRevision == actual) {
                    ready[photo.uri] = suggestions(photo, actual, row.output)
                } else pending.add(photo)
            }
            if (ready.isNotEmpty()) publish(ready)
            if (pending.isNotEmpty()) detect(pending, model, store::save, publish)
        }
    }

    private fun suggestions(photo: SelectedPhoto, revision: String, regions: List<SuggestionRegion>) =
        regions.filterNot { it.dismissed }.map { NameSuggestion(photo, revision, it.detection, it.descriptor) }

    private suspend fun detect(
        photos: List<SelectedPhoto>,
        model: ModelRevision,
        save: (Uri, IndexedPhoto<List<SuggestionRegion>>) -> Unit,
        publish: suspend (Map<Uri, List<NameSuggestion>>) -> Unit,
    ) {
        val encoders = mutableMapOf<SubjectKind, IdentityEncoder>()
        try {
            models.open().use { detector ->
                val batch = linkedMapOf<Uri, List<NameSuggestion>>()
                for (photo in photos) {
                    currentCoroutineContext().ensureActive()
                    val measured = library.inspectPhoto(photo) { image ->
                        detector.detect(image).groupBy { it.kind }.flatMap { (kind, regions) ->
                            val encoder = if (identity.available(kind)) encoders.getOrPut(kind) { identity.open(kind) } else null
                            regions.mapIndexed { index, detection ->
                                val vector = if (kind == SubjectKind.PERSON || index < 8)
                                    encoder?.encode(image, detection.region, detection) else null
                                SuggestionRegion(detection.copy(landmarks = emptyList()), vector)
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    batch[photo.uri] = measured?.let { (source, regions) ->
                        save(photo.uri, IndexedPhoto(source, null, model, regions))
                        suggestions(photo, source, regions)
                    }.orEmpty()
                    if (batch.size == 8) {
                        publish(batch.toMap())
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) publish(batch)
            }
        } finally {
            encoders.values.forEach { it.close() }
        }
    }
}
