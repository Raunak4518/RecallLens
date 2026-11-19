package org.photolibrary.app.library

import android.net.Uri
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.PhotoQueryPlan
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.clothingQuery
import org.photolibrary.app.subjects.confirmedEntityScope
import org.photolibrary.app.subjects.hasUnsupportedLogic
import org.photolibrary.app.subjects.planPhotoQuery
import org.photolibrary.app.subjects.searchNamed
import org.photolibrary.app.subjects.subjectWords

internal enum class CaptureYearRelation { IN, BEFORE, AFTER }

internal data class CaptureYearFilter(val relation: CaptureYearRelation, val year: Int,
    val start: LocalDate = LocalDate.of(year, 1, 1),
    val endExclusive: LocalDate = LocalDate.of(year + 1, 1, 1),
    val relativeLabel: String? = null,
) {
    fun photos(photos: List<SelectedPhoto>, zone: ZoneId = ZoneId.systemDefault()): List<SelectedPhoto> {
        val window = window(zone)
        return photos.filter { it.capturedAtMillis?.let(window::contains) == true }
    }

    fun uncertainPhotos(photos: List<SelectedPhoto>, zone: ZoneId = ZoneId.systemDefault()): List<SelectedPhoto> {
        val window = window(zone)
        return photos.filter { photo ->
            (photo.capturedAtMillis ?: 0) <= 0 && photo.captureDateHint?.atMillis?.let(window::contains) == true
        }
    }

    private fun window(zone: ZoneId): LongRange {
        val first = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val next = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (relation) {
            CaptureYearRelation.IN -> maxOf(1, first) until next
            CaptureYearRelation.BEFORE -> 1L until first
            CaptureYearRelation.AFTER -> maxOf(1, next)..Long.MAX_VALUE
        }
    }
}

internal data class CaptureYearQuery(
    val phrase: String,
    val filter: CaptureYearFilter? = null,
    val unsupported: Boolean = false,
) {
    fun photos(photos: List<SelectedPhoto>): List<SelectedPhoto> = filter?.photos(photos) ?: photos

    fun uncertainPhotos(photos: List<SelectedPhoto>): List<SelectedPhoto> = filter?.uncertainPhotos(photos).orEmpty()

    fun names(library: NamedLibrary): NamedLibrary {
        val window = filter ?: return library
        val year = if (window.relation == CaptureYearRelation.IN && window.start.year == window.endExclusive.minusDays(1).year)
            window.start.year else 0
        return library.atAliasYear(year)
    }

    fun plan(library: NamedLibrary, mode: SearchMode, eligiblePhotos: List<SelectedPhoto>): PhotoQueryPlan {
        if (mode == SearchMode.TEXT) return PhotoQueryPlan(null)
        val namedOnly = mode == SearchMode.NAMED
        val named = names(library)
        val plan = planPhotoQuery(phrase, named, namedOnly)
        if (unsupported) return plan.copy(
            named = if (namedOnly) NamedSearch.Unresolved else null,
            requirements = plan.requirements + QueryRequirement.CAPTURE_YEAR,
            partialContents = !namedOnly,
            relatedScope = if (namedOnly) null else plan.relatedScope ?: confirmedEntityScope(phrase, named),
        )
        if (filter == null) return plan
        val scope = eligiblePhotos.mapTo(hashSetOf()) { it.uri }
        return plan.copy(named = plan.named?.inCaptureScope(scope),
            relatedScope = plan.relatedScope?.intersect(scope) ?: scope,
            nearby = plan.nearby?.inCaptureScope(scope))
    }
}

private val captureYearClause = Regex("(?<![\\p{L}\\p{N}])(?:in|before|after)\\s+[0-9]{4}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
private val captureYearSuffix = Regex("(?:^|\\s+)(in|before|after)\\s+([0-9]{4})\\s*[.!?]?\\s*$", RegexOption.IGNORE_CASE)
private val galleryPhrases = setOf(listOf("photos"), listOf("pictures"), listOf("images"),
    listOf("show", "me", "photos"), listOf("show", "me", "pictures"), listOf("show", "me", "images"))

private val relativeSuffix = Regex("""(?:^|\s+)(today|yesterday|(?:this|last) (?:week|month|year))\s*[.!?]?\s*$""", RegexOption.IGNORE_CASE)

private fun relativeWindow(label: String, today: LocalDate): Pair<LocalDate, LocalDate> {
    val offset = if (label.startsWith("last")) -1L else 0L
    return when (label.substringAfterLast(' ')) {
        "week" -> today.minusDays(today.dayOfWeek.value - 1L).plusWeeks(offset).let { it to it.plusWeeks(1) }
        "month" -> today.withDayOfMonth(1).plusMonths(offset).let { it to it.plusMonths(1) }
        "year" -> today.withDayOfYear(1).plusYears(offset).let { it to it.plusYears(1) }
        "yesterday" -> today.minusDays(1) to today
        else -> today to today.plusDays(1)
    }
}

internal fun captureYearQuery(query: String, library: NamedLibrary, mode: SearchMode,
    today: LocalDate = LocalDate.now()): CaptureYearQuery {
    val unquoted = LiteralFilter.unquoted(query)
    if (mode != SearchMode.TEXT && !captureYearClause.containsMatchIn(unquoted)) {
        val suffix = relativeSuffix.find(query)
        if (suffix != null && !hasUnsupportedLogic(subjectWords(unquoted)) &&
            searchNamed(query, library, namedOnly = true) !is NamedSearch.Matches) {
            val label = suffix.groupValues[1].lowercase(Locale.ROOT)
            val prefix = query.substring(0, suffix.range.first).trimEnd()
            val (start, end) = relativeWindow(label, today)
            return CaptureYearQuery(if (subjectWords(prefix) in galleryPhrases) "" else prefix,
                CaptureYearFilter(CaptureYearRelation.IN, start.year, start, end, label))
        }
    }
    if (mode == SearchMode.TEXT || !captureYearClause.containsMatchIn(unquoted)) return CaptureYearQuery(query)
    val named = searchNamed(query, library, namedOnly = mode == SearchMode.NAMED)
    if (named is NamedSearch.Matches || named is NamedSearch.Nearby || clothingQuery(query, library) != null) {
        return CaptureYearQuery(query)
    }
    val suffix = captureYearSuffix.find(query) ?: return CaptureYearQuery(query, unsupported = true)
    val words = subjectWords(unquoted)
    val year = suffix.groupValues[2].toInt()
    if (year == 0 || hasUnsupportedLogic(words) || captureYearClause.findAll(unquoted).count() != 1) {
        return CaptureYearQuery(query, unsupported = true)
    }
    val relation = CaptureYearRelation.valueOf(suffix.groupValues[1].uppercase(Locale.ROOT))
    val prefix = query.substring(0, suffix.range.first).trimEnd()
    return CaptureYearQuery(if (subjectWords(prefix) in galleryPhrases) "" else prefix, CaptureYearFilter(relation, year))
}

internal fun NamedSearch.Nearby.inCaptureScope(scope: Set<Uri>): NamedSearch.Nearby =
    copy(candidates = candidates?.intersect(scope) ?: scope)

internal fun NamedSearch.inCaptureScope(scope: Set<Uri>): NamedSearch = when (this) {
    is NamedSearch.Matches -> copy(photos = photos intersect scope)
    is NamedSearch.Nearby -> inCaptureScope(scope)
    is NamedSearch.NearbyMatches -> copy(photos = photos intersect scope)
    else -> this
}
