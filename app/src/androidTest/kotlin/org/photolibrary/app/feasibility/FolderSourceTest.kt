package org.photolibrary.app.feasibility

import android.database.MatrixCursor
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.FolderSource

@RunWith(AndroidJUnit4::class)
class FolderSourceTest {
    @Test
    fun partialNestedListingIsUnavailableAndCompleteEmptyFolderIsEmpty() = runBlocking {
        val tree = DocumentsContract.buildTreeDocumentUri("fixture.documents", "removable:album")
        var failure = "loading"
        val source = FolderSource { uri, projection ->
            val nested = uri.pathSegments.contains("removable:album/nested")
            if (nested && failure == "null") return@FolderSource null
            if (nested && failure == "denied") throw SecurityException("Fixture access revoked")
            val cursor = object : MatrixCursor(projection) {
                override fun getExtras() = Bundle().apply {
                    if (nested && failure == "loading") putBoolean(DocumentsContract.EXTRA_LOADING, true)
                    if (nested && failure == "error") putString(DocumentsContract.EXTRA_ERROR, "Fixture offline")
                }
            }
            if (!nested && failure != "empty") {
                cursor.addRow(arrayOf("removable:album/a.jpg", "a.jpg", "image/jpeg", 100L, 200L))
                cursor.addRow(arrayOf("removable:album/nested", "nested", DocumentsContract.Document.MIME_TYPE_DIR, null, null))
            }
            cursor
        }
        for (unavailable in listOf("loading", "null", "error")) {
            failure = unavailable
            assertNull(source.photos(tree))
        }
        failure = "available"
        assertEquals(listOf("a.jpg"), source.photos(tree)?.map { it.name })
        failure = "empty"
        assertEquals(emptyList<Any>(), source.photos(tree))
        failure = "denied"
        assertEquals(emptyList<Any>(), source.photos(tree))
    }
}
