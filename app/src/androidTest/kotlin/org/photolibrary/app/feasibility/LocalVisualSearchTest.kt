package org.photolibrary.app.feasibility

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexKey
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.index.LocalPhotoIndexer
import org.photolibrary.app.index.PhotoProcessorFactory
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.PhotoTextCodec
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.semantic.ClipModel
import org.photolibrary.app.semantic.ClipTokenizer
import org.photolibrary.app.semantic.PhotoEmbedding
import org.photolibrary.app.semantic.closestPhotos

@RunWith(AndroidJUnit4::class)
class LocalVisualSearchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun tokenizerMatchesPinnedReference() {
        val model = ClipModel(context.assets)
        assumeTrue("Stage the cached CLIP model", model.available)
        val tokenizer = context.assets.open("clip/tokenizer.json").bufferedReader().use { ClipTokenizer(JSONObject(it.readText())) }
        val references = mapOf(
            "a dog on a beach" to longArrayOf(49406, 320, 1929, 525, 320, 2117, 49407),
            "    Red\tSQUARE!" to longArrayOf(49406, 736, 3999, 256, 49407),
            "A cat isn't a dog. 2026" to longArrayOf(49406, 320, 2368, 2923, 713, 320, 1929, 269, 273, 271, 273, 277, 49407),
            "cafe\u0301 ☕" to longArrayOf(49406, 15304, 26561, 49407),
            "A bicycle, on a street" to longArrayOf(49406, 320, 11652, 267, 525, 320, 2012, 49407),
        )
        for ((query, expected) in references) assertArrayEquals(query, expected, tokenizer.encode(query))
        val longQuery = tokenizer.encode("dog ".repeat(150))
        assertEquals(77, longQuery.size)
        assertEquals(49407L, longQuery.last())
    }

    @Test
    fun ranksRealPhotosReusesEncryptedVectorsAndPreservesOcr() = runBlocking<Unit> {
        val model = ClipModel(context.assets)
        val assets = instrumentation.context.assets
        assumeTrue("Stage the cached CLIP model and visual fixtures", model.available && assets.list("clip-local").orEmpty().size >= 5)
        val directory = File(context.noBackupFilesDir, "clip-fixture-" + UUID.randomUUID()).apply { mkdirs() }
        val alias = "clip-fixture-" + UUID.randomUUID()
        val store = EncryptedPhotoIndex(directory, alias)
        val uris = (1..5).map { Uri.parse("content://fixture/visual/$it") }
        val references = uris.zip(
            listOf("a pug wearing a red scarf", "a brown dog sitting on grass", "airplanes parked at an airport", "a pizza", "a cat"),
        )
        val sources = uris.map { IndexPhoto(it, "fixture") }
        val ocrUri = Uri.parse("content://fixture/ocr")
        var loads = 0
        var modelOpens = 0
        val revisions = uris.associateWith { "fixture" }.toMutableMap()
        fun makeIndexer() = LocalPhotoIndexer(
            loadImage = { uri, _ ->
                loads++
                val name = "photo-" + uri.lastPathSegment.orEmpty().padStart(2, '0') + ".jpg"
                assets.open("clip-local/$name").use(BitmapFactory::decodeStream)
            },
            processorFactory = PhotoProcessorFactory(model.revision) {
                modelOpens++
                model.imageProcessor()
            },
            openIndex = store::openEmbeddings,
            readRevision = revisions::get,
        )
        suspend fun collect(inputs: List<IndexPhoto>): Map<Uri, PhotoEmbedding> {
            val results = mutableMapOf<Uri, PhotoEmbedding>()
            val indexer = makeIndexer()
            indexer.currentPhotos(inputs)
            indexer.refresh(inputs, { saved, _ -> results.putAll(saved) }) { uri, output ->
                if (output != null) results[uri] = output
            }
            return results
        }
        try {
            createVersionOneIndex(directory, alias, ocrUri)
            val start = SystemClock.elapsedRealtime()
            val first = collect(sources)
            val indexedMs = SystemClock.elapsedRealtime() - start
            assertEquals(5, loads)
            assertEquals(uris.toSet(), first.keys)
            val queryStart = SystemClock.elapsedRealtime()
            val queries = references.map { (target, text) -> target to model.encodeQuery(text) }
            val queryMs = SystemClock.elapsedRealtime() - queryStart
            for ((target, vector) in queries) {
                assertEquals(target, closestPhotos(vector, first, 1).single())
            }
            loads = 0
            val restored = collect(sources)
            assertEquals(0, loads)
            for ((uri, embedding) in first) assertArrayEquals(embedding.encode(), restored.getValue(uri).encode())
            val file = File(directory, "catalog.db").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(file.contains(uris.first().toString()))
            assertFalse(file.contains(first.getValue(uris.first()).encode().toString(Charsets.ISO_8859_1)))
            val opensBeforeMetadataChanges = modelOpens
            for (revision in listOf("renamed", "moved")) {
                revisions[uris.first()] = revision
                loads = 0
                val changedSources = sources.map { source -> IndexPhoto(source.uri, revisions.getValue(source.uri)) }
                val changed = collect(changedSources)
                assertEquals(1, loads)
                assertEquals(opensBeforeMetadataChanges, modelOpens)
                for ((uri, embedding) in first) assertArrayEquals(embedding.encode(), changed.getValue(uri).encode())
                loads = 0
                collect(changedSources)
                assertEquals(0, loads)
                assertEquals(opensBeforeMetadataChanges, modelOpens)
            }
            assertEquals(uris.drop(1).toSet(), collect(sources.drop(1)).keys)
            assertEquals(0, loads)
            store.openEmbeddings().use { assertEquals(uris.drop(1).toSet(), it.reconcile(uris.toSet()).keys) }
            store.open().use { assertEquals("original OCR", it.reconcile(setOf(ocrUri)).getValue(ocrUri).output.lines.single().text) }
            Log.i("PhotoLibraryVisualTest", "5/5 top-1 on cached photos. Image indexing $indexedMs ms, five text queries $queryMs ms. Reopen and pruning used 0 image decodes.")
        } finally {
            model.releaseTextSession()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private fun createVersionOneIndex(directory: File, alias: String, uri: Uri) {
        System.loadLibrary("sqlcipher")
        val passphrase = IndexKey(File(directory, "index.key"), alias).load(databasePresent = false)
        try {
            SQLiteDatabase.openDatabase(File(directory, "catalog.db").path, passphrase, null, SQLiteDatabase.CREATE_IF_NECESSARY, null)
                .use { database ->
                    database.execSQL(
                        """CREATE TABLE photo_text (
                            uri TEXT PRIMARY KEY, source_revision TEXT, pixel_revision TEXT,
                            model TEXT NOT NULL, model_version TEXT NOT NULL, model_edge INTEGER NOT NULL,
                            output TEXT NOT NULL
                        ) WITHOUT ROWID""".trimIndent(),
                    )
                    database.execSQL("INSERT INTO photo_text VALUES (?, 'fixture', NULL, 'ocr', '1', 1600, ?)",
                        arrayOf(uri.toString(), PhotoTextCodec.encode(PhotoText(listOf(TextLine("original OCR", "en", null))))))
                    database.version = 1
                }
        } finally {
            passphrase.fill(0)
        }
    }
}
