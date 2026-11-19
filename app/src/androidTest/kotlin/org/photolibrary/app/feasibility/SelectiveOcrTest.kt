package org.photolibrary.app.feasibility

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.library.decodePhoto

@RunWith(AndroidJUnit4::class)
class SelectiveOcrTest {
    @Test
    fun compareEveryPhotoWithPreviewSelection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("selectiveOcr") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val root = "selective-ocr"
        val manifest = assets.open("$root/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val images = manifest.getJSONArray("images")
        assertEquals(300, images.length())
        val all = linkedMapOf<String, PhotoText>()
        val selected = linkedMapOf<String, PhotoText>()
        val samples = JSONArray()
        LocalTextIndex.recognizer().use { recognizer ->
            fun photo(id: String, edge: Int) =
                decodePhoto(ImageDecoder.createSource(assets, "$root/$id.jpg"), edge, software = true)

            fun recognize(id: String): PhotoText {
                val bitmap = photo(id, recognizer.model.maxImageEdge)
                return try {
                    recognizer.process(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }

            for (index in 0 until 4) {
                val id = images.getJSONObject(index).getString("image_id")
                recognize(id)
                val preview = photo(id, PREVIEW_EDGE)
                try {
                    hasTextLikeEdges(preview)
                } finally {
                    preview.recycle()
                }
            }

            for (index in 0 until images.length()) {
                val id = images.getJSONObject(index).getString("image_id")
                val sample = JSONObject().put("id", id)
                fun exhaustive() {
                    val start = SystemClock.elapsedRealtimeNanos()
                    all[id] = recognize(id)
                    sample.put("exhaustive_ms", elapsedMs(start))
                }
                fun selective() {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val preview = photo(id, PREVIEW_EDGE)
                    val keep = try {
                        hasTextLikeEdges(preview)
                    } finally {
                        preview.recycle()
                    }
                    sample.put("gate_ms", elapsedMs(start))
                    if (keep) selected[id] = recognize(id)
                    sample.put("selected", keep).put("selective_ms", elapsedMs(start))
                }
                if (index % 2 == 0) {
                    exhaustive()
                    selective()
                } else {
                    selective()
                    exhaustive()
                }
                samples.put(sample)
                if ((index + 1) % 50 == 0) Log.i("SelectiveOcr", "Compared ${index + 1} of ${images.length()} photos")
            }
        }

        val queries = manifest.getJSONArray("queries")
        assertEquals(360, queries.length())
        val outcomes = JSONArray()
        for (index in 0 until queries.length()) {
            val query = queries.getJSONObject(index)
            val text = normalizePhotoText(query.getString("text"))
            val before = all.filterValues { it.matches(text) }.keys
            val after = selected.filterValues { it.matches(text) }.keys
            assertEquals(before.intersect(selected.keys), after)
            outcomes.put(JSONObject(query.toString())
                .put("exhaustive_matches", JSONArray(before))
                .put("selective_matches", JSONArray(after)))
        }
        assertTrue(all.keys.containsAll(selected.keys))
        val report = JSONObject().put("images", samples).put("queries", outcomes)
        instrumentation.targetContext.openFileOutput("selective-ocr-results.json", 0).bufferedWriter().use {
            it.write(report.toString())
        }
    }
}

private const val PREVIEW_EDGE = 320
private const val WINDOW = 40
private const val CONTRAST = 32
private const val MIN_TRANSITIONS_PER_DIRECTION = 4

private fun hasTextLikeEdges(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= WINDOW || height < 3) return true
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val brightness = IntArray(pixels.size) { index ->
        val rgb = pixels[index]
        (77 * (rgb shr 16 and 255) + 150 * (rgb shr 8 and 255) + 29 * (rgb and 255)) shr 8
    }
    for (row in 0 until height - 2 step 2) {
        for (column in 0 until width - WINDOW step WINDOW / 2) {
            if (hasTransitions(brightness, row * width + column) &&
                hasTransitions(brightness, (row + 2) * width + column)) return true
        }
    }
    return false
}

private fun hasTransitions(brightness: IntArray, start: Int): Boolean {
    var rising = 0
    var falling = 0
    for (index in start until start + WINDOW) {
        val edge = brightness[index + 1] - brightness[index]
        if (edge >= CONTRAST) rising++
        if (edge <= -CONTRAST) falling++
    }
    return rising >= MIN_TRANSITIONS_PER_DIRECTION && falling >= MIN_TRANSITIONS_PER_DIRECTION
}

private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
