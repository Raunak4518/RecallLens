package org.photolibrary.app.library

import android.net.Uri
import org.photolibrary.app.index.DocumentFieldRole
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.PhotoResultGroups
import org.photolibrary.app.subjects.searchNamed

private enum class DocumentKind(val requests: Set<String>, val headings: Set<String>) {
    PAN(setOf("pan card", "permanent account number card"), setOf("permanent account number", "स्थायी लेखा सं")),
    AADHAAR(setOf("aadhaar", "aadhar", "आधार"), setOf("unique identification authority of india", "भारतीय विशिष्ट पहचान प्राधिकरण")),
    PASSPORT(setOf("passport", "पासपोर्ट"), setOf("passport", "type of passport", "passport no", "passport number")),
    LICENSE(setOf("driving licence", "driving license", "drivers license"), setOf("driving licence", "driving license")),
    INVOICE(setOf("invoice", "tax invoice"), setOf("tax invoice", "commercial invoice", "invoice no", "invoice number")),
    RECEIPT(setOf("receipt"), setOf("payment receipt", "cash receipt", "receipt no", "receipt number")),
}

private data class DocumentEvidence(
    val heading: Boolean,
    val corroborated: Boolean,
    val owners: Set<String>,
    val fields: Map<DocumentFieldRole, Set<String>>,
)

private data class DocumentFieldConstraint(val role: DocumentFieldRole, val value: String)

private enum class FieldConstraintMatch { MATCH, UNKNOWN, CONFLICT }

private val holderLabels = listOf("name of holder", "holder name", "cardholder name", "name of the holder", "full name", "name")
private val unrelatedName = Regex("""^(?:of|on|and|s o|d o|w o|father|mother|parent|guardian|issuing|nominee)\b""")
private val nonFieldRequestFrame = Regex("""\b(?:please|show|me|my|find|search|for|where|what|can|could|would|you|pictures?|photos?|images?|photographs?|documents?|the|a|an|cards?|scans?|copy|copies|i|want|need|with|whose|that|has|is|are|to|get|see|look|up)\b""")
private val panNumber = Regex("""\b[a-z]{5}[0-9]{4}[a-z]\b""")
private val aadhaarGroups = Regex("""\b[0-9]{4} [0-9]{4} [0-9]{4}\b""")
private val documentFieldCues = listOf(
    DocumentFieldRole.DATE_OF_BIRTH to listOf("date of birth", "birth date", "dob"),
    DocumentFieldRole.ISSUE_DATE to listOf("date of issue", "issue date"),
    DocumentFieldRole.EXPIRY_DATE to listOf("date of expiry", "expiry date", "valid until"),
    DocumentFieldRole.CLASS_LEVEL to listOf("class", "standard", "grade"),
    DocumentFieldRole.EXAM_YEAR to listOf("examination year", "academic year", "exam year"),
    DocumentFieldRole.HOLDER_NAME to listOf("name of candidate", "name of student", "candidate name", "student name", "cardholder name", "holder name", "full name", "name"),
    DocumentFieldRole.INSTITUTION to listOf("issuing authority", "issued by", "institution", "university", "school", "board"),
    DocumentFieldRole.IDENTIFIER to listOf("document number", "passport number", "aadhaar number", "aadhar number", "pan number", "card number", "identifier", "number", "no", "id"),
    DocumentFieldRole.SCORE to listOf("total marks", "marks obtained", "marks secured", "total score", "score"),
    DocumentFieldRole.AMOUNT to listOf("amount due", "total amount", "amount"),
).flatMap { (role, labels) -> labels.map { role to it } }.sortedByDescending { it.second.length }

internal fun interface DocumentPhotoQuery {
    fun search(photos: List<SelectedPhoto>, local: Map<Uri, PhotoText>, imported: Map<Uri, PhotoText>): PhotoResultGroups
}

internal fun documentPhotoQuery(prompt: String, library: NamedLibrary): DocumentPhotoQuery? {
    if ('"' in prompt || '“' in prompt || '”' in prompt) return null
    marksheetQuery(prompt, library)?.let { return DocumentPhotoQuery(it::search) }
    if (searchNamed(prompt, library, namedOnly = false) is NamedSearch.Matches) return null
    val words = " ${normalizePhotoText(prompt)} "
    val kind = DocumentKind.entries.singleOrNull { type -> type.requests.any { " $it " in words } } ?: return null
    val requestedType = kind.requests.filter { " $it " in words }.maxBy { it.length }
    val constraints = documentFieldConstraints(prompt, requestedType) ?: return null
    val personal = " my " in words
    val aliases = library.subjectFor(listOf("me"))?.let { listOf(it.name) + it.aliases }.orEmpty()
        .map(::normalizePhotoText).filter { it.isNotBlank() && it !in setOf("me", "self", "myself") }.toSet()
    return DocumentPhotoQuery { photos, local, imported ->
        val matches = mutableListOf<Uri>()
        val possible = mutableListOf<Uri>()
        for (photo in photos) {
            val readings = listOfNotNull(local[photo.uri], imported[photo.uri]).map { extractDocument(it, kind) }
            val classified = readings.filter { it.heading }
            if (classified.isEmpty()) continue
            val owners = classified.flatMap { it.owners }.toSet()
            if (personal && aliases.isNotEmpty() && owners.any { it !in aliases }) continue
            val ownerKnown = !personal || (owners.isNotEmpty() && owners.all { it in aliases })
            val fieldMatches = constraints.map { constraint -> classified.match(constraint) }
            if (fieldMatches.any { it == FieldConstraintMatch.CONFLICT }) continue
            val fieldsKnown = fieldMatches.all { it == FieldConstraintMatch.MATCH }
            if (ownerKnown && fieldsKnown && classified.any { it.corroborated }) matches += photo.uri else possible += photo.uri
        }
        PhotoResultGroups(documents = matches, possibleDocuments = possible.take(24))
    }
}

private fun extractDocument(text: PhotoText, kind: DocumentKind): DocumentEvidence {
    val lines = text.lines.map { normalizePhotoText(it.text) }.filter(String::isNotEmpty)
    val header = lines.take(16).joinToString(" ", prefix = " ", postfix = " ")
    val all = lines.joinToString(" ", prefix = " ", postfix = " ")
    val title = kind.headings.any { " $it " in header }
    val evidence = when (kind) {
        DocumentKind.PAN -> panNumber.containsMatchIn(all) && (" income tax department " in header || title)
        DocumentKind.AADHAAR -> aadhaarGroups.containsMatchIn(all) &&
            (title || listOf(" aadhaar ", " आधार ").any(header::contains))
        DocumentKind.PASSPORT -> title && " nationality " in all && (" surname " in all || " given name" in all)
        DocumentKind.LICENSE -> title && (" valid " in all || " validity " in all || " date of issue " in all)
        DocumentKind.INVOICE -> title && (" total " in all || " amount " in all)
        DocumentKind.RECEIPT -> title && (" paid " in all || " amount " in all || " total " in all)
    }
    val labels = if (kind == DocumentKind.INVOICE || kind == DocumentKind.RECEIPT) {
        listOf("billed to", "bill to", "customer name", "recipient name")
    } else holderLabels
    val owners = lines.mapNotNull { line ->
        val label = labels.firstOrNull { line.startsWith("$it ") } ?: return@mapNotNull null
        line.removePrefix("$label ").takeIf { it.isNotBlank() && !unrelatedName.containsMatchIn(it) && it.split(' ').size <= 8 && it.all { ch -> ch.isLetter() || ch == ' ' || Character.getType(ch) == Character.NON_SPACING_MARK.toInt() } }
    }.toSet() + text.documentFields.asSequence()
        .filter { it.role == DocumentFieldRole.HOLDER_NAME }
        .map { it.value }
        .filter { it.isNotBlank() && !unrelatedName.containsMatchIn(it) }
        .toSet()
    val fields = text.documentFields.groupBy({ it.role }, { normalizePhotoText(it.value) })
        .mapValues { (_, values) -> values.toSet() }
        .toMutableMap()
    if (owners.isNotEmpty()) fields[DocumentFieldRole.HOLDER_NAME] = fields[DocumentFieldRole.HOLDER_NAME].orEmpty() + owners
    return DocumentEvidence(title || evidence, evidence, owners, fields)
}

private fun documentFieldConstraints(prompt: String, requestedType: String): List<DocumentFieldConstraint>? {
    val normalized = normalizePhotoText(prompt)
    val clauses = normalized.split(Regex("\\s+and\\s+|[,;]"))
    val constraints = mutableListOf<DocumentFieldConstraint>()
    for (clause in clauses) {
        val withoutType = " ${clause.trim()} ".replace(" $requestedType ", " ")
        val residual = nonFieldRequestFrame.replace(withoutType, " ").trim()
        if (residual.isBlank()) continue
        val cue = documentFieldCues.firstOrNull { residual == it.second || residual.startsWith("${it.second} ") } ?: return null
        val rawValue = residual.removePrefix(cue.second).trim().removePrefix("is ").removePrefix("of ").trim()
        if (!isPlausibleQueryValue(cue.first, rawValue)) return null
        constraints += DocumentFieldConstraint(cue.first, normalizePhotoText(rawValue))
    }
    return constraints.distinct()
}

private fun isPlausibleQueryValue(role: DocumentFieldRole, value: String): Boolean {
    if (value.isBlank() || value.length > 96) return false
    return when (role) {
        DocumentFieldRole.HOLDER_NAME, DocumentFieldRole.INSTITUTION -> value.any(Char::isLetter)
        DocumentFieldRole.IDENTIFIER -> value.any(Char::isLetterOrDigit)
        DocumentFieldRole.DATE_OF_BIRTH, DocumentFieldRole.ISSUE_DATE, DocumentFieldRole.EXPIRY_DATE,
        DocumentFieldRole.EXAM_YEAR, DocumentFieldRole.SCORE, DocumentFieldRole.AMOUNT -> value.any(Char::isDigit)
        DocumentFieldRole.CLASS_LEVEL -> value.matches(Regex("""(?:[1-9]|1[0-2]|i|ii|iii|iv|v|vi|vii|viii|ix|x|xi|xii)(?:st|nd|rd|th)?"""))
    }
}

private fun List<DocumentEvidence>.match(constraint: DocumentFieldConstraint): FieldConstraintMatch {
    val expected = comparableFieldValue(constraint.role, constraint.value)
    val observed = flatMap { it.fields[constraint.role].orEmpty() }.map { comparableFieldValue(constraint.role, it) }.distinct()
    if (observed.isEmpty()) return FieldConstraintMatch.UNKNOWN
    if (observed.all { it == expected }) return FieldConstraintMatch.MATCH
    if (expected in observed) return FieldConstraintMatch.UNKNOWN
    return FieldConstraintMatch.CONFLICT
}

private fun comparableFieldValue(role: DocumentFieldRole, value: String): String {
    val normalized = normalizePhotoText(value)
    return if (role == DocumentFieldRole.IDENTIFIER) normalized.filter(Char::isLetterOrDigit) else normalized
}
