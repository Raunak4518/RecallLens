package org.photolibrary.app.feasibility

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.diversifySearchVersions
import org.photolibrary.app.library.groupSearchVersions
import org.photolibrary.app.library.hiddenVersions
import org.photolibrary.app.library.preservesRequestedVersions
import org.photolibrary.app.library.rankPossibleCopies
import org.photolibrary.app.semantic.PhotoCopySignature
import org.photolibrary.app.semantic.PhotoEmbedding

@RunWith(AndroidJUnit4::class)
class PhotoCopiesTest {
    private val first = Uri.parse("content://fixture/first")
    private val copy = Uri.parse("content://fixture/resized")
    private val other = Uri.parse("content://fixture/other")
    private val vector = PhotoEmbedding.normalized(FloatArray(512) { if (it == 0) 1f else 0f })

    @Test
    fun digestEnvelopeKeepsExistingVectorsReadableAndSeparatesVersions() = runBlocking {
        val representative = measured(18, false)
        val resized = PhotoEmbedding.decode(measured(36, false).encodeForStorage())
        val different = measured(18, true)
        assertNull(PhotoEmbedding.decode(vector.encode()).copySignature)
        assertEquals(1f, representative.sceneSimilarity(resized), .01f)
        val indexed = mapOf(first to representative, copy to resized, other to different)
        assertEquals(listOf(copy), rankPossibleCopies(representative, indexed - first, 10))
        val groups = groupSearchVersions(listOf(first, other, copy), indexed)
        assertEquals(mapOf(first to listOf(copy)), groups)
        val shortlist = diversifySearchVersions(listOf(first, copy, other), indexed)
        assertEquals(listOf(first, other, copy), shortlist.ranked)
        assertEquals(groups, shortlist.families)
        assertEquals(setOf(copy), hiddenVersions(groups, listOf(first, copy)))
        assertTrue(hiddenVersions(groups, listOf(copy)).isEmpty())
        assertTrue(preservesRequestedVersions("original unedited copy"))
    }

    private fun measured(side: Int, reversed: Boolean): PhotoEmbedding {
        val image = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        try {
            val colors = IntArray(side * side) { index ->
                val x = index % side
                val y = index / side
                val part = if (reversed) side - 1 - x else x
                Color.rgb(part * 255 / (side - 1), y * 255 / (side - 1), if (reversed) 180 else 35)
            }
            image.setPixels(colors, 0, side, 0, 0, side, side)
            return vector.withCopySignature(PhotoCopySignature.measure(image))
        } finally {
            image.recycle()
        }
    }
}
