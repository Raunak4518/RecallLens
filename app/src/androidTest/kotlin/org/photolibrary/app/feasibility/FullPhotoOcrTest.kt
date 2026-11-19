package org.photolibrary.app.feasibility

import android.graphics.ImageDecoder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.library.decodePhoto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullPhotoOcrTest {
    @Test
    fun searchFullHierTextPhotos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val root = "hiertext-full"
        assumeTrue("Prepare the full-photo HierText benchmark", "manifest.json" in assets.list(root).orEmpty())
        val manifest = assets.open("$root/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val images = manifest.getJSONArray("images")
        val queries = manifest.getJSONArray("queries")
        val index = sortedMapOf<String, PhotoText>()
        val predictions = JSONArray()
        val start = SystemClock.elapsedRealtimeNanos()
        val recognizer = LocalTextIndex.recognizer()
        val setupMs = elapsedMs(start)
        recognizer.use {
            for (position in 0 until images.length()) {
                val id = images.getString(position)
                val decodeStart = SystemClock.elapsedRealtimeNanos()
                val source = ImageDecoder.createSource(assets, "$root/$id.jpg")
                val bitmap = decodePhoto(source, recognizer.model.maxImageEdge, software = true)
                val decodeMs = elapsedMs(decodeStart)
                try {
                    val ocrStart = SystemClock.elapsedRealtimeNanos()
                    val result = recognizer.process(bitmap)
                    val ocrMs = elapsedMs(ocrStart)
                    index[id] = result
                    predictions.put(JSONObject().put("id", id).put("width", bitmap.width).put("height", bitmap.height)
                        .put("decode_ms", decodeMs).put("ocr_ms", ocrMs).put("lines", JSONArray(result.lines.map { line ->
                            val box = line.bounds
                            JSONObject().put("text", line.text).put("box", box?.let {
                                JSONArray(listOf(it.left, it.top, it.right, it.bottom))
                            } ?: JSONObject.NULL)
                        })))
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val results = JSONArray()
        for (position in 0 until queries.length()) {
            val query = queries.getJSONObject(position)
            val text = normalizePhotoText(query.getString("text"))
            val start = SystemClock.elapsedRealtimeNanos()
            val matches = index.filterValues { photo -> photo.matches(text) }.keys
            val searchMs = elapsedMs(start)
            results.put(JSONObject().put("id", query.getString("id")).put("matches", JSONArray(matches)).put("search_ms", searchMs))
        }
        val output = JSONObject().put("adapter_setup_ms", setupMs).put("images", predictions).put("queries", results)
        instrumentation.targetContext.openFileOutput("hiertext-full-results.json", 0).bufferedWriter().use {
            it.write(output.toString())
        }
    }

    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

}
