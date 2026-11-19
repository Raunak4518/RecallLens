package org.photolibrary.app.feasibility

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoProcessor
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.library.decodePhoto

@RunWith(AndroidJUnit4::class)
class LargeOcrComparisonTest {
    @Test
    fun compareMlKitAndTinyOnFrozenGallery() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeOcr") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val manifestBytes = assets.open("$ROOT/manifest.json").use { it.readBytes() }
        assertEquals(MANIFEST_SHA, sha256(manifestBytes))
        val manifest = JSONObject(manifestBytes.decodeToString())
        val images = manifest.getJSONArray("images")
        val queries = manifest.getJSONArray("queries")
        assertEquals(300, images.length())
        assertEquals(360, queries.length())
        val expectedModels = mapOf(
            "det.onnx" to "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8",
            "rec.onnx" to "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6",
            "rec.yml" to "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1",
        )
        for ((file, expected) in expectedModels) {
            assertEquals(expected, assets.open("paddle-mobile/tiny/$file").use { sha256(it.readBytes()) })
        }
        val context = object : ContextWrapper(instrumentation.targetContext.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getAssets(): AssetManager = assets
        }
        check(OpenCVUtils.init(context))
        val mlKitSetupStarted = SystemClock.elapsedRealtimeNanos()
        LocalTextIndex.recognizer().use { mlKit ->
            val mlKitSetupMs = elapsedMs(mlKitSetupStarted)
            val tinySetupStarted = SystemClock.elapsedRealtimeNanos()
            val config = PaddleOCRConfig(detLimitSideLen = 1600, detLimitType = "max", detMaxSideLimit = 1600,
                detThresh = .2f, detBoxThresh = .4f, detUnclipRatio = 1.4f)
            val client = PaddleOCR.create(context, config, EngineConfig(numThreads = 2),
                "paddle-mobile/tiny/det.onnx", "paddle-mobile/tiny/rec.onnx", "paddle-mobile/tiny/rec.yml")
            TinyPhotoText(client).use { tiny ->
                val setup = JSONObject().put("mlkit_ms", mlKitSetupMs).put("tiny_ms", elapsedMs(tinySetupStarted))
                val engines = linkedMapOf<String, PhotoProcessor<PhotoText>>("mlkit" to mlKit, "tiny" to tiny)
                fun decode(id: String) = decodePhoto(ImageDecoder.createSource(assets, "$ROOT/$id.jpg"), 1600, software = true)
                val warmupStarted = SystemClock.elapsedRealtimeNanos()
                repeat(4) { ordinal ->
                    val bitmap = decode(images.getJSONObject(ordinal).getString("image_id"))
                    try {
                        engines.values.forEach { it.process(bitmap) }
                    } finally {
                        bitmap.recycle()
                    }
                }
                val warmupMs = elapsedMs(warmupStarted)
                val indexes = engines.keys.associateWith { linkedMapOf<String, PhotoText>() }
                val samples = JSONArray()
                for (ordinal in 0 until images.length()) {
                    val image = images.getJSONObject(ordinal)
                    val id = image.getString("image_id")
                    assertEquals(image.getString("sha256"), assets.open("$ROOT/$id.jpg").use { sha256(it.readBytes()) })
                    val decodeStarted = SystemClock.elapsedRealtimeNanos()
                    val bitmap = decode(id)
                    val sample = JSONObject().put("id", id).put("decode_ms", elapsedMs(decodeStarted))
                        .put("width", bitmap.width).put("height", bitmap.height)
                    try {
                        val ordered = engines.entries.toList().let { if (ordinal % 2 == 0) it else it.reversed() }
                        for ((name, engine) in ordered) {
                            val started = SystemClock.elapsedRealtimeNanos()
                            val text = engine.process(bitmap)
                            val ocrMs = elapsedMs(started)
                            indexes.getValue(name)[id] = text
                            sample.put(name, JSONObject().put("ocr_ms", ocrMs).put("complete", text.complete)
                                .put("lines", JSONArray(text.lines.map { it.text })))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    samples.put(sample)
                    if ((ordinal + 1) % 25 == 0) Log.i("LargeOcr", "Compared ${ordinal + 1} of ${images.length()}")
                }
                val outcomes = JSONArray()
                for (ordinal in 0 until queries.length()) {
                    val query = queries.getJSONObject(ordinal)
                    val normalized = normalizePhotoText(query.getString("text"))
                    val row = JSONObject(query.toString())
                    for ((name, index) in indexes) {
                        row.put(name, JSONArray(index.filterValues { it.matches(normalized) }.keys))
                    }
                    outcomes.put(row)
                }
                val report = JSONObject().put("manifest_sha256", MANIFEST_SHA).put("setup", setup)
                    .put("warmup_ms", warmupMs).put("tiny_threads", 2)
                    .put("images", samples).put("queries", outcomes)
                instrumentation.targetContext.openFileOutput("large-ocr-comparison.json", 0).bufferedWriter().use {
                    it.write(report.toString())
                }
            }
        }
    }

    private companion object {
        const val ROOT = "selective-ocr"
        const val MANIFEST_SHA = "ce37091e12b3091883777476a71905048616174f2aaf3ba8a14cf5e5c170fadd"
    }
}

private class TinyPhotoText(private val client: PaddleOCR) : PhotoProcessor<PhotoText> {
    override val model = ModelRevision("pp-ocrv6-tiny", "pinned-android-bgr-1", 1600)

    override fun process(image: Bitmap): PhotoText {
        val result = runBlocking { client.recognize(image).results }
        val lines = mutableListOf<TextLine>()
        var remaining = 16_384
        var complete = true
        for (line in result) {
            if (lines.size >= 256 || line.text.length > remaining) complete = false
            if (lines.size >= 256 || remaining == 0) break
            val text = line.text.take(remaining).trim()
            if (text.isEmpty()) continue
            lines += TextLine(text, "und", null)
            remaining -= text.length
        }
        return PhotoText(lines, complete)
    }

    override fun close() = runBlocking { client.release() }
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
