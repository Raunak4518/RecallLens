package org.photolibrary.app.identity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF

private val template = listOf(
    PointF(38.2946f, 51.6963f),
    PointF(73.5318f, 51.5014f),
    PointF(56.0252f, 71.7366f),
    PointF(41.5493f, 92.3655f),
    PointF(70.7299f, 92.2041f),
)

internal fun alignFace(image: Bitmap, landmarks: List<PointF>): Bitmap? {
    if (landmarks.size != template.size || landmarks.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val source = landmarks.map { PointF(it.x * image.width, it.y * image.height) }
    val centerX = source.map { it.x }.average()
    val centerY = source.map { it.y }.average()
    val targetX = template.map { it.x }.average()
    val targetY = template.map { it.y }.average()
    var energy = 0.0
    var dot = 0.0
    var cross = 0.0
    for (i in source.indices) {
        val x = source[i].x - centerX
        val y = source[i].y - centerY
        val u = template[i].x - targetX
        val v = template[i].y - targetY
        energy += x * x + y * y
        dot += x * u + y * v
        cross += x * v - y * u
    }
    if (energy <= 0.0) return null
    val a = dot / energy
    val b = cross / energy
    val matrix = Matrix().apply {
        setValues(floatArrayOf(a.toFloat(), -b.toFloat(), (targetX - a * centerX + b * centerY).toFloat(),
            b.toFloat(), a.toFloat(), (targetY - b * centerX - a * centerY).toFloat(), 0f, 0f, 1f))
    }
    return Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawBitmap(image, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}
