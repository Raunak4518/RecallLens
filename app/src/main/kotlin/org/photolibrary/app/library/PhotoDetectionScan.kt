package org.photolibrary.app.library

import android.net.Uri
import org.photolibrary.app.identity.IdentityEncoder
import org.photolibrary.app.identity.IdentityModelFactory
import org.photolibrary.app.identity.IdentityVector
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
    private val identity: IdentityModelFactory? = null,
) {
    suspend fun scan(photos: List<SelectedPhoto>, publish: suspend (Uri, List<NameSuggestion>) -> Unit) {
        val encoders = mutableMapOf<SubjectKind, IdentityEncoder>()
        try {
            models.open().use { detector ->
                for (photo in photos) {
                    currentCoroutineContext().ensureActive()
                    val measured = library.inspectPhoto(photo) { image ->
                        detector.detect(image).mapIndexed { index, detection ->
                            val kind = detection.kind
                            val encoder = if (index < 8 && identity?.available(kind) == true)
                                encoders.getOrPut(kind) { identity.open(kind) } else null
                            detection to encoder?.encode(image, detection.region, detection)
                        }
                    }
                    val suggestions = measured?.let { (revision, regions) ->
                        regions.map { (detection, vector) -> NameSuggestion(photo, revision, detection, vector) }
                    }.orEmpty()
                    currentCoroutineContext().ensureActive()
                    publish(photo.uri, suggestions)
                }
            }
        } finally {
            encoders.values.forEach { it.close() }
        }
    }
}
