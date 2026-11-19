package org.photolibrary.app.identity

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.FloatBuffer
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import org.photolibrary.app.detection.DetectedRegion
import org.photolibrary.app.index.PhotoProcessingException
import org.photolibrary.app.models.ModelFileStore
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind

internal class IdentityModels(private val modelFiles: ModelFileStore) : IdentityModelFactory {
    constructor(assets: AssetManager) : this(ModelFileStore.bundled(assets))

    override fun available(kind: SubjectKind): Boolean = model(kind)?.let { modelFiles.available("identity/$it") } == true
    private val faceFingerprint by lazy { fingerprint(listOf("identity/sface.onnx", "detection/yunet.onnx")) }
    private val objectFingerprint by lazy {
        fingerprint(listOf("identity/dinov2.onnx", "detection/yolox_nano.onnx", "detection/coco-labels.txt"))
    }

    override fun fingerprint(kind: SubjectKind): String = inference {
        when (kind) {
            SubjectKind.PERSON -> faceFingerprint
            SubjectKind.OBJECT -> objectFingerprint
            else -> error("Unsupported identity kind")
        }
    }

    private fun fingerprint(paths: List<String>): String = paths.joinToString(":") { path ->
        val hash = MessageDigest.getInstance("SHA-256")
        DigestInputStream(modelFiles.open(path), hash).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (stream.read(buffer) >= 0) Unit
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    override fun open(kind: SubjectKind): IdentityEncoder = inference {
        require(kind == SubjectKind.PERSON || kind == SubjectKind.OBJECT)
        val environment = OrtEnvironment.getEnvironment()
        val session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            environment.createSession(modelFiles.map("identity/${model(kind)}"), options)
        }
        val runtime = IdentityRuntime(environment, session)
        when (kind) {
            SubjectKind.PERSON -> SFaceEncoder(runtime)
            SubjectKind.OBJECT -> DinoObjectEncoder(runtime)
            else -> error("Unsupported identity kind")
        }
    }

    private fun model(kind: SubjectKind): String? = when (kind) {
        SubjectKind.PERSON -> "sface.onnx"
        SubjectKind.OBJECT -> "dinov2.onnx"
        else -> null
    }
}

private class SFaceEncoder(private val runtime: IdentityRuntime) : IdentityEncoder {
    override fun encode(image: Bitmap, region: PhotoRegion?, detection: DetectedRegion?): IdentityVector? = inference {
        val aligned = alignFace(image, detection?.landmarks.orEmpty()) ?: return@inference null
        try {
            runtime.vector(aligned, pixels(aligned) { value, _ -> value }) { it as FloatArray }
                ?.let { IdentityVector(it, null) }
        } finally {
            if (aligned !== image) aligned.recycle()
        }
    }

    override fun close() = runtime.close()
}

private class DinoObjectEncoder(private val runtime: IdentityRuntime) : IdentityEncoder {
    override fun encode(image: Bitmap, region: PhotoRegion?, detection: DetectedRegion?): IdentityVector? = inference {
        val cropped = objectCrop(image, region)
        try {
            val values = pixels(cropped) { value, channel ->
                when (channel) {
                    0 -> (value / 255f - .485f) / .229f
                    1 -> (value / 255f - .456f) / .224f
                    else -> (value / 255f - .406f) / .225f
                }
            }
            runtime.vector(cropped, values) { (it as Array<*>).first() as FloatArray }
                ?.let { IdentityVector(it, objectColor(image, region)) }
        } finally {
            if (cropped !== image) cropped.recycle()
        }
    }

    override fun close() = runtime.close()
}

private class IdentityRuntime(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {
    fun vector(image: Bitmap, values: FloatArray, decode: (Any?) -> FloatArray): FloatArray? {
        val side = image.width.toLong()
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, side, side)).use { input ->
            session.run(mapOf(session.inputNames.single() to input)).use { result ->
                normalize(decode((result[0].value as Array<*>).single()))
            }
        }
    }

    override fun close() = session.close()
}

internal data class IdentityVector(private val appearance: FloatArray, private val color: FloatArray?) {
    fun similarity(other: IdentityVector): Float {
        val visual = dot(appearance, other.appearance)
        val one = color ?: return visual
        val two = other.color ?: return visual
        return .85f * visual + .15f * dot(one, two)
    }

    fun writeTo(output: DataOutputStream) {
        appearance.forEach(output::writeFloat)
        output.writeBoolean(color != null)
        color?.forEach(output::writeFloat)
    }

    companion object {
        fun readFrom(input: DataInputStream, kind: SubjectKind): IdentityVector {
            val face = kind == SubjectKind.PERSON
            val appearance = FloatArray(if (face) 128 else 384) { input.readFloat() }
            val hasColor = input.readBoolean()
            require(!face || !hasColor)
            val color = if (hasColor) FloatArray(18) { input.readFloat() } else null
            require(appearance.all(Float::isFinite) && (color == null || color.all(Float::isFinite)))
            return IdentityVector(appearance, color)
        }
    }
}

private fun dot(first: FloatArray, second: FloatArray): Float =
    first.indices.fold(0f) { sum, index -> sum + first[index] * second[index] }

private fun objectColor(image: Bitmap, region: PhotoRegion?): FloatArray? {
    val rect = bounds(image, region)
    val step = (maxOf(rect.width(), rect.height()) / 64).coerceAtLeast(1)
    val hue = FloatArray(18)
    val hsv = FloatArray(3)
    var samples = 0
    var weight = 0f
    for (y in rect.top until rect.bottom step step) {
        for (x in rect.left until rect.right step step) {
            samples++
            Color.colorToHSV(image.getPixel(x, y), hsv)
            val strength = (hsv[1] - .22f).coerceAtLeast(0f) * (hsv[2] - .15f).coerceAtLeast(0f)
            val position = hsv[0] * hue.size / 360f
            val bin = position.toInt()
            val fraction = position - bin
            hue[bin % hue.size] += strength * (1f - fraction)
            hue[(bin + 1) % hue.size] += strength * fraction
            weight += strength
        }
    }
    return if (weight >= samples * .01f) normalize(hue) else null
}

private fun objectCrop(image: Bitmap, region: PhotoRegion?): Bitmap {
    val rect = bounds(image, region)
    val result = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
    val scale = 224f / maxOf(rect.width(), rect.height())
    val width = rect.width() * scale
    val height = rect.height() * scale
    val left = (224f - width) / 2
    val top = (224f - height) / 2
    val canvas = android.graphics.Canvas(result)
    canvas.drawColor(Color.rgb(124, 116, 104))
    canvas.drawBitmap(image, rect, android.graphics.RectF(left, top, left + width, top + height),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    return result
}

private fun bounds(image: Bitmap, region: PhotoRegion?): Rect =
    if (region == null) Rect(0, 0, image.width, image.height)
    else Rect(
        floor(region.left * image.width).toInt(), floor(region.top * image.height).toInt(),
        ceil(region.right * image.width).toInt(), ceil(region.bottom * image.height).toInt(),
    )

private inline fun pixels(bitmap: Bitmap, transform: (Float, Int) -> Float): FloatArray {
    val plane = bitmap.width * bitmap.height
    val pixels = IntArray(plane)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val values = FloatArray(3 * plane)
    for ((index, pixel) in pixels.withIndex()) {
        val r = Color.red(pixel).toFloat()
        val g = Color.green(pixel).toFloat()
        val b = Color.blue(pixel).toFloat()
        values[index] = transform(r, 0)
        values[index + plane] = transform(g, 1)
        values[index + 2 * plane] = transform(b, 2)
    }
    return values
}

private fun normalize(vector: FloatArray): FloatArray? {
    val norm = sqrt(vector.fold(0.0) { sum, value -> sum + value.toDouble() * value })
    return if (norm > 0 && norm.isFinite()) FloatArray(vector.size) { (vector[it] / norm).toFloat() } else null
}

private inline fun <T> inference(action: () -> T): T = try {
    action()
} catch (error: OrtException) {
    throw PhotoProcessingException(error)
} catch (error: IOException) {
    throw PhotoProcessingException(error)
} catch (error: IllegalArgumentException) {
    throw PhotoProcessingException(error)
}
