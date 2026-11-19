package org.photolibrary.app.library

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.photolibrary.app.semantic.PhotoEmbedding
import org.photolibrary.app.semantic.ResidualVisualRanker
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.PhotoQueryPlan

internal data class ContentSearchResults(val related: List<Uri>, val location: NamedSearch? = null,
    val visualScores: Map<Uri, Float> = emptyMap(),
)

internal class PhotoContentSearch(
    private val locations: PhotoLocations,
    private val validation: PhotoEvidenceValidation,
    private val reranker: ResidualVisualRanker? = null,
) {
    suspend fun search(
        plan: PhotoQueryPlan,
        photos: List<SelectedPhoto>,
        embeddings: Map<Uri, PhotoEmbedding>,
        unavailable: UnavailableSources = UnavailableSources.NONE,
        encodeQuery: suspend () -> PhotoEmbedding,
    ): ContentSearchResults = searchVariants(plan, photos, embeddings, unavailable) { listOf(encodeQuery()) }

    suspend fun searchVariants(
        plan: PhotoQueryPlan,
        photos: List<SelectedPhoto>,
        embeddings: Map<Uri, PhotoEmbedding>,
        unavailable: UnavailableSources,
        encodeQueries: suspend () -> List<PhotoEmbedding>,
    ): ContentSearchResults {
        val nearby = plan.nearby
        val location = nearby?.let { withContext(Dispatchers.IO) { locations.search(it, photos, unavailable) } }
        if (nearby != null && location !is NamedSearch.NearbyMatches) return ContentSearchResults(emptyList(), location)
        val scope = (location as? NamedSearch.NearbyMatches)?.photos ?: plan.relatedScope
        val accessible = photos.mapTo(hashSetOf()) { it.uri }
        val eligible: (Uri) -> Boolean = { it in accessible && (scope == null || it in scope) }
        if (embeddings.isEmpty() || scope?.isEmpty() == true) return ContentSearchResults(emptyList(), location)
        val vectors = encodeQueries()
        val candidates = withContext(Dispatchers.Default) { rankVisualVariants(vectors, embeddings, 100, eligible) }
        val baseline = candidates.map { it.uri }
        val query = vectors.singleOrNull()
        val ranked = if (plan.canRankContents && scope == null && nearby == null && query != null && reranker != null) {
            withContext(Dispatchers.Default) { reranker.reorder(query, baseline, embeddings) }
        } else baseline
        val scores = candidates.associate { it.uri to it.score }
        if (nearby == null) return ContentSearchResults(ranked, visualScores = scores)
        val required = ranked.toSet() + nearby.anchors
        val expected = photos.filter { it.uri in required }.mapNotNull { photo ->
            photo.sourceRevision?.let { photo.uri to "source:$it" }
        }.toMap()
        val current = withContext(Dispatchers.IO) { validation.current(photos, expected) }
        if (!withContext(Dispatchers.IO) { locations.evidencePermitted() }) return ContentSearchResults(emptyList(), NamedSearch.LocationPermission)
        if (nearby.anchors.any { it !in current }) return ContentSearchResults(emptyList(), NamedSearch.Unresolved)
        val remaining = ranked.filter { it in current }
        val retainedIds = remaining.toHashSet()
        val proximityOrder = (location as NamedSearch.NearbyMatches).photos.filter { it in retainedIds }
        val retained = fuseRelated(remaining, proximityOrder, secondWeight = 0.25)
        return ContentSearchResults(retained, NamedSearch.NearbyMatches(retained.toSet()), scores.filterKeys { it in current })
    }
}
