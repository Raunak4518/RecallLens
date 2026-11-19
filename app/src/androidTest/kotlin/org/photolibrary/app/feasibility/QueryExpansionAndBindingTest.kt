package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.ExpansionKind
import org.photolibrary.app.library.SearchMode
import org.photolibrary.app.library.SearchQuery
import org.photolibrary.app.library.captureYearQuery
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.groupPhotoResults
import org.photolibrary.app.subjects.planPhotoQuery

@RunWith(AndroidJUnit4::class)
class QueryExpansionAndBindingTest {
    @Test
    fun trialsKeepOriginalAndNeverExpandLiteralLookup() {
        val typo = SearchQuery.parse("show me photos of a bicyle",
            enabledExpansions = setOf(ExpansionKind.TYPO))
        assertEquals(listOf("show me photos of a bicyle", "a bicyle", "a bicycle"), typo.visualVariants)
        assertFalse("a bicycle" in typo.literalVariants)
        assertEquals("a couch", SearchQuery.parse("a sofa",
            enabledExpansions = setOf(ExpansionKind.SYNONYM)).expansion?.phrase)
        assertEquals("kutaro", SearchQuery.parse("કૂતરો",
            enabledExpansions = setOf(ExpansionKind.TRANSLITERATION)).expansion?.phrase)
    }

    @Test
    fun trialsPreserveNamesPolarityAndQuotedIdentifiers() {
        val enabled = ExpansionKind.entries.toSet()
        listOf("without a bicyle", "my bicyle", "sofa AB-0007", "\"sofa\"", "sofa right of a dog").forEach {
            assertNull(SearchQuery.parse(it, enabledExpansions = enabled).expansion)
        }
        assertNull(SearchQuery.parse("sofa", protectedWords = setOf("sofa"),
            enabledExpansions = enabled).expansion)
    }

    @Test
    fun quotedYearStaysLiteralWhileOutsideYearFiltersCaptureDate() {
        val library = NamedLibrary()
        val onlyText = captureYearQuery("sign saying \"in 2024\"", library, SearchMode.CONTENT)
        assertNull(onlyText.filter)
        assertFalse(onlyText.unsupported)
        val mixed = captureYearQuery("sign saying \"before 2024\" after 2020", library, SearchMode.CONTENT)
        assertEquals("sign saying \"before 2024\"", mixed.phrase)
        assertEquals(2020, mixed.filter?.year)
    }

    @Test
    fun spatialRelationRequiresTwoDistinctConfirmedRegionsAndCorrectDirection() {
        val good = Uri.parse("content://fixture/good")
        val inverse = Uri.parse("content://fixture/inverse")
        val unknown = Uri.parse("content://fixture/no-region")
        val single = Uri.parse("content://fixture/one-entity")
        val left = PhotoRegion(.10f, .20f, .25f, .45f)
        val right = PhotoRegion(.50f, .22f, .75f, .50f)
        val library = NamedLibrary(
            listOf(NamedSubject(1, "Ari", SubjectKind.PERSON, emptyList()),
                NamedSubject(2, "Blue backpack", SubjectKind.OBJECT, emptyList())),
            listOf(SubjectReference(1, good, "1", left), SubjectReference(2, good, "1", right),
                SubjectReference(1, inverse, "1", right), SubjectReference(2, inverse, "1", left),
                SubjectReference(1, unknown, "1", left), SubjectReference(2, unknown, "1", null),
                SubjectReference(2, single, "1", right)),
        )
        val accessible = setOf(good, inverse, unknown, single)
        val plan = planPhotoQuery("show me photos of Ari to the left of blue backpack", library, false)
        assertTrue(QueryRequirement.SPATIAL_RELATION in plan.visibleRequirements)
        val grouped = groupPhotoResults(plan, accessible, rankedContents = accessible.toList())
        assertEquals(listOf(good), grouped.confirmed)
        assertEquals(listOf(unknown), grouped.related)
        assertEquals(NamedSearch.Matches(setOf(inverse)),
            planPhotoQuery("Ari right of blue backpack", library, true).named)
        assertEquals(NamedSearch.Matches(accessible),
            planPhotoQuery("Ari or blue backpack", library, true).named)
        assertTrue(groupPhotoResults(planPhotoQuery("Ari behind blue backpack", library, false),
            accessible, rankedContents = accessible.toList()).confirmed.isEmpty())
    }
}
