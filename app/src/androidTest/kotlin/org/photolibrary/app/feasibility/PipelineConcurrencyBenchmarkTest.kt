package org.photolibrary.app.feasibility

import android.content.res.AssetManager
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.photolibrary.app.index.PhotoTextCodec
import org.photolibrary.app.library.decodePhoto
import org.photolibrary.app.semantic.ClipModel
import org.photolibrary.app.semantic.PhotoEmbedding

@RunWith(AndroidJUnit4::class)
class PipelineConcurrencyBenchmarkTest {
    @Test
    fun compareIndexingOrdersOnTheSamePhotos() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("pipelineConcurrency") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val fullGallery = arguments.getString("pipelineFullGallery") == "true"
        val reportName = if (fullGallery) "pipeline-full-gallery-results.json" else "pipeline-concurrency-results.json"
        if (fullGallery) instrumentation.targetContext.deleteFile(reportName)
        val fixture = fixture(assets, fullGallery)
        val clip = ClipModel(instrumentation.targetContext.assets)
        assertTrue("The production CLIP assets must be staged", clip.available)
        val modes = if (arguments.getString("pipelineConcurrentLane") == "true") {
            listOf(Mode.WINDOWED, Mode.SCENE_FIRST, Mode.CONCURRENT)
        } else listOf(Mode.WINDOWED, Mode.SCENE_FIRST)
        val runs = JSONArray()
        var reference: Pass? = null

        suspend fun measure(mode: Mode, session: String, ocr: PhotoProcessor<PhotoText>, scene: PhotoProcessor<PhotoEmbedding>) {
            Log.i(TAG, "Starting $session ${mode.name}: ${fixture.measured.size} photos")
            val pass = runPass(mode, fixture.measured, assets, ocr, scene, clip, sample = true, fullGallery = fullGallery)
            reference?.let { assertSameResults(it, pass) } ?: run { reference = pass }
            runs.put(pass.report(mode, session))
            Log.i(TAG, "$session ${mode.name}: ${pass.wallMs} ms on ${fixture.measured.size} photos")
        }

        try {
            clip.prepareTextSearch()
            val idleQueries = queries.map { query ->
                val start = now()
                clip.encodeQuery(query)
                msSince(start)
            }
            if (!fullGallery) {
                for (mode in modes) {
                    LocalTextIndex.recognizer().use { ocr ->
                        clip.imageProcessor().use { scene -> measure(mode, "fresh", ocr, scene) }
                    }
                }
            }
            LocalTextIndex.recognizer().use { ocr ->
                clip.imageProcessor().use { scene ->
                    for (mode in modes) runPass(mode, fixture.warmup, assets, ocr, scene, clip, sample = false)
                    val order = if (fullGallery) modes else modes + modes.asReversed()
                    for (mode in order) measure(mode, "warm", ocr, scene)
                }
            }
            val report = JSONObject()
                .put("full_gallery", fullGallery)
                .put("fixture_manifest_sha256", fixture.manifestSha256)
                .put("verified_photo_count", fixture.measured.size)
                .put("photo_ids", JSONArray(fixture.measured))
                .put("warmup_photo_ids", JSONArray(fixture.warmup))
                .put("hiertext_count", fixture.hiertextCount)
                .put("mixed_count", fixture.mixedCount)
                .put("build_model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.first())
                .put("visible_cpus", Runtime.getRuntime().availableProcessors())
                .put("window_photos", WINDOW)
                .put("idle_clip_text_stats", stats(idleQueries))
                .put("runs", runs)
                .put("scope", "Asset decode plus production adapter inference. No MediaStore, source validation, encryption, UI or durable scheduling.")
                .put("session_policy", if (fullGallery) {
                    "One timed warm pass per requested mode in report order. Warmup uses four photos from the measured set."
                } else "Fresh image/OCR adapters and counterbalanced warm passes. Same app process, prevalidated assets, and warmed CLIP text model.")
                .put("pss_scope", "Peak sampled own-process PSS in KiB. Sampling can miss peaks and includes the warmed models and test runtime.")
                .put("query_scope", if (fullGallery) {
                    "CLIP text encoding only during processing, with one probe roughly every 5 seconds up to 160 samples."
                } else "CLIP text encoding only during processing, with one probe roughly every 500 ms up to 24 samples.")
            if (!fullGallery) {
                report.put("fresh_sessions", "Fresh image/OCR adapters. Same app process, prevalidated assets, and warmed CLIP text model.")
            }
            instrumentation.targetContext.openFileOutput(reportName, 0).bufferedWriter().use {
                it.write(report.toString(2))
            }
        } finally {
            clip.releaseTextSession()
        }
    }

    private suspend fun runPass(
        mode: Mode,
        ids: List<String>,
        assets: AssetManager,
        ocr: PhotoProcessor<PhotoText>,
        scene: PhotoProcessor<PhotoEmbedding>,
        clip: ClipModel,
        sample: Boolean,
        fullGallery: Boolean = false,
    ): Pass = coroutineScope {
        val start = now()
        val interval = if (fullGallery) FULL_GALLERY_SAMPLE_INTERVAL else SAMPLE_INTERVAL
        val probeLimit = if (fullGallery) 160 else 24
        fun logProgress(stage: String, count: Int) {
            if (fullGallery && count % 100 == 0) {
                Log.i(TAG, "${mode.name} $stage $count/${ids.size} at ${msSince(start).toLong()} ms")
            }
        }
        val work = async(Dispatchers.IO) {
            val text = mutableListOf<Sample<PhotoText>>()
            val visual = mutableListOf<Sample<PhotoEmbedding>>()
            suspend fun textPass(batch: List<String>) {
                for (id in batch) {
                    currentCoroutineContext().ensureActive()
                    text += process(assets, id, ocr, start)
                    logProgress("OCR", text.size)
                }
            }
            suspend fun visualPass(batch: List<String>) {
                for (id in batch) {
                    currentCoroutineContext().ensureActive()
                    visual += process(assets, id, scene, start)
                    logProgress("CLIP image", visual.size)
                }
            }
            when (mode) {
                Mode.WINDOWED -> for (batch in ids.chunked(WINDOW)) {
                    textPass(batch)
                    visualPass(batch)
                }
                Mode.SCENE_FIRST -> {
                    visualPass(ids)
                    textPass(ids)
                }
                Mode.CONCURRENT -> coroutineScope {
                    val textWork = async(Dispatchers.IO) { textPass(ids) }
                    visualPass(ids)
                    textWork.await()
                }
            }
            Output(text, visual, msSince(start))
        }
        val probes = mutableListOf<Double>()
        val pss = mutableListOf<Int>()
        val queryProbe = if (sample) launch(Dispatchers.Default) {
            delay(interval)
            while (work.isActive && probes.size < probeLimit) {
                val queryStart = now()
                clip.encodeQuery(queries[probes.size % queries.size])
                if (work.isActive) probes += msSince(queryStart)
                delay(interval)
            }
        } else null
        val memoryProbe = if (sample) launch(Dispatchers.IO) {
            val memory = Debug.MemoryInfo()
            do {
                Debug.getMemoryInfo(memory)
                pss += memory.totalPss
                delay(interval)
            } while (work.isActive)
        } else null
        val output = try {
            work.await()
        } finally {
            queryProbe?.cancelAndJoin()
            memoryProbe?.cancelAndJoin()
        }
        Pass(output.text, output.visual, output.wallMs, probes, pss)
    }

    private fun <T> process(assets: AssetManager, id: String, processor: PhotoProcessor<T>, start: Long): Sample<T> {
        val decodeStart = now()
        val bitmap = decodePhoto(ImageDecoder.createSource(assets, "$ROOT/$id.jpg"), processor.model.maxImageEdge, software = true)
        val decodeMs = msSince(decodeStart)
        try {
            val modelStart = now()
            val output = processor.process(bitmap)
            return Sample(id, output, decodeMs, msSince(modelStart), msSince(start))
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertSameResults(reference: Pass, candidate: Pass) {
        assertEquals(reference.text.map { it.id }, candidate.text.map { it.id })
        assertEquals(reference.visual.map { it.id }, candidate.visual.map { it.id })
        for ((first, second) in reference.text.zip(candidate.text)) {
            assertEquals("OCR output on ${first.id}", PhotoTextCodec.encode(first.output), PhotoTextCodec.encode(second.output))
        }
        for ((first, second) in reference.visual.zip(candidate.visual)) {
            assertTrue("CLIP output on ${first.id}", first.output.similarity(second.output) >= .9999f)
        }
    }

    private fun fixture(assets: AssetManager, fullGallery: Boolean): Fixture {
        val manifestBytes = assets.open("$ROOT/manifest.json").use { it.readBytes() }
        val manifestSha256 = sha256(manifestBytes)
        val images = JSONObject(manifestBytes.toString(Charsets.UTF_8)).getJSONArray("images")
        val entries = (0 until images.length()).map(images::getJSONObject)
        val hierText = entries.filter { it.getString("cohort") == "hiertext" }
        val mixed = entries.filter { it.getString("cohort") == "mixed" }
        val (measured, warmup) = if (fullGallery) {
            assertEquals(FULL_GALLERY_MANIFEST, manifestSha256)
            assertEquals(300, entries.size)
            assertEquals(180, hierText.size)
            assertEquals(120, mixed.size)
            var textIndex = 0
            var mixedIndex = 0
            val ordered = List(entries.size) { index ->
                val textThroughHere = (index + 1) * hierText.size / entries.size
                if (textIndex < textThroughHere) hierText[textIndex++] else mixed[mixedIndex++]
            }
            ordered to ordered.take(4)
        } else {
            val paired = hierText.take(HALF_SAMPLE + 1).zip(mixed.take(HALF_SAMPLE + 1))
            assertEquals(HALF_SAMPLE + 1, paired.size)
            val ordered = paired.flatMap { (text, other) -> listOf(text, other) }
            ordered.dropLast(2) to ordered.takeLast(2)
        }
        val measuredIds = measured.map { it.getString("image_id") }
        assertEquals(measuredIds.size, measuredIds.toSet().size)
        for (row in (measured + warmup).distinctBy { it.getString("image_id") }) {
            val id = row.getString("image_id")
            val bytes = assets.open("$ROOT/$id.jpg").use { it.readBytes() }
            assertEquals("Fixture hash for $id", row.getString("sha256"), sha256(bytes))
        }
        return Fixture(measuredIds, warmup.map { it.getString("image_id") }, manifestSha256,
            measured.count { it.getString("cohort") == "hiertext" }, measured.count { it.getString("cohort") == "mixed" })
    }

    private data class Fixture(
        val measured: List<String>,
        val warmup: List<String>,
        val manifestSha256: String,
        val hiertextCount: Int,
        val mixedCount: Int,
    )
    private data class Sample<T>(val id: String, val output: T, val decodeMs: Double, val modelMs: Double, val completeMs: Double)
    private data class Output(val text: List<Sample<PhotoText>>, val visual: List<Sample<PhotoEmbedding>>, val wallMs: Double)
    private data class Pass(
        val text: List<Sample<PhotoText>>,
        val visual: List<Sample<PhotoEmbedding>>,
        val wallMs: Double,
        val queryMs: List<Double>,
        val pssKiB: List<Int>,
    ) {
        fun report(mode: Mode, session: String): JSONObject = JSONObject()
            .put("mode", mode.name.lowercase())
            .put("session", session)
            .put("wall_ms", wallMs)
            .put("photos_per_minute", text.size * 60_000.0 / wallMs)
            .put("ocr", stage(text))
            .put("scene", stage(visual))
            .put("ocr_nonempty_photos", text.count { it.output.lines.isNotEmpty() })
            .put("clip_text_during_indexing", stats(queryMs))
            .put("pss_samples", pssKiB.size)
            .put("sampled_peak_pss_kib", pssKiB.maxOrNull() ?: JSONObject.NULL)
    }

    private enum class Mode { WINDOWED, SCENE_FIRST, CONCURRENT }

    private companion object {
        const val ROOT = "selective-ocr"
        const val TAG = "PipelineBenchmark"
        const val HALF_SAMPLE = 12
        const val WINDOW = 8
        const val SAMPLE_INTERVAL = 500L
        const val FULL_GALLERY_SAMPLE_INTERVAL = 5_000L
        const val FULL_GALLERY_MANIFEST = "ce37091e12b3091883777476a71905048616174f2aaf3ba8a14cf5e5c170fadd"
        val queries = listOf("a dog on a beach", "a notice with printed lettering", "a red car parked outside", "a page of handwritten notes")

        fun now(): Long = SystemClock.elapsedRealtimeNanos()

        fun msSince(start: Long): Double = (now() - start) / 1_000_000.0

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun stage(samples: List<Sample<*>>): JSONObject {
            val milestones = JSONObject()
            for (count in listOf(1, 8, 16, 24, 100, 200, 300, samples.size).distinct().filter { it <= samples.size }) {
                milestones.put(count.toString(), samples[count - 1].completeMs)
            }
            return JSONObject()
                .put("milestones_ms", milestones)
                .put("decode", stats(samples.map { it.decodeMs }))
                .put("model", stats(samples.map { it.modelMs }))
        }

        fun stats(values: List<Double>): JSONObject {
            val sorted = values.sorted()
            val report = JSONObject().put("count", sorted.size).put("total_ms", sorted.sum())
            if (sorted.isEmpty()) return report
            fun percentile(fraction: Double) = sorted[ceil(sorted.size * fraction).toInt() - 1]
            return report.put("p50_ms", percentile(.50)).put("p95_ms", percentile(.95))
        }
    }
}
