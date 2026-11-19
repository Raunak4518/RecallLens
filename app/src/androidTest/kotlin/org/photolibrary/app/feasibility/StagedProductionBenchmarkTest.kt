package org.photolibrary.app.feasibility

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.index.IndexingControl
import org.photolibrary.app.index.IndexingProgress
import org.photolibrary.app.index.LocalIndexCoordinator
import org.photolibrary.app.index.LocalPhotoIndexer
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoIndexSession
import org.photolibrary.app.index.PhotoProcessor
import org.photolibrary.app.index.PhotoProcessorFactory
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.PhotoTextCodec
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.library.LibrarySources
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.library.decodePhoto
import org.photolibrary.app.semantic.ClipModel
import org.photolibrary.app.semantic.PhotoEmbedding
import org.photolibrary.app.semantic.closestPhotos

@RunWith(AndroidJUnit4::class)
class StagedProductionBenchmarkTest {
    @Test
    fun indexesThePublicGalleryAndReopensBothEncryptedSearchCaches() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("stagedProductionBenchmark") == "true")
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        context.deleteFile(REPORT)
        val fixture = readFixture(assets)
        val alias = "staged-production-${UUID.randomUUID()}"
        val directory = context.noBackupFilesDir.resolve(alias)
        val clip = ClipModel(context.assets)
        assertTrue("The production CLIP assets must be staged", clip.available)
        try {
            val report = withContext(Dispatchers.IO) {
                val counters = Counters()
                val byUri = fixture.photos.associateBy { it.photo.uri }
                val photos = fixture.photos.map(FixturePhoto::photo)
                val inputs = photos.map { IndexPhoto(it.uri, it.sourceRevision) }
                val all = byUri.keys
                val textRevision = LocalTextIndex.recognizer().use { it.model }
                fun indexes(storage: EncryptedPhotoIndex): Indexes {
                    fun <T : Any> index(revision: ModelRevision, create: () -> PhotoProcessor<T>, lane: ModelCounters,
                        open: () -> PhotoIndexSession<T>,
                    ) = LocalPhotoIndexer(loadImage = { uri, size ->
                        val before = now()
                        val asset = byUri.getValue(uri).id
                        val image = decodePhoto(ImageDecoder.createSource(assets, "$ROOT/$asset.jpg"), size, software = true)
                        lane.decodes++
                        lane.decodeMs += msSince(before)
                        image
                    }, processorFactory = PhotoProcessorFactory(revision) { lane.wrap(create()) },
                        openIndex = open, readRevision = { byUri[it]?.photo?.sourceRevision })
                    return Indexes(
                        index(textRevision, LocalTextIndex::recognizer, counters.text, storage::open),
                        index(clip.revision, clip::imageProcessor, counters.visual, storage::openEmbeddings),
                    )
                }

                fun coordinator(storage: EncryptedPhotoIndex, indexes: Indexes) = LocalIndexCoordinator(
                    IndexingControl(storage),
                    { counters.listCalls++; LibrarySources(GalleryAccess.ALL, emptyList(), photos) },
                    indexes.text, { indexes.visual }, { "decoder-v1:$textRevision:${clip.revision}" },
                    batchSize = 8, visualBatchSize = 32,
                )

                val storage = EncryptedPhotoIndex(directory, alias)
                val initial = indexes(storage)
                val runner = coordinator(storage, initial)
                val batches = mutableListOf<Batch>()
                val start = now()
                var previous = runner.progress.value
                var more = true
                while (more) {
                    more = runner.runBatch()
                    val progress = runner.progress.value
                    batches += Batch(msSince(start), progress,
                        coverage(progress.visualUpdated.keys, byUri), coverage(progress.textUpdated.keys, byUri))
                    logMilestones("scene", previous.visualReady, progress.visualReady, start)
                    logMilestones("OCR", previous.textReady, progress.textReady, start)
                    previous = progress
                    check(batches.size <= 65) { "The coordinator did not finish the 300-photo fixture" }
                }
                val totalMs = batches.last().elapsedMs
                val indexCalls = counters.listCalls
                assertTrue(previous.checkpoint.finished)
                assertEquals(0, previous.checkpoint.failed)
                assertEquals(all, previous.visualUpdated.keys)
                assertEquals(all, previous.textUpdated.keys)
                assertEquals(300, previous.visualReady)
                assertEquals(300, previous.textReady)
                assertEquals(300, counters.visual.decodes)
                assertEquals(300, counters.text.decodes)
                assertEquals(300, counters.visual.modelCalls)
                assertEquals(300, counters.text.modelCalls)
                assertTrue(batches.takeWhile { it.progress.visualReady < 300 }.all { it.progress.textReady == 0 })
                assertEquals(8, batches.first().progress.visualReady)
                assertEquals(11, counters.visual.modelOpens)
                assertEquals(38, counters.text.modelOpens)
                val textReport = counters.text.report()
                val visualReport = counters.visual.report()
                val archiveBytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val databaseBytes = directory.resolve("catalog.db").length()
                assertTrue(databaseBytes > 0L)

                val beforeReopen = counters.workToken()
                val reference = Snapshot(initial.text.readCached(inputs, all), initial.visual.readCached(inputs, all))
                val reopenedStorage = EncryptedPhotoIndex(directory, alias)
                val reopened = indexes(reopenedStorage)
                val restoredRunner = coordinator(reopenedStorage, reopened)
                val checkpointStart = now()
                assertFalse(restoredRunner.runBatch())
                val checkpointMs = msSince(checkpointStart)
                assertEquals(previous.checkpoint, restoredRunner.progress.value.checkpoint)
                assertEquals(300, restoredRunner.progress.value.textReady)
                assertEquals(300, restoredRunner.progress.value.visualReady)
                val restoreStart = now()
                val restored = Snapshot(reopened.text.readCached(inputs, all), reopened.visual.readCached(inputs, all))
                val restoreMs = msSince(restoreStart)
                assertEquals(beforeReopen, counters.workToken())
                assertEquals(all, reference.text.keys)
                assertEquals(all, reference.visual.keys)
                assertEquals(all, restored.text.keys)
                assertEquals(all, restored.visual.keys)
                for (uri in all) {
                    assertEquals(PhotoTextCodec.encode(reference.text.getValue(uri)), PhotoTextCodec.encode(restored.text.getValue(uri)))
                    assertTrue(reference.visual.getValue(uri).similarity(restored.visual.getValue(uri)) >= .9999f)
                }
                val ocrStart = now()
                val ocrResults = fixture.queries.map { query -> restored.text.filterValues { it.matches(query.phrase) }.keys }
                val ocrSearchMs = msSince(ocrStart)
                fixture.queries.forEachIndexed { index, query ->
                    assertEquals(reference.text.filterValues { it.matches(query.phrase) }.keys, ocrResults[index])
                }
                val rememberedHits = fixture.queries.indices.count { fixture.queries[it].target in ocrResults[it] }
                assertTrue(rememberedHits > 0)
                clip.prepareTextSearch()
                val sceneQueries = listOf("a dog on a beach", "a sign with text", "a red car", "a page of notes")
                val rankings = JSONArray()
                for (query in sceneQueries) {
                    val before = now()
                    val queryVector = clip.encodeQuery(query)
                    val encodeMs = msSince(before)
                    val rankStart = now()
                    val ranked = closestPhotos(queryVector, restored.visual, 5)
                    val rankingMs = msSince(rankStart)
                    assertEquals(5, ranked.size)
                    assertEquals(closestPhotos(queryVector, reference.visual, 5), ranked)
                    rankings.put(JSONObject().put("query", query).put("text_encode_ms", encodeMs).put("ranking_ms", rankingMs)
                        .put("top5_ids", JSONArray(ranked.map { byUri.getValue(it).id })))
                }
                assertEquals(beforeReopen, counters.workToken())

                JSONObject()
                    .put("status", "complete")
                    .put("scope", "Cold production coordinator/decoder/model/index on pinned public assets, with injected source metadata and direct batch calls. Excludes MediaStore and WorkManager enqueue/UI delays.")
                    .put("manifest_sha256", fixture.manifestSha256)
                    .put("photos", all.size)
                    .put("cohorts", JSONObject().put("hiertext", 180).put("mixed", 120))
                    .put("photo_ids_in_order", JSONArray(fixture.photos.map(FixturePhoto::id)))
                    .put("fixture_bytes", fixture.originalBytes)
                    .put("fixture_hash_validation_ms", fixture.validationMs)
                    .put("device_model", Build.MODEL)
                    .put("abi", Build.SUPPORTED_ABIS.first())
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("ocr_revision", textRevision.toString())
                    .put("scene_revision", clip.revision.toString())
                    .put("batches", JSONArray(batches.map(Batch::report)))
                    .put("scene_milestones_ms", milestones(batches) { it.visualReady })
                    .put("ocr_milestones_ms", milestones(batches) { it.textReady })
                    .put("wall_ms", totalMs)
                    .put("source_snapshot_calls", indexCalls)
                    .put("ocr", textReport)
                    .put("scene", visualReport)
                    .put("encrypted_database_bytes", databaseBytes)
                    .put("encrypted_database_and_key_bytes", archiveBytes)
                    .put("reopen_finished_checkpoint_ms", checkpointMs)
                    .put("reopen_load_both_indexes_ms", restoreMs)
                    .put("reopen_original_decodes", 0)
                    .put("reopen_image_or_ocr_model_calls", 0)
                    .put("remembered_text_queries", fixture.queries.size)
                    .put("remembered_text_target_hits", rememberedHits)
                    .put("remembered_text_total_search_ms", ocrSearchMs)
                    .put("warm_scene_search", rankings)
            }
            context.openFileOutput(REPORT, 0).bufferedWriter().use { it.write(report.toString(2)) }
        } finally {
            clip.releaseTextSession()
            try {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun readFixture(assets: AssetManager): Fixture {
        val start = now()
        val bytes = assets.open("$ROOT/manifest.json").use { it.readBytes() }
        val digest = sha256(bytes)
        assertEquals(MANIFEST, digest)
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        val images = manifest.getJSONArray("images")
        val rows = (0 until images.length()).map(images::getJSONObject)
        val text = rows.filter { it.getString("cohort") == "hiertext" }
        val mixed = rows.filter { it.getString("cohort") == "mixed" }
        assertEquals(300, rows.size)
        assertEquals(180, text.size)
        assertEquals(120, mixed.size)
        var textIndex = 0
        var mixedIndex = 0
        var size = 0L
        val photos = List(rows.size) { position ->
            val throughHere = (position + 1) * text.size / rows.size
            val row = if (textIndex < throughHere) text[textIndex++] else mixed[mixedIndex++]
            val id = row.getString("image_id")
            val uri = Uri.parse("content://staged-production/photos/${position.toString().padStart(3, '0')}")
            val image = assets.open("$ROOT/$id.jpg").use { it.readBytes() }
            size += image.size
            val revision = sha256(image)
            assertEquals("Source SHA-256 for $id", row.getString("sha256"), revision)
            FixturePhoto(SelectedPhoto(uri, "$id.jpg", PhotoOrigin.GALLERY, revision), id, row.getString("cohort"))
        }
        val uriById = photos.associate { it.id to it.photo.uri }
        assertEquals(photos.size, uriById.size)
        val queries = manifest.getJSONArray("queries")
        assertEquals(360, queries.length())
        val remembered = List(queries.length()) { index ->
            val row = queries.getJSONObject(index)
            RememberedQuery(uriById.getValue(row.getString("target")), normalizePhotoText(row.getString("text")))
        }
        return Fixture(photos, remembered, digest, size, msSince(start))
    }

    private data class FixturePhoto(val photo: SelectedPhoto, val id: String, val cohort: String)
    private data class RememberedQuery(val target: Uri, val phrase: String)
    private data class Fixture(val photos: List<FixturePhoto>, val queries: List<RememberedQuery>, val manifestSha256: String,
        val originalBytes: Long, val validationMs: Double)
    private data class Indexes(val text: LocalPhotoIndexer<PhotoText>, val visual: LocalPhotoIndexer<PhotoEmbedding>)
    private data class Snapshot(val text: Map<Uri, PhotoText>, val visual: Map<Uri, PhotoEmbedding>)

    private data class Batch(val elapsedMs: Double, val progress: IndexingProgress, val scene: JSONObject, val ocr: JSONObject) {
        fun report() = JSONObject().put("elapsed_ms", elapsedMs).put("next_stage", progress.checkpoint.stage.name)
            .put("next_cursor", progress.checkpoint.cursor).put("failed_operations", progress.checkpoint.failed)
            .put("visual_ready", progress.visualReady).put("text_ready", progress.textReady)
            .put("scene_cohorts", scene).put("ocr_cohorts", ocr)
    }

    private class ModelCounters {
        var decodes = 0
        var decodeMs = 0.0
        var modelOpens = 0
        var modelCalls = 0
        var modelMs = 0.0

        fun <T> wrap(engine: PhotoProcessor<T>): PhotoProcessor<T> {
            modelOpens++
            return object : PhotoProcessor<T> {
                override val model = engine.model

                override fun process(image: Bitmap): T {
                    val before = now()
                    modelCalls++
                    return engine.process(image).also { modelMs += msSince(before) }
                }

                override fun close() = engine.close()
            }
        }

        fun report() = JSONObject().put("decodes", decodes).put("decode_wall_ms", decodeMs)
            .put("model_adapter_opens", modelOpens).put("model_calls", modelCalls).put("model_wall_ms", modelMs)
    }

    private class Counters {
        val text = ModelCounters()
        val visual = ModelCounters()
        var listCalls = 0

        fun workToken() = listOf(text.decodes, text.modelOpens, text.modelCalls, visual.decodes, visual.modelOpens, visual.modelCalls)
    }

    private companion object {
        const val ROOT = "selective-ocr"
        const val MANIFEST = "ce37091e12b3091883777476a71905048616174f2aaf3ba8a14cf5e5c170fadd"
        const val REPORT = "staged-production-results.json"
        const val TAG = "StagedProduction"

        fun now(): Long = SystemClock.elapsedRealtimeNanos()

        fun msSince(start: Long): Double = (now() - start) / 1_000_000.0

        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun coverage(uris: Set<Uri>, photos: Map<Uri, FixturePhoto>): JSONObject {
            val rich = uris.count { photos.getValue(it).cohort == "hiertext" }
            return JSONObject().put("hiertext", rich).put("mixed", uris.size - rich)
        }

        fun milestones(batches: List<Batch>, ready: (IndexingProgress) -> Int): JSONObject = JSONObject().apply {
            for (count in listOf(1, 8, 16, 24, 100, 200, 300)) {
                val batch = batches.first { ready(it.progress) >= count }
                put(count.toString(), JSONObject().put("wall_ms", batch.elapsedMs).put("ready", ready(batch.progress)))
            }
        }

        fun logMilestones(stage: String, before: Int, after: Int, start: Long) {
            if (listOf(8, 100, 200, 300).any { before < it && after >= it }) {
                Log.i(TAG, "$stage $after/300 ready at ${msSince(start).toLong()} ms")
            }
        }
    }
}
