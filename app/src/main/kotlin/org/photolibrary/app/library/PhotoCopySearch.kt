package org.photolibrary.app.library

import android.net.Uri
import java.util.PriorityQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.photolibrary.app.semantic.PhotoEmbedding
import org.photolibrary.app.subjects.subjectWords

internal enum class VisualSeedMode { SIMILAR, COPIES }

internal fun preservesRequestedVersions(query: String): Boolean =
    subjectWords(query).any { it in versionWords }

private val versionWords = setOf(
    "version", "versions", "copy", "copies", "duplicate", "duplicates", "original", "originals", "edit", "edited",
    "unedited", "crop", "cropped", "uncropped", "resized", "screenshot", "larger", "smaller",
)

internal suspend fun rankPossibleCopies(
    seed: PhotoEmbedding,
    gallery: Map<Uri, PhotoEmbedding>,
    limit: Int,
): List<Uri> {
    require(limit >= 0)
    if (limit == 0) return emptyList()
    val coroutine = currentCoroutineContext()
    val source = seed.copySignature ?: return emptyList()
    val order = compareBy<CopyHit> { it.differences }.thenByDescending { it.similarity }.thenBy { it.uri.toString() }
    val retained = PriorityQueue<CopyHit>(minOf(limit, 64), order.reversed())
    for ((uri, embedding) in gallery) {
        coroutine.ensureActive()
        val signature = embedding.copySignature ?: continue
        val differences = source.resemblance(signature, strict = false) ?: continue
        val similarity = seed.sceneSimilarity(embedding)
        if (similarity < .93f) continue
        val hit = CopyHit(uri, differences, similarity)
        if (retained.size == limit) {
            if (order.compare(hit, retained.element()) >= 0) continue
            retained.remove()
        }
        retained.add(hit)
    }
    return retained.sortedWith(order).map { it.uri }
}

internal fun groupSearchVersions(
    ranked: List<Uri>,
    embeddings: Map<Uri, PhotoEmbedding>,
): Map<Uri, List<Uri>> {
    val representatives = mutableListOf<Uri>()
    val families = linkedMapOf<Uri, MutableList<Uri>>()
    for (uri in ranked.distinct()) {
        val embedding = embeddings[uri] ?: continue
        val signature = embedding.copySignature ?: continue
        val representative = representatives.firstOrNull { prior ->
            val other = embeddings.getValue(prior)
            val anchor = checkNotNull(other.copySignature)
            signature.resemblance(anchor, strict = true) != null && embedding.sceneSimilarity(other) >= .98f
        }
        if (representative == null) representatives.add(uri)
        else families.getOrPut(representative) { mutableListOf() }.add(uri)
    }
    return families
}

internal data class VersionShortlist(val ranked: List<Uri>, val families: Map<Uri, List<Uri>>)

internal fun diversifySearchVersions(ranked: List<Uri>, embeddings: Map<Uri, PhotoEmbedding>): VersionShortlist {
    val families = groupSearchVersions(ranked, embeddings)
    val alternatives = families.values.flatten().toHashSet()
    val head = ranked.distinct().filterNot { it in alternatives }.take(24)
    val displayedFamilies = linkedMapOf<Uri, List<Uri>>()
    var remaining = 24
    for (uri in head) {
        val copies = families[uri].orEmpty().take(remaining)
        if (copies.isEmpty()) continue
        displayedFamilies[uri] = copies
        remaining -= copies.size
        if (remaining == 0) break
    }
    return VersionShortlist(head + displayedFamilies.values.flatten(), displayedFamilies)
}

internal fun hiddenVersions(families: Map<Uri, List<Uri>>, ordered: List<Uri>): Set<Uri> {
    if (families.isEmpty()) return emptySet()
    val visible = ordered.toHashSet()
    return families.entries.flatMapTo(hashSetOf()) { (representative, versions) ->
        if (representative in visible) versions.filter { it in visible }
        else emptyList()
    }
}

private data class CopyHit(val uri: Uri, val differences: Int, val similarity: Float)
