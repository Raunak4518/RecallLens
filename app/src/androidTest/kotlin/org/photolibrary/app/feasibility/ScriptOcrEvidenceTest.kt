package org.photolibrary.app.feasibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextBounds
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.index.mergeScriptEvidence

@RunWith(AndroidJUnit4::class)
class ScriptOcrEvidenceTest {
    @Test
    fun supplementalScriptAddsOnlyNewSupportedEvidence() {
        val bounds = TextBounds(5, 12, 90, 30)
        val latin = PhotoText(listOf(TextLine("Reference 00071", "en", bounds)))
        val extra = PhotoText(listOf(
            TextLine("Reference 7", "en", bounds),
            TextLine("विद्यालय", "hi", bounds),
            TextLine("विद्यालय", "hi", bounds),
        ))
        val result = mergeScriptEvidence(latin, extra, setOf(Character.UnicodeScript.DEVANAGARI))
        assertEquals(listOf("Reference 00071", "विद्यालय"), result.lines.map { it.text })
        assertEquals(bounds, result.lines[1].bounds)
        assertTrue(result.matches("विद्यालय"))
        assertTrue(result.matches("00071"))
        assertFalse(result.matches("7"))
    }

    @Test
    fun continuousScriptsSupportSubstringWithoutLooseningIdentifierMatch() {
        val result = PhotoText(listOf(
            TextLine("学校の図書館", "ja", null),
            TextLine("item 5007", "en", null),
        ))
        assertTrue(result.matches("図書館"))
        assertFalse(result.matches("007"))
    }
}
