package org.photolibrary.app.index

import java.text.Normalizer
import java.util.Locale

internal data class TextBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal class TextLine(val text: String, val language: String, val bounds: TextBounds?)

internal class PhotoText(val lines: List<TextLine>, val complete: Boolean = true) {
    private val searchableLines by lazy { lines.map { " ${normalizePhotoText(it.text)} " } }
    val marksheetEvidence by lazy { readMarksheetEvidence(lines) }

    fun matches(normalizedQuery: String): Boolean {
        if (normalizedQuery.isEmpty()) return false
        val continuous = usesContinuousPhotoScript(normalizedQuery)
        return searchableLines.any { line ->
            if (continuous) normalizedQuery in line else " $normalizedQuery " in line
        }
    }
}

internal fun usesContinuousPhotoScript(query: String): Boolean =
    query.isNotEmpty() && query.codePoints().allMatch { Character.UnicodeScript.of(it) in continuousScripts }

private val continuousScripts = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA)

private val textSeparator = Regex("[^\\p{L}\\p{N}\\p{M}]+")

internal fun normalizePhotoText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(textSeparator, " ").trim()
