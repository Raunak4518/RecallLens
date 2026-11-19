package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.PhotoQueryPlan
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.groupPhotoResults
import org.photolibrary.app.subjects.planPhotoQuery
import org.photolibrary.app.subjects.searchNamed
import org.photolibrary.app.subjects.subjectForReview

@RunWith(AndroidJUnit4::class)
class PhotoQueryPlanTest {
    private val confirmed = Uri.parse("content://fixture/confirmed")
    private val other = Uri.parse("content://fixture/other")
    private val suggestion = Uri.parse("content://fixture/suggestion")
    private val inaccessible = Uri.parse("content://fixture/revoked")
    private val accessible = setOf(confirmed, other, suggestion)
    private val subjects = listOf(
        NamedSubject(1, "Ronak", SubjectKind.PERSON, listOf("Myself")),
        NamedSubject(2, "My car", SubjectKind.OBJECT, emptyList()),
        NamedSubject(3, "Red shirt", SubjectKind.CLOTHING, emptyList()),
        NamedSubject(4, "Surat", SubjectKind.PLACE, emptyList()),
    )
    private val library = NamedLibrary(subjects, listOf(
        SubjectReference(1, confirmed, "1", null),
        SubjectReference(1, other, "1", null),
        SubjectReference(2, confirmed, "1", null),
        SubjectReference(3, confirmed, "1", null),
        SubjectReference(4, confirmed, "1", null),
    ))

    @Test
    fun selfAliasRequiresExplicitPersonEnrollmentAndSupportsIntersections() {
        assertEquals(1L, library.selfSubjectId)
        assertEquals(NamedSearch.Matches(setOf(confirmed, other)), searchNamed("my image", library, true))
        assertEquals(NamedSearch.Matches(setOf(confirmed)), searchNamed("me with my car", library, true))
        assertEquals(subjects.first(), subjectForReview("my photos", library))
        val withoutSelf = library.copy(subjects = subjects.map { it.copy(aliases = emptyList()) })
        assertEquals(setOf(QueryRequirement.SELF_IDENTITY), planPhotoQuery("my photo", withoutSelf, false).requirements)
        val ambiguous = library.copy(subjects = subjects + NamedSubject(5, "Me", SubjectKind.PERSON, emptyList()))
        assertEquals(setOf(QueryRequirement.SELF_IDENTITY), planPhotoQuery("my photo", ambiguous, false).requirements)
        assertEquals(setOf(QueryRequirement.CONFIRMED_NAME), planPhotoQuery("my spaceship", library, false).requirements)
    }

    @Test
    fun unknownRelationsCannotBecomeConfirmedConjunctions() {
        val query = planPhotoQuery("me wearing red shirt in front of my car around Surat", library, false)
        assertTrue(QueryRequirement.PERSON_CLOTHING_RELATION in query.requirements)
        assertTrue(QueryRequirement.SPATIAL_RELATION in query.requirements)
        assertFalse(query.canRankContents)
        val grouped = groupPhotoResults(query, accessible, rankedContents = listOf(suggestion, inaccessible, confirmed))
        assertTrue(grouped.confirmed.isEmpty())
        assertTrue(grouped.related.isEmpty())
        val located = query.copy(requirements = query.requirements - QueryRequirement.LOCATION_VERIFICATION)
        assertEquals(listOf(confirmed), groupPhotoResults(located, accessible, rankedContents = listOf(suggestion, confirmed)).related)
        assertEquals(emptyList<Uri>(), groupPhotoResults(query, accessible, identitySuggestions = listOf(suggestion), namedOnly = true).related)
        assertEquals(setOf(QueryRequirement.PLACE_REFERENCE), planPhotoQuery("my car near unknown city", library, false).requirements)
    }

    @Test
    fun groupsKeepUnconfirmedRelatedResultsOutsideNamedSearch() {
        val query = planPhotoQuery("my car", library, false)
        val grouped = groupPhotoResults(query, accessible,
            textMatches = listOf(other), rankedContents = listOf(other),
            identitySuggestions = listOf(confirmed, suggestion, inaccessible, suggestion))
        assertEquals(listOf(confirmed), grouped.confirmed)
        assertTrue(grouped.rememberedText.isEmpty())
        assertEquals(listOf(suggestion), grouped.related)
        val named = groupPhotoResults(query, accessible, identitySuggestions = listOf(suggestion), namedOnly = true)
        assertEquals(listOf(confirmed), named.confirmed)
        assertTrue(named.related.isEmpty())
        assertEquals(NamedSearch.Matches(setOf(confirmed)), searchNamed("my car", library, true))
        val afterRevocation = groupPhotoResults(query, setOf(other), identitySuggestions = listOf(suggestion))
        assertTrue(afterRevocation.confirmed.isEmpty())
        assertTrue(afterRevocation.related.isEmpty())
    }

    @Test
    fun semanticTextAndRelatedGroupsDeduplicateAndRespectScope() {
        val query = planPhotoQuery("a dog on a beach", library, false)
        assertTrue(query.canRankContents)
        val grouped = groupPhotoResults(query, accessible, listOf(other, other), listOf(other, suggestion, inaccessible))
        assertEquals(listOf(other), grouped.rememberedText)
        assertEquals(listOf(suggestion), grouped.related)
        assertTrue(groupPhotoResults(PhotoQueryPlan(NamedSearch.Nearby(setOf(confirmed), setOf(other))), accessible).confirmed.isEmpty())
    }
}
