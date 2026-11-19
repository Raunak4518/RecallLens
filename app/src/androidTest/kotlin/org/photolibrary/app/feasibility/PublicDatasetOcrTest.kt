package org.photolibrary.app.feasibility

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicDatasetOcrTest {
    @Test
    fun scoreHierTextRecognition() = compare("mlkit") {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Reader(
            version = "16.0.1",
            recognize = { bitmap ->
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS).text
            },
            close = recognizer::close,
        )
    }

    @Test
    fun scoreHierTextWithTesseract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue(
            "Prepare the pinned English Tesseract model before building the test APK",
            "eng.traineddata" in assets.list("tessdata").orEmpty(),
        )
        val directory = File(instrumentation.targetContext.cacheDir, "ocr-comparison")
        val data = File(directory, "tessdata/eng.traineddata")
        check(data.parentFile?.isDirectory == true || data.parentFile?.mkdirs() == true)
        assets.open("tessdata/eng.traineddata").use { source ->
            data.outputStream().use { destination -> source.copyTo(destination) }
        }
        compare("tesseract") {
            val engine = TessBaseAPI()
            if (!engine.init(directory.absolutePath, "eng", TessBaseAPI.OEM_LSTM_ONLY)) {
                engine.recycle()
                error("Tesseract could not initialize the bundled English model")
            }
            engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            Reader(
                version = engine.version,
                recognize = { bitmap ->
                    engine.setImage(bitmap)
                    try {
                        engine.getUTF8Text().orEmpty()
                    } finally {
                        engine.clear()
                    }
                },
                close = engine::recycle,
            )
        }
    }

    private fun compare(engine: String, createReader: () -> Reader) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue(
            "Stage the public HierText subset before building the test APK",
            "manifest.json" in assets.list("hiertext").orEmpty(),
        )
        val lines = assets.open("hiertext/manifest.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("lines")
        }
        assertEquals("Frozen line crop count", 30, lines.length())
        val initializationStart = SystemClock.elapsedRealtimeNanos()
        val reader = createReader()
        val initialization = SystemClock.elapsedRealtimeNanos() - initializationStart
        val elapsed = mutableListOf<Double>()
        val lineEdits = mutableListOf<Int>()
        val images = mutableSetOf<String>()
        var correct = 0
        var characters = 0
        var edits = 0
        var blankPredictions = 0
        try {
            for (index in 0 until lines.length()) {
                val row = lines.getJSONObject(index)
                val path = row.getString("file")
                val expected = normalize(row.getString("reference"))
                assertTrue("Missing ground truth for $path", expected.isNotEmpty())
                val bitmap = checkNotNull(assets.open("hiertext/$path").use { BitmapFactory.decodeStream(it) })
                try {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val result = reader.recognize(bitmap)
                    elapsed += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    val actual = normalize(result)
                    images += row.getString("image_id")
                    characters += expected.length
                    val errors = editDistance(expected, actual)
                    edits += errors
                    lineEdits += errors
                    if (expected == actual) correct += 1
                    if (actual.isEmpty()) blankPredictions += 1
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            reader.close()
        }
        assertEquals("Fixed source-image sample", 12, images.size)
        assertTrue("No labeled text to score", characters > 0)
        val report = JSONObject()
            .put("run_id", InstrumentationRegistry.getArguments().getString("ocrRunId", "manual"))
            .put("engine", engine)
            .put("version", reader.version)
            .put("images", images.size)
            .put("lines", lines.length())
            .put("exact", correct)
            .put("blanks", blankPredictions)
            .put("edits", edits)
            .put("characters", characters)
            .put("initialization_ms", initialization / 1_000_000.0)
            .put("recognition_ms", JSONArray(elapsed))
            .put("line_edits", JSONArray(lineEdits))
        Log.i("PhotoLibraryData", "OCR comparison $report")
    }

    private class Reader(val version: String, val recognize: (Bitmap) -> String, val close: () -> Unit)

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun editDistance(reference: String, actual: String): Int {
        var previous = IntArray(actual.length + 1) { it }
        for ((i, target) in reference.withIndex()) {
            val next = IntArray(actual.length + 1)
            next[0] = i + 1
            for ((j, observed) in actual.withIndex()) {
                next[j + 1] = minOf(
                    previous[j + 1] + 1,
                    next[j] + 1,
                    previous[j] + if (target == observed) 0 else 1,
                )
            }
            previous = next
        }
        return previous[actual.length]
    }
}
