package org.photolibrary.app.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.photolibrary.app.subjects.SubjectKind

internal suspend fun groupReviewRegions(candidates: List<NameSuggestion>): List<List<NameSuggestion>> {
    val groups = mutableListOf<MutableList<NameSuggestion>>()
    val byLabel = mutableMapOf<Pair<SubjectKind, String>, MutableList<MutableList<NameSuggestion>>>()
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        val detection = candidate.detection
        val bucket = byLabel.getOrPut(detection.kind to detection.label) { mutableListOf() }
        val descriptor = candidate.descriptor
        var best: MutableList<NameSuggestion>? = null
        var bestScore = -1f
        var runnerUp = -1f
        if (descriptor != null) {
            for (group in bucket.takeLast(128)) {
                if (group.size >= 12 || group.any { it.photo.uri == candidate.photo.uri }) continue
                val score = group.minOf { it.descriptor!!.similarity(descriptor) }
                if (score > bestScore) {
                    runnerUp = bestScore
                    bestScore = score
                    best = group
                } else if (score > runnerUp) runnerUp = score
            }
        }
        val threshold = if (detection.kind == SubjectKind.PERSON) .62f else .90f
        if (best != null && bestScore >= threshold && bestScore - runnerUp >= .04f) {
            best.add(candidate)
        } else {
            val singleton = mutableListOf(candidate)
            groups.add(singleton)
            if (descriptor != null) bucket.add(singleton)
        }
    }
    return groups.sortedByDescending { it.size }
}
