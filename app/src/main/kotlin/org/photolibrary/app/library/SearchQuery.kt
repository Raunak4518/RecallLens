package org.photolibrary.app.library

import android.net.Uri
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.semantic.PhotoEmbedding
import java.util.PriorityQueue
import org.photolibrary.app.index.tracePhotoStage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SearchQuery private constructor(
    val original: String,
    val phrase: String,
    val terms: List<String>,
    val expansion: VisualQueryExpansion?,
) {
    val literalVariants = listOf(original, phrase).distinct()
    val visualVariants = (literalVariants + listOfNotNull(expansion?.phrase)).distinct()
    val normalized = literalVariants.map(::normalizePhotoText).filter(String::isNotEmpty).distinct()

    companion object {
        private val wrapper = Regex(
            """^(?:(?:please\s+)?(?:show\s+me|find|search\s+for)\s+(?:(?:all|the)\s+)?)?(?:photos?|pictures?|images?|pics)\s+(?:of|showing|containing)\s+""",
            RegexOption.IGNORE_CASE,
        )
        private val conjunctionGuard = setOf("not", "no", "without", "except", "excluding", "or", "nor", "but")
        private val stopWords = setOf("a", "an", "the", "of", "at", "in", "on", "by", "to", "and", "with")
        private val plainWords = Regex("""[\p{L}\p{M}\s]+""")
        fun parse(
            raw: String,
            rewrite: Boolean = true,
            protectedWords: Set<String> = emptySet(),
            enabledExpansions: Set<ExpansionKind> = emptySet(),
        ): SearchQuery {
            val original = raw.trim()
            val phrase = if (rewrite) original.replaceFirst(wrapper, "").ifBlank { original } else original
            val words = normalizePhotoText(phrase).split(' ').distinct()
            val terms = if (rewrite && plainWords.matches(phrase) && words.none(conjunctionGuard::contains)) {
                words.filter { it.length >= 2 && it !in stopWords }.takeIf { it.size in 2..8 }.orEmpty()
            } else emptyList()
            val expansion = if (rewrite) VisualQueryExpansion.select(phrase, protectedWords, enabledExpansions) else null
            return SearchQuery(original, phrase, terms, expansion)
        }
    }
}

internal data class LexicalCandidates(val exact: List<SelectedPhoto>, val related: List<Uri>)

internal fun fuseRelated(first: List<Uri>, second: List<Uri>, secondWeight: Double = 1.0): List<Uri> {
    if (second.isEmpty()) return first
    if (first.isEmpty()) return second
    val scores = linkedMapOf<Uri, Double>()
    for ((route, weight) in listOf(first to 1.0, second to secondWeight)) {
        route.forEachIndexed { rank, uri -> scores[uri] = scores.getOrDefault(uri, 0.0) + weight / (61 + rank) }
    }
    return scores.keys.sortedByDescending { scores.getValue(it) }
}

internal data class VisualCandidate(val uri: Uri, val score: Float)

internal suspend fun rankVisualVariants(
    queries: List<PhotoEmbedding>,
    gallery: Map<Uri, PhotoEmbedding>,
    limit: Int,
    eligible: (Uri) -> Boolean = { true },
): List<VisualCandidate> {
    if (limit <= 0 || queries.isEmpty()) return emptyList()
    val bestFirst = compareByDescending<VisualCandidate> { it.score }.thenBy { it.uri.toString() }
    val retained = PriorityQueue(limit, bestFirst.reversed())
    val job = currentCoroutineContext()
    return tracePhotoStage("photos.rank.queryVariants") {
        var visited = 0
        for ((uri, image) in gallery) {
            if (visited++ % 128 == 0) job.ensureActive()
            if (!eligible(uri)) continue
            val score = queries.maxOf { it.similarity(image) }
            if (retained.size == limit && score < retained.element().score) continue
            val candidate = VisualCandidate(uri, score)
            if (retained.size == limit) {
                if (bestFirst.compare(candidate, retained.element()) >= 0) continue
                retained.remove()
            }
            retained.add(candidate)
        }
        retained.sortedWith(bestFirst)
    }
}


internal val queryExpansionModes: Set<ExpansionKind> = org.photolibrary.app.BuildConfig.VISUAL_QUERY_EXPANSIONS
    .split(',').mapNotNull { key -> ExpansionKind.entries.firstOrNull { it.name.equals(key.trim(), ignoreCase = true) } }
    .toSet()
