package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.planPhotoQuery

@RunWith(AndroidJUnit4::class)
class ComplexNearbyPlanTest {
    private val joint = Uri.parse("content://fixture/complex/joint")
    private val person = Uri.parse("content://fixture/complex/person")
    private val place = Uri.parse("content://fixture/complex/place")
    private val named = NamedLibrary(listOf(
        NamedSubject(1, "Ronak", SubjectKind.PERSON, listOf("Myself")),
        NamedSubject(2, "My Honda", SubjectKind.OBJECT, listOf("My car")),
        NamedSubject(3, "Surat", SubjectKind.PLACE, emptyList()),
    ), listOf(SubjectReference(1, joint, "one", null), SubjectReference(1, person, "one", null),
        SubjectReference(2, joint, "one", null), SubjectReference(3, place, "one", null)))

    @Test
    fun complexRequestRetainsConfirmedIntersectionAndRequiresActualLocationVerification() {
        val plan = planPhotoQuery("my photo wearing red in front of my car around Surat", named, false)
        assertEquals(NamedSearch.Nearby(setOf(joint), setOf(place)), plan.nearby)
        assertTrue(QueryRequirement.LOCATION_VERIFICATION in plan.requirements)
        assertTrue(QueryRequirement.SPATIAL_RELATION in plan.requirements)
        assertTrue(QueryRequirement.PERSON_CLOTHING_RELATION in plan.requirements)
        assertTrue(plan.partialContents)
    }

    @Test
    fun unknownPlacesAndLogicalAlternativesDoNotBecomeAffirmativeGpsFilters() {
        val unknown = planPhotoQuery("Ronak behind my car near an unknown city", named, false)
        assertNull(unknown.nearby)
        assertTrue(QueryRequirement.PLACE_REFERENCE in unknown.requirements)
        for (query in listOf("Ronak not near Surat", "Ronak near Surat or around Surat", "Ronak instead of my car near Surat")) {
            val plan = planPhotoQuery(query, named, false)
            assertNull(plan.nearby)
            assertTrue(QueryRequirement.LOGICAL_RELATION in plan.requirements)
        }
    }

    @Test
    fun supportedNamedNearbyRequestsKeepTheirExistingRouteAndUnsupportedNamedDoesNotRank() {
        val supported = planPhotoQuery("Ronak and my Honda near Surat", named, true)
        assertEquals(NamedSearch.Nearby(setOf(joint), setOf(place)), supported.named)
        assertNull(supported.nearby)
        assertTrue(supported.requirements.isEmpty())
        val unsupported = planPhotoQuery("Ronak behind my car near Surat", named, true)
        assertNull(unsupported.nearby)
        assertTrue(!unsupported.canRankSemantically)
    }
}
