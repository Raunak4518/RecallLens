package org.photolibrary.app.library

import android.net.Uri
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.index.usesContinuousPhotoScript
import java.text.Normalizer
import java.util.Locale

internal class LiteralFilter private constructor(
    private val required: List<Regex>,
    private val excluded: List<Regex>,
    val onlyLiterals: Boolean,
) {
    fun accepts(photo: SelectedPhoto, local: PhotoText?, imported: PhotoText?): Boolean {
        val lines = sequenceOf(photo.name.orEmpty()) + sequenceOf(local, imported)
            .filterNotNull().flatMap { it.lines.asSequence().map { line -> line.text } }
        val sources = lines.map(::literalForm).toList()
        return required.all { phrase -> sources.any(phrase::containsMatchIn) } &&
            excluded.none { phrase -> sources.any(phrase::containsMatchIn) }
    }

    companion object {
        private val quoted = Regex("""(?:(")([^"]+)"|(“)([^”]+)”)""")
        private val negation = Regex("""\b(?:not|without|excluding|except)\s*$""", RegexOption.IGNORE_CASE)
        private val alternatives = Regex("""\b(?:or|neither|nor)\b""", RegexOption.IGNORE_CASE)
        private val literalFrame = Regex("""\b(?:please|show|find|search|me|all|the|photos?|pictures?|images?|containing|reading|saying|with|text|words?|and|for|of)\b""", RegexOption.IGNORE_CASE)
        fun unquoted(query: String): String = quoted.replace(query) { " ".repeat(it.value.length) }

        fun parse(query: String): LiteralFilter? {
            val spans = quoted.findAll(query).toList()
            if (spans.isEmpty()) return null
            val outside = quoted.replace(query, " ")
            if (outside.any { it in "\"“”" } || alternatives.containsMatchIn(outside)) return null
            val required = mutableListOf<String>()
            val excluded = mutableListOf<String>()
            for (span in spans) {
                val value = literalForm(span.groupValues[2].ifEmpty { span.groupValues[4] })
                if (value.isBlank()) return null
                if (negation.containsMatchIn(query.substring(0, span.range.first))) excluded += value else required += value
            }
            val pure = required.isNotEmpty() && excluded.isEmpty() && literalFrame.replace(outside, "").isBlank()
            fun matcher(value: String) = Regex("""(?<![\p{L}\p{M}\p{N}])""" + Regex.escape(value) + """(?![\p{L}\p{M}\p{N}])""")
            return LiteralFilter(required.map(::matcher), excluded.map(::matcher), pure)
        }

        private val spacing = Regex("""\s+""")
        private fun literalForm(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT).replace(spacing, " ")
    }
}

internal class LexicalPostings(private val membershipLimit: Int = 250_000) {
    private data class Document(val local: PhotoText?, val imported: PhotoText?, val tokens: Set<String>?)
    private val documents = mutableMapOf<Uri, Document>()
    private val tokens = mutableMapOf<String, MutableSet<Uri>>()
    private val overflow = mutableSetOf<Uri>()
    private var memberships = 0
    private var lastLocal: Map<Uri, PhotoText>? = null
    private var lastImported: Map<Uri, PhotoText>? = null

    init {
        require(membershipLimit >= 0)
    }

    @Synchronized
    fun search(
        query: SearchQuery, photos: List<SelectedPhoto>,
        local: Map<Uri, PhotoText>, imported: Map<Uri, PhotoText>,
    ): LexicalCandidates {
        if (query.original.isEmpty()) return LexicalCandidates(photos, emptyList())
        synchronize(local, imported)
        val scope = photos.mapTo(hashSetOf()) { it.uri }
        val exact = query.normalized.flatMapTo(hashSetOf()) { textHits(it, scope) }
        val rankedTerms = query.terms.map { textHits(it, scope) }
        val related = mutableListOf<Uri>()
        val selected = photos.filter { photo ->
            val name = photo.name.orEmpty()
            val normalizedName = normalizePhotoText(name)
            val filename = query.literalVariants.any { term ->
                if (term.all(Char::isDigit)) " ${normalizePhotoText(term)} " in " $normalizedName "
                else name.contains(term, ignoreCase = true)
            }
            val direct = filename || photo.uri in exact
            if (!direct && rankedTerms.isNotEmpty() &&
                query.terms.indices.all { i -> photo.uri in rankedTerms[i] || " ${query.terms[i]} " in " $normalizedName " }) {
                related.add(photo.uri)
            }
            direct
        }
        return LexicalCandidates(selected, related)
    }

    private fun textHits(phrase: String, scope: Set<Uri>): Set<Uri> {
        val results = linkedSetOf<Uri>()
        fun verify(uri: Uri) {
            if (uri !in scope) return
            val document = documents[uri] ?: return
            if (document.local?.matches(phrase) == true || document.imported?.matches(phrase) == true) results.add(uri)
        }
        if (usesContinuousPhotoScript(phrase)) {
            documents.keys.forEach(::verify)
        } else {
            val rarest = phrase.split(' ').minByOrNull { tokens[it]?.size ?: 0 } ?: return results
            tokens[rarest].orEmpty().forEach(::verify)
            overflow.forEach(::verify)
        }
        return results
    }

    private fun synchronize(local: Map<Uri, PhotoText>, imported: Map<Uri, PhotoText>) {
        if (local == lastLocal && imported == lastImported) return
        val current = local.keys + imported.keys
        val changed = documents.keys.filter { uri ->
            val document = documents.getValue(uri)
            uri !in current || document.local !== local[uri] || document.imported !== imported[uri]
        }
        for (uri in changed) {
            val previous = documents.remove(uri)
            overflow.remove(uri)
            for (token in previous?.tokens.orEmpty()) {
                val posting = tokens.getValue(token)
                posting.remove(uri)
                if (posting.isEmpty()) tokens.remove(token)
                memberships--
            }
        }
        for (uri in current) {
            if (uri in documents) continue
            val native = local[uri]
            val legacy = imported[uri]
            val words = sequenceOf(native, legacy).filterNotNull().flatMap { it.lines.asSequence() }
                .flatMap { normalizePhotoText(it.text).splitToSequence(' ') }.filter(String::isNotEmpty)
                .distinct().take(513).toSet()
            val fits = words.size <= 512 && words.size <= membershipLimit - memberships
            documents[uri] = Document(native, legacy, words.takeIf { fits })
            if (fits) {
                for (word in words) tokens.getOrPut(word) { hashSetOf() }.add(uri)
                memberships += words.size
            } else overflow.add(uri)
        }
        lastLocal = local
        lastImported = imported
    }
}
