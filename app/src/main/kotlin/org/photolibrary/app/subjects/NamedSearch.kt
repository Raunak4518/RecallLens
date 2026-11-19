package org.photolibrary.app.subjects

import android.net.Uri

internal sealed interface NamedSearch {
    data class Matches(val photos: Set<Uri>) : NamedSearch
    data class Nearby(val candidates: Set<Uri>?, val anchors: Set<Uri>) : NamedSearch
    data class NearbyMatches(val photos: Set<Uri>) : NamedSearch
    data object LocationPermission : NamedSearch
    data object NoPlaceCoordinates : NamedSearch
    data object Unknown : NamedSearch
    data object Unresolved : NamedSearch
}

private val photoPhrases = listOf(
    "show me photos of", "show me pictures of", "find pictures of", "find photos of", "photos of", "pictures of",
    "a photo of", "a picture of", "my photo", "my pictures", "my photos", "my image", "my images",
    "show me",
).map(::subjectWords)
internal const val NEARBY_KM = 30
private val connectors = setOf("and", "with")
private val identityWords = setOf("my", "myself")
private val proximityWords = setOf("near", "around", "in")

internal fun subjectForReview(query: String, library: NamedLibrary): NamedSubject? {
    val words = subjectWords(query)
    val prefix = photoPhrases.firstOrNull { words.take(it.size) == it }.orEmpty()
    if (prefix.firstOrNull() == "my" && library.selfSubjectId == null) return library.subjectFor(words)
    return library.subjectFor(words) ?: library.subjectFor(
        if (prefix.firstOrNull() == "my") listOf("me") + words.drop(prefix.size) else words.drop(prefix.size),
    )
}

internal fun searchNamed(query: String, library: NamedLibrary, namedOnly: Boolean): NamedSearch? {
    val words = subjectWords(query)
    if (words.isEmpty()) return null
    library.labels[words]?.let { return NamedSearch.Matches(library.photosOf(setOf(it))) }
    namedDisjunction(query, library)?.let { return it }
    val location = nearbyPlace(words, library)
    if (location != null) {
        val (marker, place) = location
        val prefix = words.take(marker)
        val candidates = if (prefix.isEmpty()) null else {
            val names = matchLabels(prefix, library, namedOnly = true) as? NamedSearch.Matches
                ?: return NamedSearch.Unresolved
            names.photos
        }
        return NamedSearch.Nearby(candidates, library.photosOf(setOf(place)))
    }
    val prefix = photoPhrases.firstOrNull { words.take(it.size) == it }.orEmpty()
    if (library.hasInactiveDatedAlias(words.drop(prefix.size))) return NamedSearch.Unresolved
    return matchLabels(words, library, namedOnly)
}

internal fun namedDisjunction(query: String, library: NamedLibrary): NamedSearch.Matches? {
    if (query.any { it in "\"“”()" }) return null
    val words = subjectWords(query)
    if (library.subjectFor(words) != null || words.count { it == "or" } != 1) return null
    val boundary = words.indexOf("or")
    val left = words.take(boundary)
    val right = words.drop(boundary + 1)
    val leftMatch = matchLabels(left, library, namedOnly = true) as? NamedSearch.Matches ?: return null
    val rightMatch = matchLabels(right, library, namedOnly = true) as? NamedSearch.Matches ?: return null
    return NamedSearch.Matches(leftMatch.photos + rightMatch.photos)
}

internal fun nearbyPlace(words: List<String>, library: NamedLibrary): Pair<Int, Long>? =
    words.indices.firstNotNullOfOrNull { position ->
        if (words[position] !in proximityWords) null
        else library.place(words.drop(position + 1))?.let { position to it }
    }

private fun matchLabels(words: List<String>, library: NamedLibrary, namedOnly: Boolean): NamedSearch? {
    val prefix = photoPhrases.firstOrNull { words.take(it.size) == it }.orEmpty()
    if (prefix.firstOrNull() == "my" && library.selfSubjectId == null) return NamedSearch.Unknown
    val terms = if (prefix.firstOrNull() == "my") {
        listOf("me") + words.drop(prefix.size)
    } else words.drop(prefix.size)
    val ids = mutableSetOf<Long>()
    var position = 0
    var requiresSubject = true
    var unresolved = false
    while (position < terms.size) {
        val label = library.longestLabelAt(terms, position)
        if (requiresSubject && label != null) {
            ids += library.labels.getValue(label)
            position += label.size
            requiresSubject = false
        } else {
            if (requiresSubject || terms[position] !in connectors) unresolved = true
            requiresSubject = true
            position++
        }
    }
    if (ids.isEmpty()) {
        val personalQuery = terms.firstOrNull() == "me" || terms.any(identityWords::contains)
        return if (namedOnly || personalQuery) NamedSearch.Unknown else null
    }
    return if (unresolved || requiresSubject) NamedSearch.Unresolved else NamedSearch.Matches(library.photosOf(ids))
}

private fun NamedLibrary.longestLabelAt(terms: List<String>, position: Int): List<String>? =
    labels.keys.asSequence().filter { it.size <= terms.size - position && it == terms.subList(position, position + it.size) }
        .maxByOrNull { it.size }

internal fun confirmedEntityScope(query: String, library: NamedLibrary): Set<Uri>? {
    val words = subjectWords(query)
    if (hasUnsupportedLogic(words)) return null
    val prefix = photoPhrases.firstOrNull { words.take(it.size) == it }.orEmpty()
    val terms = if (prefix.firstOrNull() == "my") listOf("me") + words.drop(prefix.size) else words.drop(prefix.size)
    val ids = mutableSetOf<Long>()
    var position = 0
    while (position < terms.size) {
        val label = library.longestLabelAt(terms, position)
        if (label == null) { position++; continue }
        val id = library.labels.getValue(label)
        if (library.kindOf(id) !in setOf(SubjectKind.PLACE, SubjectKind.ORGANIZATION)) ids += id
        position += label.size
    }
    return if (ids.isEmpty()) null else library.photosOf(ids)
}

internal fun hasUnsupportedLogic(words: List<String>): Boolean =
    words.any { it in setOf("not", "without", "except", "or", "neither", "nor", "excluding") } ||
        words.windowed(2).any { it in listOf(listOf("instead", "of"), listOf("apart", "from"), listOf("rather", "than")) }
