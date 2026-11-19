package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.library.documentPhotoQuery
import org.photolibrary.app.subjects.NamedLibrary

@RunWith(AndroidJUnit4::class)
class DocumentFieldQueryTest {
    @Test
    fun exactPanNumberRetrievesOnlyTheMatchingTypedDocument() {
        val target = photo("target")
        val lookalike = photo("lookalike")
        val query = checkNotNull(documentPhotoQuery("Find PAN card number ABCDE1234F", NamedLibrary()))
        val text = mapOf(
            target.uri to text("Permanent Account Number", "Income Tax Department", "PAN Number: ABCDE1234F", "Name: Asha Verma"),
            lookalike.uri to text("Permanent Account Number", "Income Tax Department", "PAN Number: FGHIJ9876K", "Name: Noor Patel"),
        )

        val result = query.search(listOf(target, lookalike), text, emptyMap())

        assertEquals(listOf(target.uri), result.documents)
        assertTrue(result.possibleDocuments.isEmpty())
    }

    @Test
    fun nameClueDoesNotReturnAnotherDocumentHolder() {
        val target = photo("target")
        val lookalike = photo("lookalike")
        val query = checkNotNull(documentPhotoQuery("PAN card with name Asha Verma", NamedLibrary()))
        val text = mapOf(
            target.uri to text("Permanent Account Number", "Income Tax Department", "PAN Number: ABCDE1234F", "Name: Asha Verma"),
            lookalike.uri to text("Permanent Account Number", "Income Tax Department", "PAN Number: FGHIJ9876K", "Name: Noor Patel"),
        )

        val result = query.search(listOf(target, lookalike), text, emptyMap())

        assertEquals(listOf(target.uri), result.documents)
        assertTrue(result.possibleDocuments.isEmpty())
    }

    @Test
    fun missingRequestedFieldRemainsPossibleInsteadOfBecomingExact() {
        val target = photo("target")
        val query = checkNotNull(documentPhotoQuery("PAN card number ABCDE1234F", NamedLibrary()))
        val text = mapOf(target.uri to text("Permanent Account Number", "Income Tax Department"))

        val result = query.search(listOf(target), text, emptyMap())

        assertTrue(result.documents.isEmpty())
        assertEquals(listOf(target.uri), result.possibleDocuments)
    }

    @Test
    fun everyRequestedFieldMustAgreeForAnExactResult() {
        val target = photo("target")
        val wrongNumber = photo("wrong-number")
        val missingNumber = photo("missing-number")
        val query = checkNotNull(documentPhotoQuery("PAN card with name Asha Verma and number ABCDE1234F", NamedLibrary()))
        val text = mapOf(
            target.uri to text("Permanent Account Number", "Income Tax Department", "PAN Number: ABCDE1234F", "Name: Asha Verma"),
            wrongNumber.uri to text("Permanent Account Number", "Income Tax Department", "PAN Number: FGHIJ9876K", "Name: Asha Verma"),
            missingNumber.uri to text("Permanent Account Number", "Income Tax Department", "Name: Asha Verma"),
        )

        val result = query.search(listOf(target, wrongNumber, missingNumber), text, emptyMap())

        assertEquals(listOf(target.uri), result.documents)
        assertEquals(listOf(missingNumber.uri), result.possibleDocuments)
    }

    private fun photo(name: String) = SelectedPhoto(Uri.parse("content://fixture/document/$name"), "$name.png", PhotoOrigin.GALLERY)

    private fun text(vararg lines: String) = PhotoText(lines.map { TextLine(it, "en", null) })
}
