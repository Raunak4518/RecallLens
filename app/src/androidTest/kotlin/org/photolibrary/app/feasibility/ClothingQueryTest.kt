package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.detection.GarmentColor
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.clothingQuery

@RunWith(AndroidJUnit4::class)
class ClothingQueryTest {
    @Test
    fun requiresConfirmedPersonAndPreservesOtherPredicates() {
        val one = Uri.parse("content://fixture/one")
        val two = Uri.parse("content://fixture/two")
        val library = NamedLibrary(
            listOf(NamedSubject(1, "Ronak", SubjectKind.PERSON, listOf("Me")),
                NamedSubject(2, "My car", SubjectKind.OBJECT, emptyList())),
            listOf(SubjectReference(1, one, "v1", null), SubjectReference(1, two, "v1", null),
                SubjectReference(2, two, "v1", null)),
        )
        val query = checkNotNull(clothingQuery("me wearing red with my car", library))
        assertEquals(1L, query.personId)
        assertEquals(GarmentColor.RED, query.color)
        assertEquals(NamedSearch.Matches(setOf(two)), query.named)
        assertNull(clothingQuery("me wearing red shirt", library))
        assertNull(clothingQuery("me wearing red beside my car", library))
        assertNull(clothingQuery("a stranger wearing red", library))
        assertNull(clothingQuery("my car wearing red", library))
        assertNull(clothingQuery("me wearing unknown", library))
    }
}
