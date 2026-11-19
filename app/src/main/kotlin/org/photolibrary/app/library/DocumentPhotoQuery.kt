package org.photolibrary.app.library

import android.net.Uri
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

private data class DocumentEvidence(val heading: Boolean, val corroborated: Boolean, val owners: Set<String>)

private val holderLabels = listOf("name of holder", "holder name", "cardholder name", "name of the holder", "full name", "name")
private val unrelatedName = Regex("""^(?:of|on|and|s o|d o|w o|father|mother|parent|guardian|issuing|nominee)\b""")
private val requestFrame = Regex("""\b(?:please|show|me|my|find|search|for|pictures?|photos?|images?|of|the|a|an|cards?|scans?|copy|copies|i|want|need)\b""")
private val panNumber = Regex("""\b[a-z]{5}[0-9]{4}[a-z]\b""")
private val aadhaarGroups = Regex("""\b[0-9]{4} [0-9]{4} [0-9]{4}\b""")

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
    if (requestFrame.replace(words.replace(" $requestedType ", " "), "").isNotBlank()) return null
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
            if (ownerKnown && classified.any { it.corroborated }) matches += photo.uri else possible += photo.uri
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
    }.toSet()
    return DocumentEvidence(title || evidence, evidence, owners)
}
