package org.photolibrary.app.index

internal enum class DocumentFieldRole {
    HOLDER_NAME,
    IDENTIFIER,
    DATE_OF_BIRTH,
    ISSUE_DATE,
    EXPIRY_DATE,
    INSTITUTION,
    CLASS_LEVEL,
    EXAM_YEAR,
    SCORE,
    AMOUNT,
}

internal data class DocumentFieldValue(
    val role: DocumentFieldRole,
    val label: String,
    val value: String,
    val lineIndexes: List<Int>,
)

private data class FieldLabel(val role: DocumentFieldRole, val value: String)

private val fieldLabels = listOf(
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "name of the candidate"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "name of candidate"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "name of the student"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "name of student"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "name of the holder"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "name of holder"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "candidate name"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "student name"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "cardholder name"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "holder name"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "full name"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "customer name"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "recipient name"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "billed to"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "bill to"),
    FieldLabel(DocumentFieldRole.DATE_OF_BIRTH, "date of birth"),
    FieldLabel(DocumentFieldRole.DATE_OF_BIRTH, "birth date"),
    FieldLabel(DocumentFieldRole.ISSUE_DATE, "date of issue"),
    FieldLabel(DocumentFieldRole.ISSUE_DATE, "issue date"),
    FieldLabel(DocumentFieldRole.EXPIRY_DATE, "date of expiry"),
    FieldLabel(DocumentFieldRole.EXPIRY_DATE, "expiry date"),
    FieldLabel(DocumentFieldRole.EXPIRY_DATE, "valid until"),
    FieldLabel(DocumentFieldRole.INSTITUTION, "name of institution"),
    FieldLabel(DocumentFieldRole.INSTITUTION, "issuing authority"),
    FieldLabel(DocumentFieldRole.INSTITUTION, "issued by"),
    FieldLabel(DocumentFieldRole.INSTITUTION, "school name"),
    FieldLabel(DocumentFieldRole.INSTITUTION, "school"),
    FieldLabel(DocumentFieldRole.INSTITUTION, "university"),
    FieldLabel(DocumentFieldRole.INSTITUTION, "board"),
    FieldLabel(DocumentFieldRole.INSTITUTION, "institution name"),
    FieldLabel(DocumentFieldRole.CLASS_LEVEL, "class"),
    FieldLabel(DocumentFieldRole.CLASS_LEVEL, "standard"),
    FieldLabel(DocumentFieldRole.CLASS_LEVEL, "grade"),
    FieldLabel(DocumentFieldRole.EXAM_YEAR, "exam year"),
    FieldLabel(DocumentFieldRole.EXAM_YEAR, "examination year"),
    FieldLabel(DocumentFieldRole.EXAM_YEAR, "academic year"),
    FieldLabel(DocumentFieldRole.SCORE, "total marks"),
    FieldLabel(DocumentFieldRole.SCORE, "marks obtained"),
    FieldLabel(DocumentFieldRole.SCORE, "marks secured"),
    FieldLabel(DocumentFieldRole.SCORE, "total score"),
    FieldLabel(DocumentFieldRole.AMOUNT, "amount due"),
    FieldLabel(DocumentFieldRole.AMOUNT, "total amount"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "permanent account number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "aadhaar number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "aadhar number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "passport number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "driving licence number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "driving license number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "registration number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "roll number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "invoice number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "receipt number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "document number"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "passport no"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "aadhaar no"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "aadhar no"),
    FieldLabel(DocumentFieldRole.IDENTIFIER, "pan number"),
    FieldLabel(DocumentFieldRole.HOLDER_NAME, "name"),
).sortedByDescending { it.value.length }

internal fun readDocumentFields(lines: List<TextLine>): List<DocumentFieldValue> {
    val normalized = lines.map { normalizePhotoText(it.text) }
    val fields = mutableListOf<DocumentFieldValue>()
    for (index in lines.indices) {
        val line = normalized[index]
        val label = fieldLabels.firstOrNull { line == it.value || line.startsWith("${it.value} ") } ?: continue
        val inlineValue = line.removePrefix(label.value).trim()
        if (inlineValue.isNotEmpty()) {
            if (isPlausibleFieldValue(label.role, inlineValue)) {
                fields += DocumentFieldValue(label.role, label.value, inlineValue, listOf(index))
            }
            continue
        }
        val nextIndex = (index + 1 until lines.size).firstOrNull { normalized[it].isNotBlank() } ?: continue
        if (!canLinkAdjacentField(lines[index].bounds, lines[nextIndex].bounds)) continue
        val value = normalized[nextIndex]
        if (!isPlausibleFieldValue(label.role, value)) continue
        fields += DocumentFieldValue(label.role, label.value, value, listOf(index, nextIndex))
    }
    return fields.distinctBy { Triple(it.role, it.value, it.lineIndexes) }
}

private fun canLinkAdjacentField(label: TextBounds?, value: TextBounds?): Boolean {
    if (label == null || value == null) return false
    val labelHeight = (label.bottom - label.top).coerceAtLeast(1)
    val valueHeight = (value.bottom - value.top).coerceAtLeast(1)
    val maxHeight = maxOf(labelHeight, valueHeight)
    val horizontalGap = maxOf(0, maxOf(label.left - value.right, value.left - label.right))
    val centerDistance = kotlin.math.abs((label.top + label.bottom) / 2 - (value.top + value.bottom) / 2)
    if (centerDistance <= maxHeight / 2) return horizontalGap <= maxHeight * 3
    val verticalGap = value.top - label.bottom
    if (verticalGap < -labelHeight / 2 || verticalGap > maxHeight * 2) return false
    return horizontalGap <= maxHeight * 2
}

private fun isPlausibleFieldValue(role: DocumentFieldRole, value: String): Boolean {
    if (value.isBlank() || value.length > 96) return false
    return when (role) {
        DocumentFieldRole.HOLDER_NAME -> value.any(Char::isLetter) && value.all { it.isLetter() || it.isWhitespace() || it == '.' } &&
            !unrelatedNameValue.containsMatchIn(value)
        DocumentFieldRole.IDENTIFIER -> value.any(Char::isLetterOrDigit)
        DocumentFieldRole.DATE_OF_BIRTH, DocumentFieldRole.ISSUE_DATE, DocumentFieldRole.EXPIRY_DATE,
        DocumentFieldRole.EXAM_YEAR -> value.any(Char::isDigit)
        DocumentFieldRole.CLASS_LEVEL -> classLevelValue.matches(value)
        DocumentFieldRole.INSTITUTION -> value.any(Char::isLetter)
        DocumentFieldRole.SCORE, DocumentFieldRole.AMOUNT -> value.any(Char::isDigit)
    }
}

private val unrelatedNameValue = Regex("""^(?:of|on|and|s o|d o|w o|father|mother|parent|guardian|issuing|nominee)\b""")
private val classLevelValue = Regex("""^(?:[1-9]|1[0-2]|i|ii|iii|iv|v|vi|vii|viii|ix|x|xi|xii)(?:st|nd|rd|th)?$""")
