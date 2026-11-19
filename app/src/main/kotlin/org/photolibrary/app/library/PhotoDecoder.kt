package org.photolibrary.app.library

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import java.io.IOException
import kotlin.math.roundToInt

internal fun decodePhoto(source: ImageDecoder.Source, maxEdge: Int, software: Boolean = false, stillOnly: Boolean = false): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        if (width <= 0 || height <= 0 || width.toLong() * height > 128_000_000L || (stillOnly && info.isAnimated)) {
            throw IOException("Unsupported image dimensions")
        }
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
        if (software) {
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        decoder.setTargetSize(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
        )
    }
