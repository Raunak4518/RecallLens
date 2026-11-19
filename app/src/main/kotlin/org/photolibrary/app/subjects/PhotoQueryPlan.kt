package org.photolibrary.app.subjects

import android.net.Uri

internal enum class QueryRequirement {
    SELF_IDENTITY,
    CONFIRMED_NAME,
    PERSON_CLOTHING_RELATION,
    SPATIAL_RELATION,
    LOGICAL_RELATION,
    PLACE_REFERENCE,
    LOCATION_VERIFICATION,
    CAPTURE_YEAR,
    SUPPORTED_QUERY,
}

internal data class PhotoQueryPlan(
    val named: NamedSearch?,
    val requirements: Set<QueryRequirement> = emptySet(),
    val partialContents: Boolean = false,
    val relatedScope: Set<Uri>? = null,
    val nearby: NamedSearch.Nearby? = null,
    val unverifiedRelation: Boolean = false,
) {
    val visibleRequirements get() = if (unverifiedRelation) requirements + QueryRequirement.SPATIAL_RELATION else requirements
    val canRankContents get() = named == null && requirements.isEmpty()
    val canRankSemantically get() = canRankContents || partialContents
}

internal data class PhotoResultGroups(
    val confirmed: List<Uri> = emptyList(),
    val rememberedText: List<Uri> = emptyList(),
    val related: List<Uri> = emptyList(),
    val estimatedColor: List<Uri> = emptyList(),
    val nearEventDate: List<Uri> = emptyList(),
    val documents: List<Uri> = emptyList(),
    val possibleDocuments: List<Uri> = emptyList(),
    val uncertainDate: List<Uri> = emptyList(),
    val versionFamilies: Map<Uri, List<Uri>> = emptyMap(),
)

internal fun planPhotoQuery(query: String, library: NamedLibrary, namedOnly: Boolean): PhotoQueryPlan {
    namedSpatialPlan(query, library, namedOnly)?.let { return it }
    val named = searchNamed(query, library, namedOnly)
    if (named != null && named != NamedSearch.Unknown && named != NamedSearch.Unresolved) return PhotoQueryPlan(named)
    val words = subjectWords(query)
    val place = nearbyPlace(words, library)
    val requirements = buildSet {
        val personal = words.any { it in setOf("me", "myself") } ||
            words.zipWithNext().any { (first, second) -> first == "my" && second in setOf("photo", "photos", "picture", "pictures", "image", "images") }
        if (personal && library.selfSubjectId == null) add(QueryRequirement.SELF_IDENTITY)
        val clothing = words.indices.any { index ->
            words[index] == "wearing" || words[index] == "in" &&
                (words.drop(index + 1).any { it in setOf("shirt", "shirts", "jacket", "dress", "coat", "sweater", "hoodie") } ||
                library.labels.any { (label, subjectId) ->
                    library.kindOf(subjectId) == SubjectKind.CLOTHING && words.drop(index + 1).take(label.size) == label
                })
        }
        if (clothing) add(QueryRequirement.PERSON_CLOTHING_RELATION)
        if (words.any { it in setOf("beside", "behind", "above", "below") } ||
            words.windowed(2).any { it == listOf("left", "of") || it == listOf("right", "of") } ||
            words.windowed(3).any { it == listOf("in", "front", "of") }) add(QueryRequirement.SPATIAL_RELATION)
        if (hasUnsupportedLogic(words)) add(QueryRequirement.LOGICAL_RELATION)
        if (place != null) add(QueryRequirement.LOCATION_VERIFICATION)
        if (words.any { it in setOf("near", "around") } &&
            words.indices.none { index -> library.place(words.drop(index + 1)) != null }) {
            add(QueryRequirement.PLACE_REFERENCE)
        }
        if (isEmpty() && named != null) add(if (named == NamedSearch.Unknown) QueryRequirement.CONFIRMED_NAME else QueryRequirement.SUPPORTED_QUERY)
    }
    if (requirements.isEmpty()) return PhotoQueryPlan(named)
    val scope = if (namedOnly) null else confirmedEntityScope(query, library)
    val nearby = if (!namedOnly && place != null && !hasUnsupportedLogic(words)) {
        NamedSearch.Nearby(scope, library.photosOf(setOf(place.second)))
    } else null
    return PhotoQueryPlan(named, requirements, partialContents = !namedOnly, relatedScope = scope, nearby = nearby)
}

internal fun groupPhotoResults(
    plan: PhotoQueryPlan,
    accessible: Set<Uri>,
    textMatches: List<Uri> = emptyList(),
    rankedContents: List<Uri> = emptyList(),
    identitySuggestions: List<Uri> = emptyList(),
    namedOnly: Boolean = false,
): PhotoResultGroups {
    val confirmed = when (val named = plan.named) {
        is NamedSearch.Matches -> named.photos
        is NamedSearch.NearbyMatches -> named.photos
        else -> emptySet()
    }.filter { it in accessible && plan.requirements.isEmpty() }
    if (namedOnly) return PhotoResultGroups(confirmed = confirmed)
    val confirmedSet = confirmed.toSet()
    val text = if (plan.canRankContents) textMatches.distinct().filter { it in accessible && it !in confirmedSet } else emptyList()
    val retained = confirmedSet + text
    val candidates = when {
        plan.nearby != null && QueryRequirement.LOCATION_VERIFICATION in plan.requirements -> emptyList()
        plan.canRankSemantically -> rankedContents
        else -> identitySuggestions
    }
    val related = candidates.distinct().filter {
        it in accessible && it !in retained && (!plan.partialContents || plan.relatedScope == null || it in plan.relatedScope)
    }
    return PhotoResultGroups(confirmed, text, related)
}
