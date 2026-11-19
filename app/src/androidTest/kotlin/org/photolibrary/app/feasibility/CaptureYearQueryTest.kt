package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.CaptureYearFilter
import org.photolibrary.app.library.CaptureYearRelation
import org.photolibrary.app.library.PhotoDateHint
import org.photolibrary.app.library.PhotoDateHintSource
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SearchMode
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.library.captureYearQuery
import org.photolibrary.app.subjects.DatedAliasDraft
import org.photolibrary.app.subjects.DatedSubjectAlias
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.groupPhotoResults

@RunWith(AndroidJUnit4::class)
class CaptureYearQueryTest {
    private val timezone = ZoneId.of("Asia/Kolkata")
    private val start = LocalDate.of(2023, 1, 1).atStartOfDay(timezone).toInstant().toEpochMilli()
    private val next = LocalDate.of(2024, 1, 1).atStartOfDay(timezone).toInstant().toEpochMilli()
    private val older = photo("earlier", start - 1)
    private val first = photo("first", start)
    private val last = photo("last", next - 1)
    private val later = photo("later", next)
    private val undated = photo("undated", null)
    private val zero = photo("zero", 0)
    private val photos = listOf(older, first, last, later, undated, zero)
    private val named = NamedLibrary(listOf(
        NamedSubject(1, "Campus", SubjectKind.ORGANIZATION, emptyList()),
        NamedSubject(2, "Gallery in 2023", SubjectKind.OBJECT, emptyList()),
        NamedSubject(3, "Surat", SubjectKind.PLACE, emptyList()),
        NamedSubject(4, "My vehicle", SubjectKind.OBJECT, emptyList()),
    ), listOf(SubjectReference(1, older.uri, "older", null), SubjectReference(1, first.uri, "first", null),
        SubjectReference(1, undated.uri, "undated", null), SubjectReference(2, later.uri, "later", null),
        SubjectReference(3, older.uri, "older", null), SubjectReference(4, first.uri, "first", null),
        SubjectReference(4, later.uri, "later", null)))

    @Test
    fun boundariesUseDeviceCalendarYearAndDoNotInventMissingDates() {
        assertEquals(listOf(first, last), CaptureYearFilter(CaptureYearRelation.IN, 2023).photos(photos, timezone))
        assertEquals(listOf(older), CaptureYearFilter(CaptureYearRelation.BEFORE, 2023).photos(photos, timezone))
        assertEquals(listOf(later), CaptureYearFilter(CaptureYearRelation.AFTER, 2023).photos(photos, timezone))
        assertEquals(listOf(last, later), CaptureYearFilter(CaptureYearRelation.IN, 2023).photos(photos, ZoneId.of("UTC")))
    }

    @Test
    fun namedResultsIntersectDateAndConfirmationInsteadOfUsingLookalikes() {
        val capture = captureYearQuery("Campus in 2023", named, SearchMode.NAMED)
        val eligible = checkNotNull(capture.filter).photos(photos, timezone)
        assertEquals("Campus", capture.phrase)
        val plan = capture.plan(named, SearchMode.NAMED, eligible)
        assertEquals(NamedSearch.Matches(setOf(first.uri)), plan.named)
        val results = groupPhotoResults(plan, eligible.mapTo(hashSetOf()) { it.uri },
            rankedContents = listOf(last.uri), identitySuggestions = listOf(last.uri), namedOnly = true)
        assertEquals(listOf(first.uri), results.confirmed)
        assertTrue(results.related.isEmpty())
    }

    @Test
    fun exactLabelsAndTextModeRetainTheLiteralPhraseAndGalleryOnlyQueriesAreRecognized() {
        val saved = captureYearQuery("Gallery in 2023", named, SearchMode.CONTENT)
        assertNull(saved.filter)
        assertEquals(NamedSearch.Matches(setOf(later.uri)), saved.plan(named, SearchMode.CONTENT, photos).named)
        val text = captureYearQuery("invoice in 2023", named, SearchMode.TEXT)
        assertEquals("invoice in 2023", text.phrase)
        assertNull(text.filter)
        val only = captureYearQuery("Show me photos AFTER 2023.", named, SearchMode.CONTENT)
        assertEquals("", only.phrase)
        assertEquals(CaptureYearFilter(CaptureYearRelation.AFTER, 2023), only.filter)
    }

    @Test
    fun nearbyCandidatesAreDatedButOldPlaceAnchorsSurviveAndSpatialConditionStaysPartial() {
        val capture = captureYearQuery("Campus behind my vehicle near Surat in 2023", named, SearchMode.CONTENT)
        val plan = capture.plan(named, SearchMode.CONTENT, checkNotNull(capture.filter).photos(photos, timezone))
        assertEquals(setOf(first.uri), plan.relatedScope)
        assertEquals(NamedSearch.Nearby(setOf(first.uri), setOf(older.uri)), plan.nearby)
        assertTrue(plan.partialContents)
        assertTrue(QueryRequirement.SPATIAL_RELATION in plan.requirements)
        assertTrue(QueryRequirement.LOCATION_VERIFICATION in plan.requirements)
    }

    @Test
    fun ambiguousDateQueriesRemainPartialAndNeverExpandNamedResults() {
        for (phrase in listOf("Campus before 2023 or after 2023", "Campus in 0000", "Campus in 2023 near Surat", "Campus in 2023 before 2024")) {
            val capture = captureYearQuery(phrase, named, SearchMode.CONTENT)
            assertNull(capture.filter)
            assertTrue(capture.unsupported)
            assertTrue(QueryRequirement.CAPTURE_YEAR in capture.plan(named, SearchMode.CONTENT, photos).requirements)
            val strict = captureYearQuery(phrase, named, SearchMode.NAMED).plan(named, SearchMode.NAMED, photos)
            assertFalse(strict.canRankSemantically)
            assertEquals(NamedSearch.Unresolved, strict.named)
        }
    }

    @Test
    fun importDateAndModificationDateStayOutsideConfirmedCaptureMatches() {
        val importHint = PhotoDateHint(start, PhotoDateHintSource.ADDED_TO_GALLERY)
        val modified = zero.copy(captureDateHint = PhotoDateHint(next - 1, PhotoDateHintSource.FILE_MODIFIED))
        val imported = undated.copy(captureDateHint = importHint)
        val conflicting = later.copy(captureDateHint = importHint)
        val input = listOf(first, conflicting, imported, modified)
        val filter = CaptureYearFilter(CaptureYearRelation.IN, 2023)
        assertEquals(listOf(first), filter.photos(input, timezone))
        assertEquals(listOf(imported, modified), filter.uncertainPhotos(input, timezone))
    }

    @Test
    fun coveredAliasSpansSelectTheCorrectSubjectAndKeepPhotosInTheRequestedYear() {
        val historical = named.copy(datedAliases = listOf(
            DatedSubjectAlias(1, DatedAliasDraft("my institute", 2022, 2023)),
            DatedSubjectAlias(4, DatedAliasDraft("my institute", 2024, 2026)),
        ))
        val firstQuery = captureYearQuery("my institute in 2023", historical, SearchMode.CONTENT)
        val firstScope = checkNotNull(firstQuery.filter).photos(photos, timezone)
        assertEquals(NamedSearch.Matches(setOf(first.uri)), firstQuery.plan(historical, SearchMode.CONTENT, firstScope).named)
        val laterQuery = captureYearQuery("my institute in 2024", historical, SearchMode.CONTENT)
        val laterScope = checkNotNull(laterQuery.filter).photos(photos, timezone)
        assertEquals(NamedSearch.Matches(setOf(later.uri)), laterQuery.plan(historical, SearchMode.CONTENT, laterScope).named)
        val expired = captureYearQuery("my institute in 2021", historical, SearchMode.CONTENT)
        assertEquals(NamedSearch.Unresolved, expired.plan(historical, SearchMode.CONTENT, emptyList()).named)
    }

    private fun photo(id: String, date: Long?) = SelectedPhoto(Uri.parse("content://fixture/capture/$id"), "$id.jpg",
        PhotoOrigin.GALLERY, id, date)
}
