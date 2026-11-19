package org.photolibrary.app.library

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

internal enum class FeedbackReason { PERSON, OBJECT, ATTRIBUTE, TEXT, TIME_PLACE, DUPLICATE, OTHER }

internal data class FeedbackFeatures(val packed: Long, val baseRank: Int, val tier: Int) {
    fun vector() = DoubleArray(5) { ((packed ushr (it * 8)) and 127) / 100.0 }

    companion object {
        private val words = Regex("[\\p{L}\\p{N}]+")
        fun from(query: String, photo: SelectedPhoto, baseRank: Int, tier: Int, visual: Float?): FeedbackFeatures {
            val filename = normalizedFeedbackQuery(photo.name.orEmpty().substringBeforeLast('.', photo.name.orEmpty()))
            val search = words.findAll(query).map { it.value }.toSet()
            val inName = words.findAll(filename).map { it.value }.toSet()
            val common = search.intersect(inName).size.toDouble()
            val values = doubleArrayOf(
                common / search.size.coerceAtLeast(1),
                common / inName.size.coerceAtLeast(1),
                if (search.any { token -> token.any(Char::isDigit) && token in inName }) 1.0 else 0.0,
                if (query.length >= 3 && query in filename) 1.0 else 0.0,
                visual?.coerceIn(0f, 1f)?.toDouble() ?: 0.0,
            )
            val packed = values.foldIndexed(0L) { index, bits, value -> bits or ((100 * value).toLong() shl (8 * index)) }
            return FeedbackFeatures(packed, baseRank, tier)
        }
    }
}

internal class FeedbackHead private constructor(private val weights: DoubleArray) {
    fun shift(features: FeedbackFeatures): Double = dot(weights, features.vector()).coerceIn(-1.5, 1.5)

    private data class PairSample(val difference: DoubleArray, val baseline: Double)

    companion object {
        private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        private fun loss(score: Double) = ln(1 + exp(-score))

        private fun pairs(events: List<ResultJudgment>): List<List<PairSample>> =
            events.filter { it.features != null }.groupBy { it.query }.values.mapNotNull { group ->
                val current = group.associateBy { it.source to it.revision }.values
                val positives = current.filter { it.positive }.takeLast(8)
                val negatives = current.filterNot { it.positive }.takeLast(8)
                buildList {
                    for (p in positives) for (n in negatives) {
                        val a = p.features ?: continue
                        val b = n.features ?: continue
                        if (p.source == n.source || a.tier != b.tier || abs(a.baseRank - b.baseRank) > 5) continue
                        val x = a.vector()
                        val y = b.vector()
                        add(PairSample(DoubleArray(x.size) { x[it] - y[it] }, (b.baseRank - a.baseRank).toDouble()))
                    }
                }.takeIf { it.isNotEmpty() }
            }

        fun fit(events: List<ResultJudgment>): FeedbackHead? {
            val labeled = events.filter { it.features != null }
            if (labeled.size < 32) return null
            val cut = labeled.size * 3 / 4
            val earlier = labeled.take(cut)
            val trainingQueries = earlier.mapTo(hashSetOf()) { it.query }
            val trainingPhotos = earlier.mapTo(hashSetOf()) { it.source }
            val training = pairs(earlier)
            val validation = pairs(labeled.drop(cut).filter { it.query !in trainingQueries && it.source !in trainingPhotos })
            if (training.size < 12 || validation.size < 4) return null
            val weights = DoubleArray(5)
            repeat(60) {
                val gradient = DoubleArray(5) { 0.15 * weights[it] }
                for (group in training) for (pair in group) {
                    val chance = 1 / (1 + exp(pair.baseline + dot(weights, pair.difference)))
                    for (i in weights.indices) gradient[i] -= chance * pair.difference[i] / (training.size * group.size)
                }
                for (i in weights.indices) weights[i] = (weights[i] - 0.5 * gradient[i]).coerceIn(-1.5, 1.5)
            }
            val scale = (weights.sumOf { abs(it) } / 1.5).coerceAtLeast(1.0)
            for (i in weights.indices) weights[i] /= scale
            val gains = validation.map { group ->
                group.map { loss(it.baseline) - loss(it.baseline + dot(weights, it.difference)) }.average()
            }
            val baselineErrors = validation.sumOf { group -> group.count { it.baseline <= 0 } }
            val learnedErrors = validation.sumOf { group -> group.count { it.baseline + dot(weights, it.difference) <= 0 } }
            return if (gains.average() > 0.01 && gains.count { it > 0 } >= gains.size * 0.75 &&
                learnedErrors <= baselineErrors) FeedbackHead(weights) else null
        }
    }
}
