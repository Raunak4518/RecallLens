package org.photolibrary.app.index

private const val title = "mark ?sheets?|marks (?:statements?|cards?)|statement of (?:marks|grades)|grade ?sheets?|report cards?|statement of subject ?wise performance"
private const val level = "1[012]|[1-9]|xii|xi|x|ix|viii|vii|vi|v|iv|iii|ii|i"
internal val marksheetTitleWords = Regex("\\b(?:$title)\\b")
internal val marksheetYear = Regex("\\b(?:19|20)[0-9]{2}\\b")
private val documentTitle = Regex("^(?:(?:pass|passing) certificate cum )?(?:$title)(?: cum (?:passing )?certificate(?: of performance)?)?(?: (?:19|20)[0-9]{2})?$")
private val classPrefix = Regex("\\b(?:class|grade|standard|std) ($level)(?:st|nd|rd|th)?\\b")
private val classSuffix = Regex("\\b($level)(?:st|nd|rd|th)? (?:class|grade|standard|std)\\b")
private val lowerBoard = Regex("\\b(?:secondary school examination|secondary school certificate examination|icse examination|ssc examination)\\b")
private val upperBoard = Regex("\\b(?:senior school certificate examination|(?:senior|higher) secondary (?:school )?(?:certificate )?examination|isc examination|hsc examination)\\b")
private val candidate = Regex("^(?:(?:candidate|student)(?: s)? name|name of (?:the )?(?:candidate|student))(?: (.*))?$")
private val nextField = Regex("^(?:father|mother|parent|guardian|school|roll|registration|date|exam|class|grade|subject|result|total|section)\\b")
private val nextInlineField = Regex("\\b(?:roll (?:no|number)|date of birth|father s name|mother s name|school name)\\b")
private val documentField = Regex("^(?:roll (?:no|number)|registration number|total marks|marks (?:obtained|secured)|subject (?:code|name)|date of birth)\\b")
private val romanNumbers = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii")

internal data class MarksheetEvidence(val grades: Set<Int>, val examYears: Set<String>,
    val candidates: Set<String>, val hasResultFields: Boolean)

internal fun readMarksheetEvidence(lines: List<TextLine>): MarksheetEvidence? {
    val header = lines.asSequence().take(24).map { normalizePhotoText(it.text) }.toList()
    val heading = header.find(::isMarksheetHeading) ?: header.zipWithNext { a, b -> "$a $b" }.find(::isMarksheetHeading)
        ?: return null
    val classFields = header.filter { it.startsWith("class ") || it.startsWith("standard ") || it.startsWith("std ") }
    val grades = (classFields.flatMap(::explicitMarksheetGrades) + header.mapNotNull(::marksheetBoardGrade)).toSet()
        .ifEmpty { header.filter { it.startsWith("grade ") || it == heading }.flatMap(::explicitMarksheetGrades).toSet() }
    val candidates = header.mapIndexedNotNull { index, line ->
        val label = candidate.matchEntire(line) ?: return@mapIndexedNotNull null
        label.groupValues[1].ifBlank { header.getOrNull(index + 1).orEmpty() }
            .split(nextInlineField, limit = 2).first().trim()
            .takeIf { it.isNotEmpty() && it.all { c -> c.isLetter() || c.isWhitespace() } && !nextField.containsMatchIn(it) }
    }.toSet()
    val years = header.asSequence().filter { it == heading || it.contains("examination") || it.startsWith("exam year ") ||
        it.startsWith("academic year ") }.flatMap { marksheetYear.findAll(it).map(MatchResult::value) }.toSet()
    return MarksheetEvidence(grades, years, candidates, candidates.isNotEmpty() || header.any(documentField::containsMatchIn))
}

private fun isMarksheetHeading(line: String): Boolean = documentTitle.matches(line) ||
    (line.startsWith("class ") && documentTitle.matches(classPrefix.replaceFirst(line, "").trim()))

internal fun removeMarksheetLevel(line: String): String = listOf(classPrefix, classSuffix, upperBoard, lowerBoard)
    .fold(line) { text, pattern -> pattern.replace(text, " ") }

internal fun explicitMarksheetGrades(line: String): Set<Int> = (classPrefix.findAll(line) + classSuffix.findAll(line))
    .map { it.groupValues[1] }.map { it.toIntOrNull() ?: romanNumbers.indexOf(it) + 1 }.toSet()

internal fun marksheetBoardGrade(line: String): Int? = when {
    upperBoard.containsMatchIn(line) -> 12
    lowerBoard.containsMatchIn(line) -> 10
    else -> null
}
