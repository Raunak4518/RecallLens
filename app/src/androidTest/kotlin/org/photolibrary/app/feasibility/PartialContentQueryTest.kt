package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.groupPhotoResults
import org.photolibrary.app.subjects.planPhotoQuery

@RunWith(AndroidJUnit4::class)
class PartialContentQueryTest {
    private val joint = Uri.parse("content://fixture/both")
    private val person = Uri.parse("content://fixture/person")
    private val other = Uri.parse("content://fixture/lookalike")
    private val named = NamedLibrary(
        listOf(NamedSubject(1, "Me", SubjectKind.PERSON, listOf("Myself")),
            NamedSubject(2, "My car", SubjectKind.OBJECT, listOf("My BMW")),
            NamedSubject(3, "Surat", SubjectKind.PLACE, emptyList())),
        listOf(SubjectReference(1, joint, "one", null), SubjectReference(1, person, "one", null),
            SubjectReference(2, joint, "one", null), SubjectReference(3, other, "one", null)),
    )

    @Test
    fun unsupportedPersonalConjunctionRanksOnlyConfirmedEntitiesAsPartialResults() {
        val query = "my photo in red shirt in front of my BMW around Surat"
        val plan = planPhotoQuery(query, named, false)
        assertTrue(QueryRequirement.SPATIAL_RELATION in plan.requirements)
        assertTrue(plan.partialContents)
        assertEquals(setOf(joint), plan.relatedScope)
        assertEquals(setOf(other), plan.nearby?.anchors)
        assertTrue(QueryRequirement.LOCATION_VERIFICATION in plan.requirements)
        val groups = groupPhotoResults(plan, setOf(joint, person, other), textMatches = listOf(other),
            rankedContents = listOf(other, person, joint))
        assertTrue(groups.confirmed.isEmpty())
        assertTrue(groups.rememberedText.isEmpty())
        assertTrue(groups.related.isEmpty())
        val located = plan.copy(requirements = plan.requirements - QueryRequirement.LOCATION_VERIFICATION)
        assertEquals(listOf(joint), groupPhotoResults(located, setOf(joint, person, other), rankedContents = listOf(other, person, joint)).related)
        assertFalse(plan.canRankContents)
    }

    @Test
    fun namedModeNeverPromotesPartialSuggestionsAndRevokedPhotosStayOut() {
        val query = "me behind my car"
        val namedPlan = planPhotoQuery(query, named, true)
        assertFalse(namedPlan.partialContents)
        val strict = groupPhotoResults(namedPlan, setOf(joint, person, other), rankedContents = listOf(joint), namedOnly = true)
        assertTrue(strict.confirmed.isEmpty())
        assertTrue(strict.related.isEmpty())
        val plan = planPhotoQuery(query, named, false)
        assertTrue(groupPhotoResults(plan, setOf(person, other), rankedContents = listOf(joint, other)).related.isEmpty())
    }

    @Test
    fun alternativesAndNegationDoNotBecomeAnAffirmativeIntersection() {
        for (query in listOf("me or my car behind a wall", "me without my car", "not my car",
            "me instead of my car", "my photos apart from my BMW", "me rather than my car")) {
            val plan = planPhotoQuery(query, named, false)
            assertEquals(null, plan.relatedScope)
            assertTrue(QueryRequirement.LOGICAL_RELATION in plan.requirements)
        }
    }

    @Test
    fun genericSpatialAndExclusionQueriesAlsoKeepTheirLimitationsVisible() {
        for ((query, requirement) in listOf("a dog behind a car" to QueryRequirement.SPATIAL_RELATION,
            "dogs without cats" to QueryRequirement.LOGICAL_RELATION)) {
            val plan = planPhotoQuery(query, NamedLibrary(), false)
            assertTrue(requirement in plan.requirements)
            assertTrue(plan.partialContents)
            assertEquals(null, plan.relatedScope)
        }
        assertTrue(planPhotoQuery("a dog on a beach", NamedLibrary(), false).canRankContents)
    }

    @Test
    fun unknownSelfKeepsKnownObjectScopeWithoutClaimingIdentity() {
        val withoutSelf = named.copy(subjects = named.subjects.filter { it.id != 1L },
            references = named.references.filter { it.subjectId != 1L })
        val plan = planPhotoQuery("my photo behind my BMW", withoutSelf, false)
        assertTrue(QueryRequirement.SELF_IDENTITY in plan.requirements)
        assertEquals(setOf(joint), plan.relatedScope)
        val groups = groupPhotoResults(plan, setOf(joint, other), rankedContents = listOf(other, joint))
        assertEquals(listOf(joint), groups.related)
        assertTrue(groups.confirmed.isEmpty())
        assertTrue(groupPhotoResults(plan, setOf(joint), rankedContents = listOf(joint), namedOnly = true).related.isEmpty())
    }

    @Test
    fun knownSubjectWithNoReferencesDoesNotFallBackToUnrelatedPhotos() {
        val withoutCar = named.copy(references = named.references.filter { it.subjectId != 2L })
        val plan = planPhotoQuery("me behind my car", withoutCar, false)
        assertEquals(emptySet<Uri>(), plan.relatedScope)
        assertTrue(groupPhotoResults(plan, setOf(other), rankedContents = listOf(other)).related.isEmpty())
    }

    @Test
    fun unknownIdentityGetsAnExplicitRequirementRatherThanInventedConfirmation() {
        val plan = planPhotoQuery("my photos behind a red car", NamedLibrary(), false)
        assertTrue(QueryRequirement.SELF_IDENTITY in plan.requirements)
        assertEquals(null, plan.relatedScope)
        val groups = groupPhotoResults(plan, setOf(other), rankedContents = listOf(other))
        assertEquals(listOf(other), groups.related)
        assertTrue(groups.confirmed.isEmpty())
    }
}
