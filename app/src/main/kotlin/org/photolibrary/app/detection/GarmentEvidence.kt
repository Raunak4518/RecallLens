package org.photolibrary.app.detection

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.floor
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.subjects.PhotoRegion

internal interface GarmentParser : AutoCloseable {
    val model: ModelRevision
    fun upperClothes(image: Bitmap): GarmentMask
}

internal class GarmentMask(
    val width: Int,
    val height: Int,
    private val upperClothes: BooleanArray,
) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= 262_144)
        require(upperClothes.size == width * height)
    }

    fun contains(x: Int, y: Int) = upperClothes[y * width + x]
}

internal enum class GarmentColor { RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, WHITE, BLACK, GRAY }

internal sealed interface PersonGarmentEvidence {
    data class Found(
        val face: PhotoRegion,
        val person: PhotoRegion,
        val model: ModelRevision,
        val sampledPixels: Int,
        val colorFractions: Map<GarmentColor, Float>,
    ) : PersonGarmentEvidence

    data class Missing(val face: PhotoRegion, val reason: GarmentEvidenceMissing) : PersonGarmentEvidence
}

internal enum class GarmentEvidenceMissing { NO_PERSON, AMBIGUOUS_PERSON, OVERLAPPING_PEOPLE, NO_UPPER_CLOTHES }
internal enum class GarmentOverlapPolicy { WITHHOLD, EXCLUDE_OTHER_PEOPLE }

internal fun associatePerson(
    face: PhotoRegion, people: List<PhotoRegion>, policy: GarmentOverlapPolicy = GarmentOverlapPolicy.WITHHOLD,
): Pair<PhotoRegion?, GarmentEvidenceMissing?> {
    val candidates = people.filter { person ->
        intersection(face, person) / face.area() >= .9f &&
            (face.top + face.bottom) / 2 < person.top + (person.bottom - person.top) * .55f
    }
    if (candidates.isEmpty()) return null to GarmentEvidenceMissing.NO_PERSON
    if (candidates.size > 1) return null to GarmentEvidenceMissing.AMBIGUOUS_PERSON
    val person = candidates.single()
    val overlap = people.any { other ->
        other != person && intersection(person, other) / minOf(person.area(), other.area()) > .15f
    }
    return if (overlap && policy == GarmentOverlapPolicy.WITHHOLD) null to GarmentEvidenceMissing.OVERLAPPING_PEOPLE else person to null
}

internal fun describePersonGarment(
    image: Bitmap,
    face: PhotoRegion,
    faces: List<PhotoRegion>,
    people: List<PhotoRegion>,
    parser: GarmentParser,
    overlapPolicy: GarmentOverlapPolicy = GarmentOverlapPolicy.WITHHOLD,
): PersonGarmentEvidence {
    val (person, error) = associatePerson(face, people, overlapPolicy)
    if (person == null) return PersonGarmentEvidence.Missing(face, checkNotNull(error))
    val owners = faces.count { other -> associatePerson(other, people, overlapPolicy).first == person }
    if (owners != 1) return PersonGarmentEvidence.Missing(face, GarmentEvidenceMissing.AMBIGUOUS_PERSON)
    val left = floor(person.left * image.width).toInt()
    val top = floor(person.top * image.height).toInt()
    val right = kotlin.math.ceil(person.right * image.width).toInt()
    val bottom = kotlin.math.ceil(person.bottom * image.height).toInt()
    val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
    return try {
        val mask = parser.upperClothes(crop)
        val histogram = mutableMapOf<GarmentColor, Int>()
        val neighbors = if (overlapPolicy == GarmentOverlapPolicy.EXCLUDE_OTHER_PEOPLE) people.filter { it != person } else emptyList()
        var removed = 0
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (!mask.contains(x, y)) continue
                val px = ((x + .5f) * crop.width / mask.width).toInt().coerceAtMost(crop.width - 1)
                val py = ((y + .5f) * crop.height / mask.height).toInt().coerceAtMost(crop.height - 1)
                val worldX = (left + px + .5f) / image.width
                val worldY = (top + py + .5f) / image.height
                if (neighbors.any { worldX >= it.left && worldX <= it.right && worldY >= it.top && worldY <= it.bottom }) {
                    removed++
                    continue
                }
                val color = garmentColor(crop.getPixel(px, py))
                histogram[color] = histogram.getOrDefault(color, 0) + 1
            }
        }
        val count = histogram.values.sum()
        if (count < 16 || count.toFloat() / (mask.width * mask.height) < .01f) {
            PersonGarmentEvidence.Missing(face, if (removed > 0) GarmentEvidenceMissing.OVERLAPPING_PEOPLE else GarmentEvidenceMissing.NO_UPPER_CLOTHES)
        } else {
            PersonGarmentEvidence.Found(face, person, parser.model, count,
                histogram.mapValues { it.value.toFloat() / count })
        }
    } finally {
        if (crop !== image) crop.recycle()
    }
}

internal fun garmentColor(pixel: Int): GarmentColor {
    val hsv = FloatArray(3)
    Color.colorToHSV(pixel, hsv)
    val (hue, saturation, value) = hsv
    return when {
        value < .2f -> GarmentColor.BLACK
        saturation < .18f && value > .65f -> GarmentColor.WHITE
        saturation < .18f -> GarmentColor.GRAY
        hue < 20 || hue >= 340 -> GarmentColor.RED
        hue < 45 -> GarmentColor.ORANGE
        hue < 70 -> GarmentColor.YELLOW
        hue < 170 -> GarmentColor.GREEN
        hue < 260 -> GarmentColor.BLUE
        else -> GarmentColor.PURPLE
    }
}
