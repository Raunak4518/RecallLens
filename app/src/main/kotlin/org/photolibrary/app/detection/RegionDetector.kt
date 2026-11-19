package org.photolibrary.app.detection

import android.graphics.Bitmap
import android.graphics.PointF
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind

internal data class DetectedRegion(
    val label: String,
    val kind: SubjectKind,
    val region: PhotoRegion,
    val confidence: Float,
    val landmarks: List<PointF> = emptyList(),
)

internal interface RegionDetector : AutoCloseable {
    fun detect(image: Bitmap, kind: SubjectKind? = null): List<DetectedRegion>
}

internal fun intersection(first: PhotoRegion, second: PhotoRegion): Float {
    val width = (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0f)
    val height = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0f)
    return width * height
}

internal fun PhotoRegion.area(): Float = (right - left) * (bottom - top)

internal fun suppressOverlaps(regions: List<DetectedRegion>, overlap: Float, limit: Int): List<DetectedRegion> {
    val chosen = mutableListOf<DetectedRegion>()
    for (candidate in regions.sortedByDescending { it.confidence }) {
        val region = candidate.region
        val redundant = chosen.any { previous ->
            if (candidate.label != previous.label) false else {
                val common = intersection(region, previous.region)
                common / (region.area() + previous.region.area() - common) > overlap
            }
        }
        if (!redundant) chosen += candidate
        if (chosen.size == limit) break
    }
    return chosen
}
