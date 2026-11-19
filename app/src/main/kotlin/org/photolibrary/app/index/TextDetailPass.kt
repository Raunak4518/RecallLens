package org.photolibrary.app.index

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.floor

internal class TextDetailPass(private val loadImage: suspend (Uri, Int) -> Bitmap?) {
    suspend fun refine(uri: Uri, image: Bitmap, initial: PhotoText, processor: PhotoProcessor<PhotoText>): PhotoText {
        if (!initial.complete || maxOf(image.width, image.height) < processor.model.maxImageEdge) return initial
        val regions = ArrayList<Rect>(2)
        for (line in initial.lines) {
            val box = line.bounds ?: continue
            val height = box.bottom - box.top
            if (height !in 1..18 || box.right <= box.left) continue
            val padding = maxOf(10, height * 2)
            val rect = Rect(box.left - padding, box.top - padding, box.right + padding, box.bottom + padding)
            if (!rect.intersect(0, 0, image.width, image.height) || regions.any { Rect.intersects(it, rect) }) continue
            regions.add(rect)
            if (regions.size == 2) break
        }
        if (regions.isEmpty()) return initial
        currentCoroutineContext().ensureActive()
        val detail = loadImage(uri, 2560) ?: return initial
        try {
            if (detail.width <= image.width || detail.height <= image.height) return initial
            val scaleX = detail.width.toDouble() / image.width
            val scaleY = detail.height.toDouble() / image.height
            val lines = initial.lines.toMutableList()
            var characters = lines.sumOf { it.text.length }
            for (region in regions) {
                currentCoroutineContext().ensureActive()
                val x = floor(region.left * scaleX).toInt().coerceIn(0, detail.width - 1)
                val y = floor(region.top * scaleY).toInt().coerceIn(0, detail.height - 1)
                val right = ceil(region.right * scaleX).toInt().coerceIn(x + 1, detail.width)
                val bottom = ceil(region.bottom * scaleY).toInt().coerceIn(y + 1, detail.height)
                val crop = Bitmap.createBitmap(detail, x, y, right - x, bottom - y)
                val extra = try {
                    tracePhotoStage("photos.index.detailOcr") { processor.process(crop) }
                } catch (_: PhotoProcessingException) {
                    continue
                } finally {
                    if (crop !== detail) crop.recycle()
                }
                currentCoroutineContext().ensureActive()
                for (line in extra.lines) {
                    val local = line.bounds ?: continue
                    val box = TextBounds(floor((local.left + x) / scaleX).toInt().coerceIn(0, image.width),
                        floor((local.top + y) / scaleY).toInt().coerceIn(0, image.height),
                        ceil((local.right + x) / scaleX).toInt().coerceIn(0, image.width),
                        ceil((local.bottom + y) / scaleY).toInt().coerceIn(0, image.height))
                    if (box.left >= box.right || box.top >= box.bottom) continue
                    val matching = lines.indexOfFirst { overlap(it.bounds, box) >= 0.5 }
                    if (matching >= 0) {
                        val old = lines[matching].text
                        if (line.text.length <= old.length || !normalizePhotoText(line.text).contains(normalizePhotoText(old))) continue
                        if (characters - old.length + line.text.length > 16_384) continue
                        lines[matching] = TextLine(line.text, line.language, box)
                        characters += line.text.length - old.length
                    } else if (lines.size < 256 && characters + line.text.length <= 16_384) {
                        lines.add(TextLine(line.text, line.language, box))
                        characters += line.text.length
                    }
                }
            }
            return PhotoText(lines, initial.complete)
        } finally {
            if (detail !== image) detail.recycle()
        }
    }

    private fun overlap(first: TextBounds?, second: TextBounds): Double {
        if (first == null) return 0.0
        val width = (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)
        val height = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0)
        val intersection = width * height
        val union = (first.right - first.left) * (first.bottom - first.top) +
            (second.right - second.left) * (second.bottom - second.top) - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union
    }
}
