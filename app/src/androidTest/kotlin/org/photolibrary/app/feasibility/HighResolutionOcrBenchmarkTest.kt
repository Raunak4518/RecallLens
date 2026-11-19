package org.photolibrary.app.feasibility

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.PhotoProcessor
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.library.decodePhoto

@RunWith(AndroidJUnit4::class)
class HighResolutionOcrBenchmarkTest {
    @Test
    fun comparesWholeImageWithOverlappingNativeTiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("highResolutionOcr") == "true")
        val context = instrumentation.targetContext
        val output = context.filesDir.resolve("high-resolution-ocr.json")
        output.delete()
        val directory = context.cacheDir.resolve("high-resolution-ocr-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val rows = JSONArray()
            LocalTextIndex.recognizer().use { recognizer ->
                val warmup = directory.resolve("warmup.jpg")
                renderDocument(warmup, 0)
                analyzeWhole(warmup, recognizer)
                for ((index, expected) in phrases.withIndex()) {
                    val image = directory.resolve("document-$index.jpg")
                    renderDocument(image, index)
                    val start = SystemClock.elapsedRealtimeNanos()
                    val whole: Analysis
                    val tiles: Analysis
                    if (index % 2 == 0) {
                        whole = analyzeWhole(image, recognizer)
                        tiles = analyzeTiles(image, recognizer)
                    } else {
                        tiles = analyzeTiles(image, recognizer)
                        whole = analyzeWhole(image, recognizer)
                    }
                    assertEquals(6, tiles.text.size)
                    val query = normalizePhotoText(expected)
                    val absentQueries = phrases.filterNot { it == expected }.map(::normalizePhotoText)
                    rows.put(JSONObject().put("id", index).put("target_query", expected)
                        .put("jpeg_bytes", image.length()).put("whole", whole.report(query, absentQueries))
                        .put("tile", tiles.report(query, absentQueries)).put("paired_ms", elapsed(start)))
                }
                output.writeText(JSONObject()
                    .put("complete", true).put("fixture", "generated document photographs at 3200 by 2400 pixels")
                    .put("model", recognizer.model.version).put("whole_max_edge", recognizer.model.maxImageEdge)
                    .put("tile_max_edge", tileEdge).put("minimum_overlap", overlap).put("results", rows).toString(2))
            }
        } finally {
            directory.listFiles().orEmpty().forEach(File::delete)
            directory.delete()
        }
    }

    private fun analyzeWhole(image: File, recognizer: PhotoProcessor<PhotoText>): Analysis {
        val start = SystemClock.elapsedRealtimeNanos()
        val bitmap = decodePhoto(ImageDecoder.createSource(image), recognizer.model.maxImageEdge,
            software = true, stillOnly = true)
        val bytes = bitmap.allocationByteCount
        val text = recognize(bitmap, recognizer)
        return Analysis(listOf(text), elapsed(start), bytes)
    }

    @Suppress("DEPRECATION")
    private fun analyzeTiles(image: File, recognizer: PhotoProcessor<PhotoText>): Analysis {
        val start = SystemClock.elapsedRealtimeNanos()
        val decoder = checkNotNull(BitmapRegionDecoder.newInstance(image.path, false))
        try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val texts = mutableListOf<PhotoText>()
            var bytes = 0
            for (top in offsets(decoder.height)) {
                for (left in offsets(decoder.width)) {
                    val rect = Rect(left, top, minOf(left + tileEdge, decoder.width), minOf(top + tileEdge, decoder.height))
                    val bitmap = checkNotNull(decoder.decodeRegion(rect, options))
                    bytes = maxOf(bytes, bitmap.allocationByteCount)
                    texts += recognize(bitmap, recognizer)
                }
            }
            return Analysis(texts, elapsed(start), bytes)
        } finally {
            decoder.recycle()
        }
    }

    private fun offsets(length: Int): List<Int> {
        if (length <= tileEdge) return listOf(0)
        val remaining = length - tileEdge
        val stride = tileEdge - overlap
        val intervals = (remaining + stride - 1) / stride
        return (0..intervals).map { index -> remaining * index / intervals }
    }

    private fun recognize(bitmap: Bitmap, recognizer: PhotoProcessor<PhotoText>): PhotoText = try {
        recognizer.process(bitmap)
    } finally {
        bitmap.recycle()
    }

    private fun renderDocument(file: File, index: Int) {
        val portrait = index % 2 != 0
        val width = if (portrait) 2400 else 3200
        val height = if (portrait) 3200 else 2400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xff423d38.toInt())
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
            canvas.rotate((index % 5 - 2) * 1.4f, width * 0.5f, height * 0.5f)
            ink.color = 0xfff6f2e7.toInt()
            canvas.drawRect(165f, 158f, width - 172f, height - 190f, ink)
            ink.color = Color.BLACK
            ink.textSize = 56f
            canvas.drawText(if (index % 3 == 0) "STATEMENT OF MARKS" else "FACILITIES SERVICE RECORD", 240f, 300f, ink)
            ink.textSize = 31f
            canvas.drawText("Reference | Description | Amount | Review", 242f, 405f, ink)
            for (row in 0 until 21) {
                val y = 502f + row * 64f
                ink.color = 0xff353a3f.toInt()
                canvas.drawText("${27 + row}  |  Standard record item  |  Reviewed", 245f, y, ink)
                ink.color = 0xffd6cfc0.toInt()
                canvas.drawLine(240f, y + 18f, width - 240f, y + 18f, ink)
            }
            ink.color = if (index % 3 == 1) 0xff62635f.toInt() else Color.BLACK
            ink.textSize = listOf(19f, 22f, 26f, 31f)[index % 4]
            val x = if (index % 3 == 0) 925f else width - 800f - (index % 4) * 70f
            val y = if (index % 3 == 2) 1124f else height - 296f - (index % 5) * 43f
            canvas.drawText(phrases[index], x, y, ink)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private data class Analysis(val text: List<PhotoText>, val wallMs: Double, val largestBitmapBytes: Int) {
        fun report(target: String, absent: List<String>) = JSONObject().put("target_hit", text.any { it.matches(target) })
            .put("absent_query_hits", absent.count { query -> text.any { it.matches(query) } })
            .put("wall_ms", wallMs).put("ocr_calls", text.size).put("max_bitmap_bytes", largestBitmapBytes)
    }

    private companion object {
        const val tileEdge = 1280
        const val overlap = 128
        val phrases = listOf("Banyan permit", "Maple clearance", "Orchid annex", "Harbor audit", "Indigo schedule",
            "Juniper transit", "Amber census", "Cedar extension", "Saffron ledger", "Lotus statement", "Willow docket", "Alpine dispatch")

        fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }
}
