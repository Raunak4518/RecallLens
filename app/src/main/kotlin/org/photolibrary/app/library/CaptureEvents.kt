package org.photolibrary.app.library

import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import org.json.JSONObject
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.PhotoResultGroups
import org.photolibrary.app.subjects.searchNamed
import org.photolibrary.app.subjects.subjectWords

internal data class CaptureEvent(val name: String, val aliases: Set<List<String>>, val zone: ZoneId,
    val visualQuery: String, val dates: List<LocalDate>)

internal data class EventPhotoQuery(val event: CaptureEvent, val year: Int?, val photoScope: Set<Uri>? = null) {
    private val dates get() = event.dates.filter { year == null || it.year == year }
    val hasCalendarDates get() = dates.isNotEmpty()

    fun groups(photos: List<SelectedPhoto>, visual: List<Uri> = emptyList()): PhotoResultGroups {
        val calendar = dates
        val ranked = if (photoScope == null) visual else visual.filter { it in photoScope }
        val dated = if (calendar.isEmpty()) emptyList() else photos.mapNotNull { photo ->
            if (photoScope != null && photo.uri !in photoScope) return@mapNotNull null
            val millis = photo.capturedAtMillis?.takeIf { it > 0 } ?: return@mapNotNull null
            val day = Instant.ofEpochMilli(millis).atZone(event.zone).toLocalDate()
            val nearby = calendar.minOf { abs(ChronoUnit.DAYS.between(it, day)) }
            if (nearby <= 2) photo.uri to nearby else null
        }.sortedWith(compareBy<Pair<Uri, Long>> { it.second }.thenBy { it.first.toString() }).map { it.first }
        val dateSet = dated.toHashSet()
        val overlaps = ranked.filter { it in dateSet }
        val nearDate = (overlaps + dated).distinct().take(12)
        val visualMatches = ranked.toHashSet()
        val hinted = if (calendar.isEmpty()) emptySet() else photos.asSequence().filter { photo ->
            photo.uri in visualMatches && (photo.capturedAtMillis ?: 0) <= 0 &&
                photo.captureDateHint?.let { hint ->
                    val day = Instant.ofEpochMilli(hint.atMillis).atZone(event.zone).toLocalDate()
                    calendar.any { abs(ChronoUnit.DAYS.between(it, day)) <= 2 }
                } == true
        }.map { it.uri }.toHashSet()
        val uncertain = ranked.filter { it in hinted }.take(6)
        return PhotoResultGroups(related = ranked.filterNot { it in nearDate || it in uncertain },
            nearEventDate = nearDate, uncertainDate = uncertain)
    }
}

internal class CaptureEvents(private val events: List<CaptureEvent>) {
    fun resolve(query: String, library: NamedLibrary = NamedLibrary()): EventPhotoQuery? {
        val words = subjectWords(query)
        match(words)?.let { return it }
        val marker = words.indexOfFirst { it in setOf("at", "during", "on") }
        if (marker < 1) return null
        val event = match(words.drop(marker + 1)) ?: return null
        val names = event.year?.let(library::atAliasYear) ?: library
        val subject = searchNamed(words.take(marker).joinToString(" "), names, namedOnly = true) as? NamedSearch.Matches
            ?: return null
        return event.copy(photoScope = subject.photos)
    }

    private fun match(query: List<String>): EventPhotoQuery? {
        val words = query.filterNot { it in queryWords }
        val yearWord = words.singleOrNull { it.length == 4 && it.all(Char::isDigit) }
        val year = yearWord?.toIntOrNull()
        val label = if (yearWord != null) words - yearWord else words
        val event = events.firstOrNull { label in it.aliases } ?: return null
        return EventPhotoQuery(event, year)
    }

    companion object {
        private val queryWords = setOf("show", "find", "photos", "pictures", "pics", "of", "for", "my", "in", "from")

        fun read(text: String): CaptureEvents {
            val root = JSONObject(text)
            require(root.getInt("version") == 1)
            val rows = root.getJSONArray("events")
            return CaptureEvents(List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val labels = row.getJSONArray("aliases")
                val dates = row.getJSONArray("dates")
                CaptureEvent(row.getString("name"), List(labels.length()) { subjectWords(labels.getString(it)) }.toSet(),
                    ZoneId.of(row.getString("zone")), row.getString("visual_query"),
                    List(dates.length()) { LocalDate.parse(dates.getJSONObject(it).getString("day")) })
            })
        }
    }
}
