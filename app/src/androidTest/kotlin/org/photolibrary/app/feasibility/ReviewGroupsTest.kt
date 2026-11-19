package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.detection.DetectedRegion
import org.photolibrary.app.identity.IdentityVector
import org.photolibrary.app.library.NameSuggestion
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.library.groupReviewRegions
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind

@RunWith(AndroidJUnit4::class)
class ReviewGroupsTest {
    @Test
    fun groupsSimilarFacesAndKeepsUnrelatedFacesSeparate() = runBlocking {
        val candidates = listOf(
            suggestion("face-1", SubjectKind.PERSON, "person", vector(1f, 0f)),
            suggestion("face-2", SubjectKind.PERSON, "person", vector(1f, 0f)),
            suggestion("face-3", SubjectKind.PERSON, "person", vector(0f, 1f)),
        )

        val groups = groupReviewRegions(candidates)

        assertEquals(listOf(2, 1), groups.map { it.size })
        assertEquals(setOf("face-1", "face-2"), groups.first().map { it.photo.name }.toSet())
    }

    @Test
    fun usesCompleteLinkSoSimilarityChainsDoNotMergeDifferentEnds() = runBlocking {
        val half = kotlin.math.sqrt(.5f)
        val candidates = listOf(
            suggestion("a", SubjectKind.PERSON, "person", vector(1f, 0f)),
            suggestion("b", SubjectKind.PERSON, "person", vector(half, half)),
            suggestion("c", SubjectKind.PERSON, "person", vector(0f, 1f)),
        )

        val groups = groupReviewRegions(candidates)

        assertEquals(listOf(2, 1), groups.map { it.size })
        assertEquals(setOf("a", "b"), groups.first().map { it.photo.name }.toSet())
    }

    @Test
    fun leavesAmbiguousAndSamePhotoRegionsAsSeparateGroups() = runBlocking {
        val diagonal = kotlin.math.sqrt(.5f)
        val ambiguous = listOf(
            suggestion("a", SubjectKind.PERSON, "person", vector(1f, 0f)),
            suggestion("b", SubjectKind.PERSON, "person", vector(0f, 1f)),
            suggestion("c", SubjectKind.PERSON, "person", vector(diagonal, diagonal)),
        )
        val samePhoto = listOf(
            suggestion("same", SubjectKind.PERSON, "person", vector(1f, 0f), left = .1f),
            suggestion("same", SubjectKind.PERSON, "person", vector(1f, 0f), left = .5f),
        )

        assertEquals(listOf(1, 1, 1), groupReviewRegions(ambiguous).map { it.size })
        assertEquals(listOf(1, 1), groupReviewRegions(samePhoto).map { it.size })
    }

    @Test
    fun separatesCategoriesAndCapsAReviewGroupAtTwelvePhotos() = runBlocking {
        val differentKinds = listOf(
            suggestion("person", SubjectKind.PERSON, "person", vector(1f, 0f)),
            suggestion("object", SubjectKind.OBJECT, "car", vector(1f, 0f)),
            suggestion("other-object", SubjectKind.OBJECT, "chair", vector(1f, 0f)),
        )
        val repeated = (1..13).map { index ->
            suggestion("repeat-$index", SubjectKind.PERSON, "person", vector(1f, 0f))
        }

        assertEquals(listOf(1, 1, 1), groupReviewRegions(differentKinds).map { it.size })
        assertEquals(listOf(12, 1), groupReviewRegions(repeated).map { it.size })
    }

    private fun suggestion(
        id: String,
        kind: SubjectKind,
        label: String,
        descriptor: IdentityVector,
        left: Float = .1f,
    ): NameSuggestion {
        val photo = SelectedPhoto(Uri.parse("content://group-fixture/$id"), id, PhotoOrigin.GALLERY, "revision-$id")
        return NameSuggestion(
            photo,
            "revision-$id",
            DetectedRegion(label, kind, PhotoRegion(left, .1f, left + .3f, .4f), .99f),
            descriptor,
        )
    }

    private fun vector(x: Float, y: Float): IdentityVector {
        val values = FloatArray(128)
        values[0] = x
        values[1] = y
        return IdentityVector(values, null)
    }
}
