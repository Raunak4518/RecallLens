package org.photolibrary.app.feasibility

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipEncoderTest {
    @Test
    fun quantizedClipMatchesTwoSyntheticQueries() {
        assumeTrue(
            "Stage the pinned CLIP model assets and opt in with clipFixtures=true",
            InstrumentationRegistry.getArguments().getString("clipFixtures") == "true",
        )
        val fixtureDirectory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "g0-clip",
        )
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            val start = SystemClock.elapsedRealtime()
            environment.createSession(fixtureDirectory.resolve("vision_model_quantized.onnx").path, options).use { vision ->
                environment.createSession(fixtureDirectory.resolve("text_model_quantized.onnx").path, options).use { text ->
                    val loadTime = SystemClock.elapsedRealtime() - start
                    val firstInference = SystemClock.elapsedRealtime()
                    val red = embedImage(environment, vision, fixtureDirectory.resolve("red.png"))
                    val blue = embedImage(environment, vision, fixtureDirectory.resolve("blue.png"))
                    val redQuery = embedText(environment, text, longArrayOf(49406, 320, 736, 3999, 525, 320, 1579, 5994, 49407))
                    val blueQuery = embedText(environment, text, longArrayOf(49406, 320, 1746, 7117, 525, 320, 1579, 5994, 49407))
                    val scores = listOf(
                        cosine(redQuery, red), cosine(redQuery, blue),
                        cosine(blueQuery, red), cosine(blueQuery, blue),
                    )
                    assertTrue(scores.all { it.isFinite() })
                    assertTrue(scores[0] > scores[1] && scores[3] > scores[2])
                    Log.i(
                        "PhotoLibraryG0",
                        "CLIP two-query smoke: load ${loadTime} ms, first pass ${SystemClock.elapsedRealtime() - firstInference} ms, scores $scores",
                    )
                }
            }
        }
    }

    private fun embedImage(environment: OrtEnvironment, session: OrtSession, fixture: File): FloatArray {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(fixture.path))
        val pixels = IntArray(224 * 224)
        try {
            assertEquals(224, bitmap.width)
            assertEquals(224, bitmap.height)
            bitmap.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        } finally {
            bitmap.recycle()
        }
        val normalized = FloatArray(3 * pixels.size)
        for ((index, pixel) in pixels.withIndex()) {
            normalized[index] = (Color.red(pixel) / 255f - 0.48145466f) / 0.26862954f
            normalized[index + pixels.size] = (Color.green(pixel) / 255f - 0.4578275f) / 0.26130258f
            normalized[index + 2 * pixels.size] = (Color.blue(pixel) / 255f - 0.40821073f) / 0.27577711f
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(normalized), longArrayOf(1, 3, 224, 224)).use { input ->
            embedding(session, "pixel_values", input)
        }
    }

    private fun embedText(environment: OrtEnvironment, session: OrtSession, tokens: LongArray): FloatArray =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())).use { input ->
            embedding(session, "input_ids", input)
        }

    private fun embedding(session: OrtSession, name: String, input: OnnxTensor): FloatArray =
        session.run(mapOf(name to input)).use { result ->
            ((result[0].value as Array<*>).single() as FloatArray).also {
                assertEquals(512, it.size)
            }
        }

    private fun cosine(left: FloatArray, right: FloatArray): Double {
        val numerator = left.indices.sumOf { left[it].toDouble() * right[it] }
        val leftLength = sqrt(left.sumOf { it.toDouble() * it })
        val rightLength = sqrt(right.sumOf { it.toDouble() * it })
        return numerator / (leftLength * rightLength)
    }
}
