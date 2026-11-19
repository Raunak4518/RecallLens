package org.photolibrary.app.feasibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.photolibrary.app.index.DocumentFieldRole
import org.photolibrary.app.index.TextBounds
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.index.readDocumentFields

class DocumentFieldEvidenceTest {
    @Test
    fun extractsTypedValuesFromInlineLabels() {
        val fields = readDocumentFields(listOf(
            line("Candidate Name: Asha Verma"),
            line("Roll Number: 001928"),
            line("Date of Birth: 03/08/2005"),
            line("Total Marks: 452 / 500"),
        ))

        assertEquals("asha verma", fields.single { it.role == DocumentFieldRole.HOLDER_NAME }.value)
        assertEquals("001928", fields.single { it.role == DocumentFieldRole.IDENTIFIER }.value)
        assertEquals("03 08 2005", fields.single { it.role == DocumentFieldRole.DATE_OF_BIRTH }.value)
        assertEquals("452 500", fields.single { it.role == DocumentFieldRole.SCORE }.value)
    }

    @Test
    fun linksASeparateValueOnlyWhenItsBoundsAreAdjacent() {
        val fields = readDocumentFields(listOf(
            TextLine("Candidate Name", "en", TextBounds(40, 100, 250, 128)),
            TextLine("Asha Verma", "en", TextBounds(44, 133, 220, 161)),
        ))

        assertEquals("asha verma", fields.single().value)
        assertEquals(listOf(0, 1), fields.single().lineIndexes)
    }

    @Test
    fun linksSameRowLabelAndValueWhenTheirBoxesAreAdjacent() {
        val fields = readDocumentFields(listOf(
            TextLine("Candidate Name", "en", TextBounds(40, 100, 230, 128)),
            TextLine("Asha Verma", "en", TextBounds(242, 100, 420, 128)),
        ))

        assertEquals("asha verma", fields.single().value)
        assertEquals(listOf(0, 1), fields.single().lineIndexes)
    }

    @Test
    fun refusesUnlocatedOrDistantValuesAndParentNames() {
        val unlocated = readDocumentFields(listOf(line("Name"), line("Asha Verma")))
        val distant = readDocumentFields(listOf(
            TextLine("Name", "en", TextBounds(40, 100, 120, 128)),
            TextLine("Asha Verma", "en", TextBounds(480, 420, 660, 448)),
        ))
        val parent = readDocumentFields(listOf(line("Name of Father: Asha Verma")))
        val noteHeading = readDocumentFields(listOf(line("Class 10 study notes")))

        assertTrue(unlocated.isEmpty())
        assertTrue(distant.isEmpty())
        assertTrue(parent.none { it.role == DocumentFieldRole.HOLDER_NAME })
        assertTrue(noteHeading.none { it.role == DocumentFieldRole.CLASS_LEVEL })
    }

    private fun line(value: String) = TextLine(value, "en", null)
}
