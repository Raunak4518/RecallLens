package org.photolibrary.app.semantic

import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ResidualVisualRanker private constructor(
    private val depth: Int,
    private val projection: FloatArray,
    private val residual: FloatArray,
    private val rank: Int,
) {
    fun reorder(query: PhotoEmbedding, original: List<Uri>, gallery: Map<Uri, PhotoEmbedding>): List<Uri> {
        if (original.size < 2) return original
        val values = FloatArray(DIMENSIONS)
        ByteBuffer.wrap(query.encode()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
        val hidden = FloatArray(rank) { row -> dot(projection, row * DIMENSIONS, values) }
        val adjusted = PhotoEmbedding.normalized(FloatArray(DIMENSIONS) { row ->
            values[row] + dot(residual, row * rank, hidden)
        })
        val shortlist = original.take(depth)
        val scores = shortlist.associateWith { adjusted.sceneSimilarity(gallery.getValue(it)) }
        return shortlist.sortedByDescending(scores::getValue) + original.drop(depth)
    }

    private fun dot(matrix: FloatArray, offset: Int, vector: FloatArray): Float {
        var total = 0f
        for (index in vector.indices) total += matrix[offset + index] * vector[index]
        return total
    }

    companion object {
        private const val DIMENSIONS = 512
        private const val HEADER_BYTES = 80
        private const val HEX_LENGTH = 64

        fun decode(bytes: ByteArray, expectedTextSha256: String): ResidualVisualRanker {
            require(bytes.size >= HEADER_BYTES)
            require(expectedTextSha256.matches(Regex("[0-9a-f]{64}")))
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val signature = ByteArray(4).also(buffer::get)
            require(signature.contentEquals("PLR1".encodeToByteArray()))
            require(buffer.int == DIMENSIONS)
            val rank = buffer.int
            val depth = buffer.int
            require(rank in 1..128 && depth in 5..100)
            val textDigest = ByteArray(HEX_LENGTH / 2).also(buffer::get)
            require(textDigest.contentEquals(expectedTextSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
            buffer.position(HEADER_BYTES)
            val entries = rank * DIMENSIONS
            require(buffer.remaining() == 2 * entries * Float.SIZE_BYTES)
            val weights = buffer.asFloatBuffer()
            val projection = FloatArray(entries).also(weights::get)
            val residual = FloatArray(entries).also(weights::get)
            require(projection.all(Float::isFinite) && residual.all(Float::isFinite))
            return ResidualVisualRanker(depth, projection, residual, rank)
        }
    }
}
