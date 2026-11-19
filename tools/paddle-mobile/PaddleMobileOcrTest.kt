package org.photolibrary.app.feasibility

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.library.decodePhoto

@RunWith(AndroidJUnit4::class)
class PaddleMobileOcrTest {
    @Test
    fun compareMobilePhotoSearch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configuredTier = InstrumentationRegistry.getArguments().getString("modelTier")
        assumeTrue("Select an engine with the benchmark runner", configuredTier != null)
        val tier = checkNotNull(configuredTier)
        require(tier in setOf("mlkit", "tiny", "small"))
        val started = SystemClock.elapsedRealtimeNanos()
        val report = if (tier == "mlkit") {
            LocalTextIndex.recognizer().use { client ->
                scan(hybridElapsedMs(started), client::process)
            }
        } else {
            val fixtureAssets = instrumentation.context.assets
            val context = object : ContextWrapper(instrumentation.targetContext.applicationContext) {
                override fun getApplicationContext(): Context = this
                override fun getAssets(): AssetManager = fixtureAssets
            }
            check(OpenCVUtils.init(context)) { "OpenCV is unavailable on this test device" }
            val root = "paddle-mobile/$tier"
            val config = PaddleOCRConfig(detLimitSideLen = 1600, detLimitType = "max", detMaxSideLimit = 1600,
                detThresh = .2f, detBoxThresh = if (tier == "tiny") .4f else .45f, detUnclipRatio = 1.4f)
            val threads = 2
            val client = PaddleOCR.create(context, config, EngineConfig(numThreads = threads),
                "$root/det.onnx", "$root/rec.onnx", "$root/rec.yml")
            try {
                scan(hybridElapsedMs(started)) { bitmap ->
                    PhotoText(client.recognize(bitmap).results.map { TextLine(it.text, "und", null) })
                }.put("threads", threads)
            } finally {
                client.release()
            }
        }
        instrumentation.targetContext.openFileOutput("hybrid-ocr-paddle-mobile.json", 0).bufferedWriter().use {
            it.write(report.toString())
        }
    }

    private suspend fun scan(setupMs: Double, recognize: suspend (Bitmap) -> PhotoText): JSONObject {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val manifest = hybridManifest()
        val photos = manifest.getJSONArray("images")
        val index = linkedMapOf<String, PhotoText>()
        val measurements = JSONArray()
        for (ordinal in 0 until photos.length()) {
            val photo = photos.getJSONObject(ordinal)
            val id = photo.getString("id")
            val asset = "$HYBRID_ASSETS/${photo.getString("file")}"
            val decodeStarted = SystemClock.elapsedRealtimeNanos()
            val bitmap = decodePhoto(ImageDecoder.createSource(assets, asset), 1600, software = true)
            val decodeMs = hybridElapsedMs(decodeStarted)
            try {
                val ocrStarted = SystemClock.elapsedRealtimeNanos()
                index[id] = recognize(bitmap)
                val ocrMs = hybridElapsedMs(ocrStarted)
                measurements.put(JSONObject().put("id", id).put("ocr_ms", ocrMs).put("decode_ms", decodeMs))
            } finally {
                bitmap.recycle()
            }
        }
        return JSONObject().put("images", measurements).put("queries", hybridMatches(manifest, index)).put("setup_ms", setupMs)
    }
}
