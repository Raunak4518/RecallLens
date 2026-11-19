package org.photolibrary.app.detection

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.exp
import org.photolibrary.app.subjects.PhotoRegion

internal class DetectorInput(image: Bitmap, val side: Int, background: Float) {
    private val scale = side.toFloat() / maxOf(image.width, image.height)
    private val width = (image.width * scale).toInt().coerceIn(1, side)
    private val height = (image.height * scale).toInt().coerceIn(1, side)
    val channels = FloatArray(3 * side * side) { background }

    init {
        val scaled = Bitmap.createScaledBitmap(image, width, height, true)
        val pixels = IntArray(width * height)
        try {
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            if (scaled !== image) scaled.recycle()
        }
        val plane = side * side
        for ((index, pixel) in pixels.withIndex()) {
            val offset = index / width * side + index % width
            channels[offset] = Color.blue(pixel).toFloat()
            channels[offset + plane] = Color.green(pixel).toFloat()
            channels[offset + 2 * plane] = Color.red(pixel).toFloat()
        }
    }

    fun box(cell: Int, stride: Int, values: FloatArray, offset: Int = 0): PhotoRegion? {
        val columns = side / stride
        val x = (cell % columns + values[offset]) * stride
        val y = (cell / columns + values[offset + 1]) * stride
        val halfWidth = exp(values[offset + 2]) * stride / 2
        val halfHeight = exp(values[offset + 3]) * stride / 2
        if (!x.isFinite() || !y.isFinite() || !halfWidth.isFinite() || !halfHeight.isFinite()) return null
        val left = ((x - halfWidth) / width).coerceIn(0f, 1f)
        val top = ((y - halfHeight) / height).coerceIn(0f, 1f)
        val right = ((x + halfWidth) / width).coerceIn(0f, 1f)
        val bottom = ((y + halfHeight) / height).coerceIn(0f, 1f)
        return if (left < right && top < bottom) PhotoRegion(left, top, right, bottom) else null
    }

    fun landmarks(cell: Int, stride: Int, values: FloatArray): List<PointF> {
        val columns = side / stride
        return List(5) { index ->
            PointF((cell % columns + values[index * 2]) * stride / width,
                (cell / columns + values[index * 2 + 1]) * stride / height)
        }
    }
}
