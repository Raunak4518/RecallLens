package org.photolibrary.app.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.exp
import org.photolibrary.app.index.ModelRevision

internal class OnnxGarmentParser(modelBytes: ByteBuffer, artifactSha256: String) : GarmentParser {
    init {
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
    }

    override val model = ModelRevision(
        "segformer-b0-upper-clothes",
        "$artifactSha256:7142c7f9d2be9ee39a47bd2091ad9d5fbe61bb1b:rgb-imagenet-bilinear512:argmax-softmax0.60",
        512,
    )
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(modelBytes, options)
    }

    override fun upperClothes(image: Bitmap): GarmentMask {
        val resized = Bitmap.createScaledBitmap(image, 512, 512, true)
        val input = try {
            val pixels = IntArray(512 * 512)
            resized.getPixels(pixels, 0, 512, 0, 0, 512, 512)
            val mean = floatArrayOf(.485f, .456f, .406f)
            val deviation = floatArrayOf(.229f, .224f, .225f)
            FloatArray(pixels.size * 3) { index ->
                val channel = index / pixels.size
                val value = pixels[index % pixels.size] shr (16 - 8 * channel) and 255
                (value / 255f - mean[channel]) / deviation[channel]
            }
        } finally {
            if (resized !== image) resized.recycle()
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 512, 512)).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result[0].value as Array<Array<Array<FloatArray>>>).single()
                require(logits.size == 18)
                val height = logits[0].size
                val width = logits[0][0].size
                val selected = BooleanArray(width * height) { index ->
                    val y = index / width
                    val x = index % width
                    val values = FloatArray(logits.size) { label -> logits[label][y][x] }
                    val best = values.indices.maxBy { values[it] }
                    val maximum = values[best]
                    val probability = 1f / values.sumOf { exp((it - maximum).toDouble()) }.toFloat()
                    best == 4 && probability >= .6f
                }
                return GarmentMask(width, height, selected)
            }
        }
    }

    override fun close() = session.close()
}
