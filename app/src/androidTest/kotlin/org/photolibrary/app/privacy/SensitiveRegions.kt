package org.photolibrary.app.privacy

import android.graphics.Bitmap
import java.text.Normalizer
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind

internal data class SensitiveRegions(val identifiers: List<PhotoRegion>, val faces: List<PhotoRegion>) {
    val masks get() = identifiers + faces
}

internal class LocalSensitiveRegions(private val models: OnnxRegionModels) {
    fun detect(image: Bitmap): SensitiveRegions {
        if (!models.available) throw ExportFailure.Detector()
        val ocr = LocalTextIndex.recognizer().use { it.process(image) }
        val identifiers = identifierRegions(ocr, image.width, image.height)
        val faces = models.open().use { detector ->
            detector.detect(image, SubjectKind.PERSON).map { pad(it.region, .04f, .04f) }
        }
        return SensitiveRegions(identifiers, faces)
    }
}

private val email = Regex("[\\p{L}\\p{N}._+%-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}")
private val numeric = Regex("\\d(?:[\\d .()+-]*\\d)?")
private val pan = Regex("(?i)(?<![a-z0-9])[a-z]{5}[0-9]{4}[a-z](?![a-z0-9])")

internal fun identifierRegions(text: PhotoText, width: Int, height: Int): List<PhotoRegion> {
    if (!text.complete) throw ExportFailure.Detector()
    return text.lines.filter { line ->
        val normalized = Normalizer.normalize(line.text, Normalizer.Form.NFKC)
        email.containsMatchIn(normalized) || pan.containsMatchIn(normalized) ||
            numeric.findAll(normalized).any { it.value.count(Char::isDigit) >= 7 }
    }.map { line ->
        val box = line.bounds ?: throw ExportFailure.Detector()
        val left = (box.left.toFloat() / width).coerceIn(0f, 1f)
        val top = (box.top.toFloat() / height).coerceIn(0f, 1f)
        val right = (box.right.toFloat() / width).coerceIn(0f, 1f)
        val bottom = (box.bottom.toFloat() / height).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) throw ExportFailure.Detector()
        pad(PhotoRegion(left, top, right, bottom), 4f / width, 4f / height)
    }
}

private fun pad(region: PhotoRegion, x: Float, y: Float) = PhotoRegion(
    (region.left - x).coerceAtLeast(0f), (region.top - y).coerceAtLeast(0f),
    (region.right + x).coerceAtMost(1f), (region.bottom + y).coerceAtMost(1f),
)
