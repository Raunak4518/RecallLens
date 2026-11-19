package org.photolibrary.app.library

import android.net.Uri
import org.photolibrary.app.index.MarksheetEvidence
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.explicitMarksheetGrades
import org.photolibrary.app.index.marksheetBoardGrade
import org.photolibrary.app.index.marksheetTitleWords
import org.photolibrary.app.index.marksheetYear
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.index.removeMarksheetLevel
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.PhotoResultGroups
import org.photolibrary.app.subjects.hasUnsupportedLogic
import org.photolibrary.app.subjects.searchNamed

private val ordinal = Regex("\\b(1[012]|[1-9])(?:st|nd|rd|th)\\b")
private val numeral = Regex("\\b(10|12)\\b")
private val personalLabels = setOf("me", "myself", "self")
private val requestWords = setOf("i", "want", "need", "to", "see", "show", "find", "get", "look", "search", "for", "me", "my",
    "of", "the", "a", "an", "from", "picture", "pictures", "photo", "photos", "image", "images", "scan", "scans", "where",
    "is", "are", "please", "can", "you", "do", "have", "with", "year", "exam", "issued", "cum", "certificate", "performance")

private enum class DocumentMatch { CONTRADICTED, POSSIBLE, MATCH }

internal data class MarksheetQuery(val grade: Int?, val examYear: String?, val personal: Boolean, val names: Set<String>) {
    fun search(photos: List<SelectedPhoto>, local: Map<Uri, PhotoText>, cloud: Map<Uri, PhotoText>): PhotoResultGroups {
        val matches = mutableListOf<Uri>()
        val possible = mutableListOf<Uri>()
        for (photo in photos) {
            val native = local[photo.uri]?.marksheetEvidence?.rank(this)
            val approved = cloud[photo.uri]?.marksheetEvidence?.rank(this)
            if (native == DocumentMatch.CONTRADICTED || approved == DocumentMatch.CONTRADICTED) continue
            val rank = if (native == null) approved else if (approved == null) native else maxOf(native, approved)
            when (rank) {
                DocumentMatch.MATCH -> matches += photo.uri
                DocumentMatch.POSSIBLE -> possible += photo.uri
                else -> Unit
            }
        }
        return PhotoResultGroups(documents = matches, possibleDocuments = possible.take(24))
    }
}

internal fun marksheetQuery(query: String, library: NamedLibrary): MarksheetQuery? {
    val normalized = normalizePhotoText(query)
    if (!marksheetTitleWords.containsMatchIn(normalized) || hasUnsupportedLogic(normalized.split(' ')) ||
        searchNamed(query, library, namedOnly = false) is NamedSearch.Matches) return null
    val words = normalized.split(' ')
    val unparsed = removeMarksheetLevel(marksheetTitleWords.replace(normalized, " "))
        .let { ordinal.replace(it, " ") }.let { numeral.replace(it, " ") }.let { marksheetYear.replace(it, " ") }
    if (unparsed.split(' ').any { it.isNotEmpty() && it !in requestWords }) return null
    val grades = explicitMarksheetGrades(normalized).ifEmpty {
        marksheetBoardGrade(normalized)?.let(::setOf) ?: ordinal.findAll(normalized).map { it.groupValues[1].toInt() }.toSet()
    }.ifEmpty { numeral.findAll(normalized).map { it.value.toInt() }.toSet() }
    val years = marksheetYear.findAll(normalized).map { it.value }.toSet()
    if (grades.size > 1 || years.size > 1) return null
    val personal = "my" in words
    val owner = library.subjectFor(listOf("me"))?.takeIf { personal }
    val names = owner?.let { listOf(it.name) + it.aliases }.orEmpty().map(::normalizePhotoText)
        .filter { it.length >= 2 && it !in personalLabels }.toSet()
    return MarksheetQuery(grades.singleOrNull(), years.singleOrNull(), personal, names)
}

private fun MarksheetEvidence.rank(query: MarksheetQuery): DocumentMatch {
    if (query.grade != null && grades.isNotEmpty() && grades.singleOrNull() != query.grade) return DocumentMatch.CONTRADICTED
    if (query.examYear != null && examYears.isNotEmpty() && query.examYear !in examYears) return DocumentMatch.CONTRADICTED
    val ownerMatches = candidates.isNotEmpty() && candidates.all { it in query.names }
    if (query.personal && query.names.isNotEmpty() && candidates.isNotEmpty() && !ownerMatches) return DocumentMatch.CONTRADICTED
    val knownGrade = query.grade == null || query.grade in grades
    val knownYear = query.examYear == null || query.examYear in examYears
    return if (hasResultFields && knownGrade && knownYear && (!query.personal || ownerMatches)) DocumentMatch.MATCH else DocumentMatch.POSSIBLE
}
