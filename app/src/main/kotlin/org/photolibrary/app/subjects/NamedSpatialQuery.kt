package org.photolibrary.app.subjects

import android.net.Uri
import kotlin.math.abs

private enum class ImageRelation(val words: List<String>) {
    LEFT(listOf("left", "of")),
    RIGHT(listOf("right", "of")),
    ABOVE(listOf("above")),
    BELOW(listOf("below")),
    BESIDE(listOf("beside"));

    fun verified(first: PhotoRegion, second: PhotoRegion): Boolean {
        val horizontalGap = maxOf(first.left, second.left) - minOf(first.right, second.right)
        val verticalOverlap = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        val smallerHeight = minOf(first.bottom - first.top, second.bottom - second.top)
        val centerDistance = abs((first.left + first.right - second.left - second.right) / 2)
        return when (this) {
            LEFT -> first.right + .015f <= second.left
            RIGHT -> second.right + .015f <= first.left
            ABOVE -> first.bottom + .015f <= second.top
            BELOW -> second.bottom + .015f <= first.top
            BESIDE -> horizontalGap in .015f..0.16f && verticalOverlap >= smallerHeight * .35f && centerDistance <= .44f
        }
    }
}

internal fun namedSpatialPlan(query: String, library: NamedLibrary, namedOnly: Boolean): PhotoQueryPlan? {
    if (query.any { it in "\"“”" }) return null
    val words = subjectWords(query)
    if (hasUnsupportedLogic(words) || library.subjectFor(words) != null) return null
    val operands = words.dropPrefix(listOf("show", "me")).dropPrefix(listOf("find"))
        .let { tokens -> if (tokens.take(2) in spatialFrames) tokens.drop(2) else tokens }
    val interpretations = buildList {
        for (relation in ImageRelation.entries) for (marker in 1 until operands.size) {
            if (operands.drop(marker).take(relation.words.size) != relation.words) continue
            val prefix = operands.take(marker)
            val first = library.subjectFor(prefix)
                ?: library.subjectFor(prefix.dropTrailing(listOf("to", "the")).dropTrailing(listOf("is")))
                ?: continue
            val second = library.subjectFor(operands.drop(marker + relation.words.size)) ?: continue
            if (first.id != second.id && first.kind in imageSubjectKinds && second.kind in imageSubjectKinds) {
                add(Triple(first.id, relation, second.id))
            }
        }
    }
    val (first, relation, second) = interpretations.singleOrNull() ?: return null
    val verified = linkedSetOf<Uri>()
    val unresolved = linkedSetOf<Uri>()
    for (uri in library.photosOf(setOf(first, second))) {
        val references = library.referencesIn(uri)
        val a = references.filter { it.subjectId == first }
        val b = references.filter { it.subjectId == second }
        val left = a.mapNotNull { it.region }
        val right = b.mapNotNull { it.region }
        if (left.any { one -> right.any { two -> relation.verified(one, two) } }) verified.add(uri)
        else if (left.isEmpty() || right.isEmpty() || a.any { it.region == null } || b.any { it.region == null }) unresolved.add(uri)
    }
    return PhotoQueryPlan(NamedSearch.Matches(verified), partialContents = !namedOnly && unresolved.isNotEmpty(),
        relatedScope = unresolved, unverifiedRelation = !namedOnly && unresolved.isNotEmpty())
}

private val imageSubjectKinds = setOf(SubjectKind.PERSON, SubjectKind.OBJECT, SubjectKind.CLOTHING)
private val spatialFrames = setOf(listOf("photos", "of"), listOf("pictures", "of"), listOf("images", "of"),
    listOf("photos", "showing"))
private fun List<String>.dropPrefix(prefix: List<String>) = if (take(prefix.size) == prefix) drop(prefix.size) else this
private fun List<String>.dropTrailing(suffix: List<String>) = if (takeLast(suffix.size) == suffix) dropLast(suffix.size) else this
