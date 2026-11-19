package org.photolibrary.app.library

import android.net.Uri
import org.photolibrary.app.index.GarmentEvidenceStore
import org.photolibrary.app.subjects.ClothingQuery
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.SubjectKind

internal class ClothingSearch(
    private val analysis: CachedGarmentAnalysis,
    private val store: GarmentEvidenceStore,
    private val locations: PhotoLocations,
    private val validation: PhotoEvidenceValidation,
) {
    val available get() = analysis.available

    fun confirmedPhotos(named: NamedLibrary, photos: List<SelectedPhoto>): List<SelectedPhoto> {
        val people = named.subjects.filter { it.kind == SubjectKind.PERSON }.mapTo(hashSetOf()) { it.id }
        val references = named.references.filter { it.subjectId in people && it.region != null }.mapTo(hashSetOf()) { it.uri }
        return photos.filter { it.uri in references }
    }

    suspend fun analyze(photos: List<SelectedPhoto>, progress: suspend (Int, Int) -> Unit) = analysis.scan(photos, progress)

    suspend fun search(
        query: ClothingQuery, named: NamedLibrary, photos: List<SelectedPhoto>,
        unavailable: UnavailableSources = UnavailableSources.NONE,
    ): ClothingResults {
        val references = named.references.groupBy { it.uri }
        val revisions = photos.mapNotNull { photo ->
            val revision = photo.sourceRevision?.let { "source:$it" } ?: references[photo.uri]
                ?.map { it.revision }?.distinct()?.singleOrNull()
            revision?.let { photo.uri to it }
        }.toMap()
        val fractions = store.colorFractions(query.personId, query.color, named, revisions, analysis.dependencies)
        val colored = fractions.filterValues { it >= .5f }.keys
        val base = when (val requirement = query.named) {
            is NamedSearch.Matches -> requirement
            is NamedSearch.Nearby -> locations.search(requirement, photos, unavailable)
            else -> NamedSearch.Unresolved
        }
        val matches = when (base) {
            is NamedSearch.Matches -> NamedSearch.Matches(base.photos intersect colored)
            is NamedSearch.NearbyMatches -> NamedSearch.NearbyMatches(base.photos intersect colored)
            else -> base
        }
        val candidates = when (base) {
            is NamedSearch.Matches -> base.photos
            is NamedSearch.NearbyMatches -> base.photos
            else -> emptySet()
        }
        val related = fractions.entries.filter { it.key in candidates && it.key !in colored && it.value >= .25f }
            .sortedWith(compareByDescending<Map.Entry<Uri, Float>> { it.value }.thenBy { it.key.toString() })
            .take(5).map { it.key }
        if (matches !is NamedSearch.Matches && matches !is NamedSearch.NearbyMatches) return ClothingResults(matches, emptyList())
        val required = when (matches) {
            is NamedSearch.Matches -> matches.photos
            is NamedSearch.NearbyMatches -> matches.photos
            else -> emptySet()
        } + related + (query.named as? NamedSearch.Nearby)?.anchors.orEmpty()
        val current = validation.current(photos, revisions.filterKeys { it in required })
        val anchors = (query.named as? NamedSearch.Nearby)?.anchors.orEmpty()
        if (anchors.any { it !in current }) return ClothingResults(NamedSearch.Unresolved, emptyList())
        val verified = when (matches) {
            is NamedSearch.Matches -> matches.copy(photos = matches.photos intersect current)
            is NamedSearch.NearbyMatches -> matches.copy(photos = matches.photos intersect current)
            else -> matches
        }
        return ClothingResults(verified, related.filter { it in current })
    }
}

internal data class ClothingResults(val matches: NamedSearch, val related: List<Uri>)
