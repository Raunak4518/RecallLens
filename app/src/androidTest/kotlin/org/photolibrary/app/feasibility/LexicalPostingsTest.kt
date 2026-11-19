package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.library.LexicalPostings
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SearchQuery
import org.photolibrary.app.library.SelectedPhoto

@RunWith(AndroidJUnit4::class)
class LexicalPostingsTest {
    private val one = photo("one", "welcome.jpg")
    private val two = photo("two", "5007.jpg")
    private val three = photo("three", "scan.jpg")
    private val local = mapOf(one.uri to evidence("North Gate 007"), two.uri to evidence("north", "gate 5007"))
    private val imported = mapOf(three.uri to evidence("学校の図書館"))

    @Test
    fun lineBoundariesAndBudgetOverflowPreserveExactAndRelatedResults() {
        for (budget in listOf(0, 2, 250_000)) {
            val index = LexicalPostings(budget)
            val photos = listOf(two, one, three)
            val result = index.search(SearchQuery.parse("north gate"), photos, local, imported)
            assertEquals(listOf(one), result.exact)
            assertEquals(listOf(two.uri), result.related)
            assertEquals(listOf(one), index.search(SearchQuery.parse("007"), photos, local, imported).exact)
            assertEquals(listOf(three), index.search(SearchQuery.parse("図書館"), photos, local, imported).exact)
        }
    }

    @Test
    fun scopeChangesAndRemovedEvidenceCannotPublishStaleHits() {
        val index = LexicalPostings()
        val query = SearchQuery.parse("north gate")
        assertEquals(listOf(one), index.search(query, listOf(one, two), local, imported).exact)
        assertTrue(index.search(query, listOf(two), local, imported).exact.isEmpty())
        val edited = local + (one.uri to evidence("south gate"))
        assertTrue(index.search(query, listOf(one, two), edited, emptyMap()).exact.isEmpty())
        assertTrue(index.search(query, listOf(one), emptyMap(), emptyMap()).related.isEmpty())
    }

    private fun photo(key: String, name: String) =
        SelectedPhoto(Uri.parse("content://fixture/$key"), name, PhotoOrigin.GALLERY)

    private fun evidence(vararg lines: String) = PhotoText(lines.map { TextLine(it, "und", null) })
}
