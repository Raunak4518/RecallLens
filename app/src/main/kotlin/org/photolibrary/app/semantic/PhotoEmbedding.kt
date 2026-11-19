package org.photolibrary.app.semantic

import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.sqrt
import org.photolibrary.app.index.tracePhotoStage

internal class PhotoEmbedding private constructor(
    private val values: FloatArray,
    private val details: List<PhotoEmbedding> = emptyList(),
    val copySignature: PhotoCopySignature? = null,
) {
    fun sceneSimilarity(other: PhotoEmbedding): Float {
        var sum = 0f
        for (i in values.indices) sum += values[i] * other.values[i]
        return sum
    }

    fun similarity(other: PhotoEmbedding): Float =
        if (other.details.isEmpty()) sceneSimilarity(other)
        else maxOf(sceneSimilarity(other), other.details.maxOf { sceneSimilarity(it) })

    fun withCopySignature(signature: PhotoCopySignature): PhotoEmbedding = PhotoEmbedding(values, details, signature)

    fun withDetails(views: List<PhotoEmbedding>): PhotoEmbedding {
        require(views.size <= 2 && views.all { it.details.isEmpty() })
        return PhotoEmbedding(values, views, copySignature)
    }

    fun encode(): ByteArray = ByteBuffer.allocate(DIMENSIONS * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        asFloatBuffer().put(values)
    }.array()

    fun encodeForStorage(): ByteArray {
        val payload = encodeViews()
        val signature = copySignature ?: return payload
        return ByteBuffer.allocate(8 + PhotoCopySignature.BYTES + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(SIGNATURE_HEADER)
            signature.encode(this)
            putInt(payload.size)
            put(payload)
        }.array()
    }

    private fun encodeViews(): ByteArray {
        if (details.isEmpty()) return encodeSingle()
        val parts = listOf(encodeSingle()) + details.map { it.encodeSingle() }
        val buffer = ByteBuffer.allocate(4 + parts.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(parts.size)
        for (part in parts) buffer.putInt(part.size).put(part)
        return buffer.array()
    }

    private fun encodeSingle(): ByteArray {
        if (!org.photolibrary.app.BuildConfig.COMPACT_SCENE_INDEX) return encode()
        val peak = values.maxOf { kotlin.math.abs(it) }
        require(peak.isFinite() && peak > 0f)
        val buffer = ByteBuffer.allocate(5 + values.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(1.toByte()).putFloat(peak)
        values.forEach { buffer.put(Math.round(it / peak * 127f).coerceIn(-127, 127).toByte()) }
        return buffer.array()
    }

    companion object {
        private const val DIMENSIONS = 512
        private const val SIGNATURE_HEADER = -3

        fun normalized(values: FloatArray): PhotoEmbedding {
            require(values.size == DIMENSIONS)
            val length = sqrt(values.sumOf { it.toDouble() * it }).toFloat()
            require(length.isFinite() && length > 0f)
            return PhotoEmbedding(FloatArray(DIMENSIONS) { values[it] / length })
        }

        fun decode(bytes: ByteArray): PhotoEmbedding {
            if (bytes.size >= 8 + PhotoCopySignature.BYTES && bytes.size != DIMENSIONS * Float.SIZE_BYTES &&
                bytes.size != 5 + DIMENSIONS) {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if (buffer.int == SIGNATURE_HEADER) {
                    val signature = PhotoCopySignature.decode(buffer)
                    val size = buffer.int
                    require(size == buffer.remaining())
                    return decodeViews(ByteArray(size).also(buffer::get)).withCopySignature(signature)
                }
            }
            return decodeViews(bytes)
        }

        private fun decodeViews(bytes: ByteArray): PhotoEmbedding {
            if (bytes.size != DIMENSIONS * Float.SIZE_BYTES && bytes.size != 5 + DIMENSIONS) {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                require(buffer.remaining() >= 4)
                val count = buffer.int
                require(count in 2..3)
                val views = List(count) {
                    require(buffer.remaining() >= 4)
                    val size = buffer.int
                    require(size in setOf(DIMENSIONS * Float.SIZE_BYTES, 5 + DIMENSIONS) && size <= buffer.remaining())
                    decodeViews(ByteArray(size).also(buffer::get))
                }
                require(!buffer.hasRemaining())
                return views.first().withDetails(views.drop(1))
            }
            if (bytes.size == 5 + DIMENSIONS) {
                val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                require(input.get().toInt() == 1)
                val peak = input.float
                require(peak.isFinite() && peak > 0f)
                return normalized(FloatArray(DIMENSIONS) { input.get().toInt() * peak / 127f })
            }
            require(bytes.size == DIMENSIONS * Float.SIZE_BYTES)
            val vector = FloatArray(DIMENSIONS)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector)
            require(vector.all { it.isFinite() })
            return PhotoEmbedding(vector)
        }
    }
}

private data class ScoredPhoto(val uri: Uri, val score: Float)

private val bestFirst = compareByDescending<ScoredPhoto> { it.score }.thenBy { it.uri.toString() }

internal fun closestPhotos(
    query: PhotoEmbedding,
    gallery: Map<Uri, PhotoEmbedding>,
    limit: Int,
    eligible: (Uri) -> Boolean = { true },
): List<Uri> {
    require(limit >= 0)
    if (limit == 0 || gallery.isEmpty()) return emptyList()
    return tracePhotoStage("photos.rank.exact") {
        val retained = PriorityQueue(minOf(limit, gallery.size, 64), bestFirst.reversed())
        for ((uri, photo) in gallery) {
            if (!eligible(uri)) continue
            val score = query.similarity(photo)
            if (retained.size == limit) {
                val worst = retained.element()
                val comparison = score.compareTo(worst.score)
                if (comparison < 0 || comparison == 0 && uri.toString() >= worst.uri.toString()) continue
                retained.remove()
            }
            retained.add(ScoredPhoto(uri, score))
        }
        retained.sortedWith(bestFirst).map { it.uri }
    }
}
