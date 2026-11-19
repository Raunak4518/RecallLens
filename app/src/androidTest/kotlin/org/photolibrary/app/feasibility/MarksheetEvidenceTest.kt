package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.library.marksheetQuery
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.SelfIdentity
import org.photolibrary.app.subjects.SubjectKind

@RunWith(AndroidJUnit4::class)
class MarksheetEvidenceTest {
    private val owner = NamedSubject(1, "Asha Verma", SubjectKind.PERSON, listOf("Asha Kirti Verma"))
    private val named = NamedLibrary(subjects = listOf(owner), selfIdentity = SelfIdentity(owner.id))

    @Test
    fun classAndBoardTitlesUseTheSchoolLevelAndAnExamYear() {
        val classTen = text("Statement of", "Marks", "SSC Examination 2020", "Candidate's name", "Asha Kirti Verma")
        val classTwelve = text("Pass Certificate cum Statement of Marks", "Higher Secondary School Examination 2022",
            "Name of Candidate: Asha Verma")
        assertEquals(setOf(10), classTen.marksheetEvidence?.grades)
        assertEquals(setOf("2020"), classTen.marksheetEvidence?.examYears)
        assertEquals(setOf("asha kirti verma"), classTen.marksheetEvidence?.candidates)
        assertEquals(setOf(12), classTwelve.marksheetEvidence?.grades)
        for (prompt in listOf("my Class X statement of marks", "my 10th marksheet", "my standard 10 report card from 2020")) {
            val result = rank(prompt, classTwelve, classTen)
            assertEquals(listOf(photo(1).uri), result.documents)
            assertTrue(result.possibleDocuments.isEmpty())
        }
    }

    @Test
    fun absentOwnersStayPossibleAndContradictingOwnersAreExcluded() {
        val other = text("Mark Sheet", "Class X", "Student's name: Noor Patel", "Father's name: Asha Verma")
        val obscured = text("Mark Sheet", "Class X", "Candidate name", "Father's name: Asha Verma")
        val conflict = text("Mark Sheet", "Class X", "Class XII", "Candidate name: Asha Verma")
        val unrelated = text("Class 10 marksheets are available online", "Call our coaching centre")
        val collage = text("Mark Sheet", "Class X", "Student Name: Asha Verma", "Student Name: Noor Patel")
        val result = rank("my 10th marksheet", other, obscured, conflict, unrelated, collage)
        assertTrue(result.documents.isEmpty())
        assertEquals(listOf(photo(1).uri), result.possibleDocuments)
    }

    @Test
    fun knownDocumentLabelsKeepConfirmedSearchAndBirthYearIsNotTheExamYear() {
        val saved = NamedSubject(2, "My 10th marksheet", SubjectKind.DOCUMENT, emptyList())
        assertNull(marksheetQuery("My 10th marksheet", named.copy(subjects = listOf(owner, saved))))
        val older = text("Gradesheet", "Grade 10", "Exam Year 2021", "Candidate Name: Asha Verma", "Date of birth 2005")
        assertTrue(rank("my class 10 gradesheet from 2005", older).documents.isEmpty())
        assertEquals(listOf(photo(0).uri), rank("my class 10 gradesheet from 2021", older).documents)
        assertNull(marksheetQuery("Find me on stage holding a report card", named))
        assertNull(marksheetQuery("Noor Patel's 10th marksheet", named))
    }

    @Test
    fun conflictingLocalAndApprovedTextDoesNotClaimAnOwner() {
        val selected = photo(0)
        val query = checkNotNull(marksheetQuery("my 10th marksheet", named))
        val local = mapOf(selected.uri to text("Mark Sheet", "Class X", "Student Name: Noor Patel"))
        val cloud = mapOf(selected.uri to text("Mark Sheet", "Class X", "Student Name: Asha Verma"))
        assertTrue(query.search(listOf(selected), local, cloud).documents.isEmpty())
        assertEquals(listOf(selected.uri), query.search(listOf(selected), emptyMap(), cloud).documents)
    }

    private fun rank(prompt: String, vararg texts: PhotoText) = checkNotNull(marksheetQuery(prompt, named)).search(
        texts.indices.map(::photo), texts.mapIndexed { index, evidence -> photo(index).uri to evidence }.toMap(), emptyMap())

    private fun text(vararg lines: String) = PhotoText(lines.map { TextLine(it, "en", null) })

    private fun photo(index: Int) = SelectedPhoto(Uri.parse("content://fixture/marksheet/$index"), "$index.png", PhotoOrigin.GALLERY)
}
