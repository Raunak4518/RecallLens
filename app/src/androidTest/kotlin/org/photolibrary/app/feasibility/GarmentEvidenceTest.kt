package org.photolibrary.app.feasibility

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.detection.GarmentColor
import org.photolibrary.app.detection.GarmentEvidenceMissing
import org.photolibrary.app.detection.GarmentMask
import org.photolibrary.app.detection.GarmentParser
import org.photolibrary.app.detection.GarmentOverlapPolicy
import org.photolibrary.app.detection.PersonGarmentEvidence
import org.photolibrary.app.detection.describePersonGarment
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.subjects.PhotoRegion

@RunWith(AndroidJUnit4::class)
class GarmentEvidenceTest {
    private val leftFace = PhotoRegion(.1f, .05f, .25f, .2f)
    private val rightFace = PhotoRegion(.7f, .05f, .85f, .2f)
    private val faces = listOf(leftFace, rightFace)
    private val people = listOf(PhotoRegion(0f, 0f, .45f, 1f), PhotoRegion(.55f, 0f, 1f, 1f))
    private val model = ModelRevision("mask-fixture", "validated-regions-v1", 10)

    @Test
    fun maskedGarmentPixelsStayAttachedToTheirPerson() {
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.RED)
            for (y in 60 until 140) {
                for (x in 110 until 200) image.setPixel(x, y, Color.BLUE)
            }
            val parser = maskParser()
            val left = describePersonGarment(image, leftFace, faces, people, parser) as PersonGarmentEvidence.Found
            val right = describePersonGarment(image, rightFace, faces, people, parser) as PersonGarmentEvidence.Found
            assertEquals(leftFace, left.face)
            assertEquals(rightFace, right.face)
            assertEquals(model, right.model)
            assertEquals(1f, left.colorFractions.getValue(GarmentColor.RED), .0001f)
            assertEquals(1f, right.colorFractions.getValue(GarmentColor.BLUE), .0001f)
            assertTrue(GarmentColor.RED !in right.colorFractions)
        } finally {
            image.recycle()
        }
    }

    @Test
    fun ambiguousOwnersAndOverlappingPeopleNeverProduceGarmentEvidence() {
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val parser = object : GarmentParser {
            override val model = this@GarmentEvidenceTest.model
            override fun upperClothes(image: Bitmap): GarmentMask = error("Parser must not run for ambiguous ownership")
            override fun close() = Unit
        }
        try {
            val overlap = listOf(people.first(), PhotoRegion(.35f, .3f, .8f, 1f))
            val result = describePersonGarment(image, leftFace, faces, overlap, parser) as PersonGarmentEvidence.Missing
            assertEquals(GarmentEvidenceMissing.OVERLAPPING_PEOPLE, result.reason)
            val twoFaces = listOf(leftFace, PhotoRegion(.27f, .05f, .4f, .2f))
            val sameOwner = describePersonGarment(image, leftFace, twoFaces, people, parser) as PersonGarmentEvidence.Missing
            assertEquals(GarmentEvidenceMissing.AMBIGUOUS_PERSON, sameOwner.reason)
            val missing = describePersonGarment(image, leftFace, faces, emptyList(), parser) as PersonGarmentEvidence.Missing
            assertEquals(GarmentEvidenceMissing.NO_PERSON, missing.reason)
        } finally {
            image.recycle()
        }
    }

    @Test
    fun experimentalOverlapPolicyExcludesNeighborsAndWithholdsEmptyOwnedMasks() {
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.RED)
            for (y in 0 until 100) {
                for (x in 35 until 100) image.setPixel(x, y, Color.BLUE)
            }
            val overlapped = listOf(people.first(), PhotoRegion(.35f, .3f, .8f, 1f))
            val result = describePersonGarment(image, leftFace, listOf(leftFace), overlapped,
                maskParser(), GarmentOverlapPolicy.EXCLUDE_OTHER_PEOPLE) as PersonGarmentEvidence.Found
            assertEquals(1f, result.colorFractions.getValue(GarmentColor.RED), .0001f)
            assertTrue(GarmentColor.BLUE !in result.colorFractions)
            val covered = listOf(people.first(), PhotoRegion(0f, .3f, .8f, 1f))
            val withheld = describePersonGarment(image, leftFace, listOf(leftFace), covered,
                maskParser(), GarmentOverlapPolicy.EXCLUDE_OTHER_PEOPLE) as PersonGarmentEvidence.Missing
            assertEquals(GarmentEvidenceMissing.OVERLAPPING_PEOPLE, withheld.reason)
        } finally {
            image.recycle()
        }
    }

    @Test
    fun colorRequiresEnoughPixelsFromActualGarmentMask() {
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            val empty = maskParser(empty = true)
            val result = describePersonGarment(image, leftFace, faces, people, empty) as PersonGarmentEvidence.Missing
            assertEquals(GarmentEvidenceMissing.NO_UPPER_CLOTHES, result.reason)
        } finally {
            image.recycle()
        }
    }

    private fun maskParser(empty: Boolean = false) = object : GarmentParser {
        override val model = this@GarmentEvidenceTest.model
        override fun upperClothes(image: Bitmap) = GarmentMask(10, 10,
            BooleanArray(100) { index -> !empty && index / 10 in 3..6 })
        override fun close() = Unit
    }
}
