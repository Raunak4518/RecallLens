package org.photolibrary.app.semantic

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import kotlin.math.abs

internal class PhotoCopySignature private constructor(
    private val width: Int,
    private val height: Int,
    private val horizontal: Long,
    private val vertical: Long,
    private val palette: ByteArray,
    private val contrast: Int,
) {
    fun resemblance(other: PhotoCopySignature, strict: Boolean): Int? {
        val aspect = width.toDouble() / height
        val otherAspect = other.width.toDouble() / other.height
        if (abs(aspect - otherAspect) > maxOf(aspect, otherAspect) * if (strict) .025 else .10) return null
        if (minOf(contrast, other.contrast) < 20) return null
        val differences = java.lang.Long.bitCount(horizontal xor other.horizontal) +
            java.lang.Long.bitCount(vertical xor other.vertical)
        val averageColorDifference = palette.indices.sumOf { index ->
            abs((palette[index].toInt() and 255) - (other.palette[index].toInt() and 255))
        } / palette.size.toDouble()
        if (differences > if (strict) 7 else 20) return null
        if (averageColorDifference > if (strict) 9 else 28) return null
        return differences
    }

    fun encode(buffer: ByteBuffer) {
        buffer.putInt(width).putInt(height).putLong(horizontal).putLong(vertical).put(palette).putInt(contrast)
    }

    companion object {
        private const val SIDE = 9
        private const val PALETTE_BYTES = 12
        const val BYTES = 2 * Int.SIZE_BYTES + 2 * Long.SIZE_BYTES + PALETTE_BYTES + Int.SIZE_BYTES

        fun decode(buffer: ByteBuffer): PhotoCopySignature {
            val width = buffer.int
            val height = buffer.int
            val horizontal = buffer.long
            val vertical = buffer.long
            val palette = ByteArray(PALETTE_BYTES).also(buffer::get)
            val contrast = buffer.int
            require(width > 0 && height > 0 && contrast in 0..255)
            return PhotoCopySignature(width, height, horizontal, vertical, palette, contrast)
        }

        fun measure(image: Bitmap): PhotoCopySignature {
            val reduced = Bitmap.createScaledBitmap(image, SIDE, SIDE, true)
            val pixels = IntArray(SIDE * SIDE)
            try {
                reduced.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            } finally {
                if (reduced !== image) reduced.recycle()
            }
            val luminance = IntArray(pixels.size)
            val colors = IntArray(PALETTE_BYTES)
            val counts = IntArray(4)
            for ((index, pixel) in pixels.withIndex()) {
                val row = index / SIDE
                val column = index % SIDE
                val quadrant = (if (column >= SIDE / 2) 1 else 0) + (if (row >= SIDE / 2) 2 else 0)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                colors[3 * quadrant] += red
                colors[3 * quadrant + 1] += green
                colors[3 * quadrant + 2] += blue
                counts[quadrant]++
                luminance[index] = (77 * red + 150 * green + 29 * blue) / 256
            }
            var horizontal = 0L
            var vertical = 0L
            for (row in 0 until 8) for (column in 0 until 8) {
                val position = row * SIDE + column
                val bit = 1L shl (row * 8 + column)
                if (luminance[position] > luminance[position + 1]) horizontal = horizontal or bit
                if (luminance[position] > luminance[position + SIDE]) vertical = vertical or bit
            }
            val palette = ByteArray(PALETTE_BYTES) { (colors[it] / counts[it / 3]).toByte() }
            val contrast = luminance.max() - luminance.min()
            return PhotoCopySignature(image.width, image.height, horizontal, vertical, palette, contrast)
        }
    }
}
