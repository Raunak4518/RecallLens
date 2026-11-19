package org.photolibrary.app.feasibility

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.index.IndexedPhoto
import org.photolibrary.app.index.LocalPhotoIndexer
import org.photolibrary.app.index.PhotoIndexSession
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoEvidenceUpgrade
import org.photolibrary.app.index.PhotoProcessor
import org.photolibrary.app.index.PhotoProcessorFactory
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.index.normalizePhotoText

@RunWith(AndroidJUnit4::class)
class LocalPhotoIndexerTest {
    private val uri = Uri.parse("content://fixture/photo")

    @Test
    fun refreshReusesCurrentResultAndRunsReplacementModel() = runBlocking {
        var modelVersion = "a"
        var inferenceCalls = 0
        var loads = 0
        val index = LocalPhotoIndexer(
            loadImage = { _, edge ->
                assertEquals(128, edge)
                loads++
                solid(Color.WHITE)
            },
            createProcessor = { processor(modelVersion) { inferenceCalls++ } },
            openIndex = { memoryIndex },
            readRevision = { null },
        )
        val observed = mutableListOf<String?>()
        val publish: suspend (Uri, String?) -> Unit = { _, output -> observed += output }
        index.refresh(listOf(uri), publish)
        index.refresh(listOf(uri), publish)
        modelVersion = "b"
        index.refresh(listOf(uri), publish)
        assertEquals(listOf("a", "a", "b"), observed)
        assertEquals(2, inferenceCalls)
        assertEquals(6, loads)
    }

    @Test
    fun changedDuringInferenceAndRevokedAccessDropResults() = runBlocking {
        var allowed = true
        var pixel = Color.WHITE
        var mutateDuringInference = true
        val index = LocalPhotoIndexer(
            loadImage = { _, _ -> if (allowed) solid(pixel) else null },
            createProcessor = {
                processor("text") { if (mutateDuringInference) pixel = Color.BLACK }
            },
            openIndex = { memoryIndex },
            readRevision = { null },
        )
        val observed = mutableListOf<String?>()
        val publish: suspend (Uri, String?) -> Unit = { _, output -> observed += output }
        index.refresh(listOf(uri), publish)
        mutateDuringInference = false
        index.refresh(listOf(uri), publish)
        allowed = false
        index.refresh(listOf(uri), publish)
        assertEquals(listOf(null, "text", null), observed)
    }

    @Test
    fun batchRechecksEarlierSourcesAndCommitsBeforePublishing() = runBlocking {
        val sources = (1..3).map { Uri.parse("content://fixture/batch/$it") }
        val revisions = sources.associateWith { "source:original" }.toMutableMap()
        var inference = 0
        val index = LocalPhotoIndexer(
            loadImage = { _, _ -> solid(Color.WHITE) },
            createProcessor = {
                processor("text") {
                    if (++inference == 3) revisions[sources.first()] = "source:changed"
                }
            },
            openIndex = { memoryIndex },
            readRevision = { revisions[it] },
        )
        val published = mutableListOf<String?>()
        index.refresh(sources.map { IndexPhoto(it, revisions.getValue(it)) }, { _, _ -> },
            processUris = sources.toSet(), writeBatchSize = 3) { _, output ->
            assertEquals(sources.drop(1).toSet(), memoryIndex.reconcile(sources.toSet()).keys)
            published += output
        }
        assertEquals(listOf(null, "text", "text"), published)
    }

    @Test
    fun cancellationDuringInferencePreventsPublishAndCacheReuse() = runBlocking {
        var inferenceCalls = 0
        var published = false
        lateinit var job: Job
        val index = LocalPhotoIndexer(
            loadImage = { _, _ -> solid(Color.WHITE) },
            createProcessor = {
                processor("text") {
                    if (++inferenceCalls == 1) job.cancel()
                }
            },
            openIndex = { memoryIndex },
            readRevision = { null },
        )
        job = launch(start = CoroutineStart.LAZY) {
            index.refresh(listOf(uri)) { _, _ -> published = true }
        }
        job.start()
        job.join()
        assertFalse(published)
        index.refresh(listOf(uri)) { _, output -> assertEquals("text", output) }
        assertEquals(2, inferenceCalls)
    }

    @Test
    fun metadataChangesReusePixelsWithoutOpeningModelsButEditsRunInference() = runBlocking {
        var sourceRevision = "first"
        var color = Color.WHITE
        var opens = 0
        var inference = 0
        val model = ModelRevision("fixture", "hash-reuse", 128)
        val index = LocalPhotoIndexer(
            loadImage = { _, _ -> solid(color) },
            processorFactory = PhotoProcessorFactory(model) {
                opens++
                processor(model.version) { inference++ }
            },
            openIndex = { memoryIndex },
            readRevision = { sourceRevision },
        )
        suspend fun refresh() = index.refresh(listOf(IndexPhoto(uri, sourceRevision)), { _, _ -> }) { _, text ->
            assertEquals(model.version, text)
        }
        refresh()
        sourceRevision = "renamed"
        refresh()
        assertEquals(1, opens)
        assertEquals(1, inference)
        assertEquals("renamed", memoryIndex.reconcile(setOf(uri)).getValue(uri).sourceRevision)
        sourceRevision = "edited"
        color = Color.BLACK
        refresh()
        assertEquals(2, opens)
        assertEquals(2, inference)
    }

    @Test
    fun unchangedPixelsCannotBeReusedAfterAccessChangesDuringDecode() = runBlocking {
        var revision: String? = "first"
        var invalidate = false
        val model = ModelRevision("fixture", "hash-reuse", 128)
        var inference = 0
        val index = LocalPhotoIndexer(
            loadImage = { _, _ ->
                if (invalidate) revision = null
                solid(Color.WHITE)
            },
            processorFactory = PhotoProcessorFactory(model) { processor(model.version) { inference++ } },
            openIndex = { memoryIndex },
            readRevision = { revision },
        )
        index.refresh(listOf(IndexPhoto(uri, revision)), { _, _ -> }) { _, _ -> }
        revision = "renamed"
        invalidate = true
        var output: String? = "unexpected"
        index.refresh(listOf(IndexPhoto(uri, revision)), { _, _ -> }) { _, text -> output = text }
        assertEquals(null, output)
        assertEquals(1, inference)
        assertTrue(memoryIndex.reconcile(setOf(uri)).isEmpty())
    }

    @Test
    fun supportsBackfillAndVerifiedDuplicateReuseWithoutAnotherInference() = runBlocking {
        val oldModel = ModelRevision("fixture", "old", 128)
        val current = oldModel.copy(version = "enriched")
        val copy = Uri.parse("content://fixture/copy")
        val accessible = listOf(IndexPhoto(uri, "one"), IndexPhoto(copy, "two"))
        val revisions = accessible.associate { it.uri to it.sourceRevision }
        val before = LocalPhotoIndexer(
            loadImage = { _, _ -> solid(Color.WHITE) },
            processorFactory = PhotoProcessorFactory(oldModel) { processor(oldModel.version) {} },
            openIndex = { memoryIndex },
            readRevision = revisions::get,
        )
        before.refresh(accessible, { _, _ -> }, setOf(uri)) { _, _ -> }
        var inference = 0
        var backfills = 0
        val changed = LocalPhotoIndexer(
            loadImage = { _, _ -> solid(Color.WHITE) },
            processorFactory = PhotoProcessorFactory(current) { processor(current.version) { inference++ } },
            openIndex = { memoryIndex },
            readRevision = revisions::get,
            upgrade = PhotoEvidenceUpgrade(oldModel) { _, output -> backfills++; "$output + fingerprint" },
            reuseEquivalentPixels = true,
        )
        assertEquals(mapOf(uri to "old"), changed.readCached(accessible, setOf(uri)))
        val all = mutableMapOf<Uri, String?>()
        changed.refresh(accessible, { _, _ -> }, setOf(uri, copy)) { source, output -> all[source] = output }
        assertEquals(mapOf(uri to "old + fingerprint", copy to "old + fingerprint"), all)
        assertEquals(1, backfills)
        assertEquals(0, inference)
        assertEquals("two", memoryIndex.reconcile(setOf(uri, copy)).getValue(copy).sourceRevision)
    }

    @Test
    fun textSearchMatchesWordsAndPhrasesWithinOneLine() {
        val text = PhotoText(listOf(TextLine("NORTH—GATE 4821", "en", null), TextLine("Open", "en", null)))
        assertTrue(text.matches(normalizePhotoText("north gate")))
        assertTrue(text.matches(normalizePhotoText("４８２１")))
        assertFalse(text.matches(normalizePhotoText("gate 48")))
        assertFalse(text.matches(normalizePhotoText("4821 open")))
    }

    private fun processor(version: String, process: () -> Unit) = object : PhotoProcessor<String> {
        override val model = ModelRevision("fixture", version, 128)

        override fun process(image: Bitmap): String {
            process()
            return version
        }

        override fun close() = Unit
    }

    private fun solid(color: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private suspend fun LocalPhotoIndexer<String>.refresh(uris: List<Uri>, publish: suspend (Uri, String?) -> Unit) {
        refresh(uris.map { IndexPhoto(it) }, { restored, _ -> check(restored.isEmpty()) }, publish)
    }

    private val memoryIndex = object : PhotoIndexSession<String> {
        private val records = mutableMapOf<Uri, IndexedPhoto<String>>()

        override fun reconcile(accessible: Set<Uri>): Map<Uri, IndexedPhoto<String>> {
            records.keys.retainAll(accessible)
            return records.toMap()
        }

        override fun save(uri: Uri, photo: IndexedPhoto<String>) {
            records[uri] = photo
        }

        override fun remove(uri: Uri) {
            records.remove(uri)
        }

        override fun close() = Unit
    }
}
