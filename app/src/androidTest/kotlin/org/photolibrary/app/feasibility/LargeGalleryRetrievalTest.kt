package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.content.ContentUris
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.index.LocalPhotoIndexer
import org.photolibrary.app.index.PhotoProcessorFactory
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.semantic.ClipModel
import org.photolibrary.app.semantic.PhotoEmbedding

@RunWith(AndroidJUnit4::class)
class LargeGalleryRetrievalTest {
    @Test
    fun frozenRealGalleryCachesSceneVectorsAndRanksJudgedQueries() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("largeGallery") == "true")
        val fixture = checkNotNull(arguments.getString("largeGalleryFixture"))
        require(fixture.matches(Regex("large-gallery-[a-f0-9]{32}")))
        val context = instrumentation.targetContext
        val app = context.applicationContext as PhotoLibraryApplication
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val root = File(context.filesDir, fixture)
        val pauseRecord = root.resolve("original-pause.txt")
        val priorPause = if (pauseRecord.exists()) pauseRecord.readText().toBooleanStrict()
            else app.indexing.progress.value.checkpoint.paused.also { pauseRecord.writeText(it.toString()) }
        val bytes = root.resolve("manifest.json").readBytes()
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        val images = manifest.getJSONArray("images")
        require(images.length() in 1000..5000)
        val alias = fixture
        val directory = context.noBackupFilesDir.resolve(fixture)
        val owned = mutableListOf<Uri>()
        val sourceIds = linkedMapOf<Uri, String>()
        val model = ClipModel(context.assets)
        val library = PhotoLibrary(context)
        val storage = EncryptedPhotoIndex(directory, alias)
        val output = File(context.filesDir, fixture + "-results.json")
        val report = JSONObject().put("manifest_sha256", digest(bytes)).put("model", model.revision.version)
            .put("scope", "real-image gallery; judged positives/negatives only; unknown images stay unjudged; emulator x86_64")
        val start = SystemClock.elapsedRealtime()
        var complete = false
        try {
            app.indexing.pause(true)
            app.indexScheduler.cancel()
            val existing = linkedMapOf<String, Uri>()
            val pending = hashSetOf<Uri>()
            context.contentResolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.IS_PENDING),
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?", arrayOf(fixture + "-%"), null)?.use { rows ->
                while (rows.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), rows.getLong(0))
                    existing[rows.getString(1)] = uri
                    if (rows.getInt(2) != 0) pending += uri
                }
            }
            for (position in 0 until images.length()) {
                val image = images.getJSONObject(position)
                val id = image.getString("id")
                require(id.matches(Regex("[a-f0-9]{16}")))
                val source = root.resolve("images/" + id + ".jpg")
                assertEquals(image.getString("sha256"), source.inputStream().use { stream ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
                    hash.digest().joinToString("") { "%02x".format(it) }
                })
                val previous = existing["$fixture-$id.jpg"]
                if (previous != null) {
                    val existingBytes = context.contentResolver.openInputStream(previous)!!.use { it.readBytes() }
                    if (image.getString("sha256") != digest(existingBytes)) {
                        context.contentResolver.update(previous, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
                        context.contentResolver.openOutputStream(previous, "wt")!!.use { target -> source.inputStream().use { it.copyTo(target) } }
                        pending += previous
                    }
                    if (previous in pending) context.contentResolver.update(previous, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    owned += previous
                    sourceIds[previous] = id
                    continue
                }
                val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "$fixture-$id.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                owned += uri
                sourceIds[uri] = id
                context.contentResolver.openOutputStream(uri)!!.use { target -> source.inputStream().use { it.copyTo(target) } }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                if ((position + 1) % 100 == 0) Log.i("LargeGallery", "Imported ${position + 1}/${images.length()}")
            }
            report.put("import_ms", SystemClock.elapsedRealtime() - start)
            val photos = library.list().photos.filter { it.uri in sourceIds }
            assertEquals(images.length(), photos.size)
            val inputs = photos.map { IndexPhoto(it.uri, it.sourceRevision) }
            val vectors = linkedMapOf<Uri, PhotoEmbedding>()
            var decodes = 0
            var opens = 0
            val indexer = LocalPhotoIndexer(
                loadImage = { uri, edge -> decodes++; library.loadPixels(uri, edge) },
                processorFactory = PhotoProcessorFactory(model.revision) { opens++; model.imageProcessor() },
                openIndex = storage::openEmbeddings, readRevision = library::currentRevision,
            )
            val indexStart = SystemClock.elapsedRealtime()
            indexer.refresh(inputs, { saved, _ -> vectors.putAll(saved); report.put("preexisting_cache_entries", saved.size) }) { uri, embedding ->
                if (embedding != null) vectors[uri] = embedding
                if (vectors.size % 50 == 0) {
                    report.put("indexed", vectors.size).put("index_elapsed_ms", SystemClock.elapsedRealtime() - indexStart)
                    output.writeText(report.toString())
                    Log.i("LargeGallery", "Indexed ${vectors.size}/${inputs.size}")
                }
            }
            assertEquals(inputs.size, vectors.size)
            report.put("index_ms", SystemClock.elapsedRealtime() - indexStart).put("first_decodes", decodes).put("first_model_opens", opens)
            decodes = 0
            val restoreStart = SystemClock.elapsedRealtime()
            val restored = indexer.readCached(inputs, sourceIds.keys)
            report.put("restore_ms", SystemClock.elapsedRealtime() - restoreStart).put("repeat_decodes", decodes)
            assertEquals(inputs.size, restored.size)
            assertEquals(0, decodes)
            val queries = manifest.getJSONArray("queries")
            val results = JSONArray()
            for (position in 0 until minOf(queries.length(), 60)) {
                val query = queries.getJSONObject(position)
                val encodeStart = SystemClock.elapsedRealtime()
                val encoded = model.encodeQuery(query.getString("query"))
                val encodeMs = SystemClock.elapsedRealtime() - encodeStart
                val rankStart = SystemClock.elapsedRealtime()
                val ranked = restored.map { (uri, vector) -> sourceIds.getValue(uri) to encoded.similarity(vector) }
                    .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
                results.put(JSONObject().put("query", query.getString("query")).put("type", query.getString("type"))
                    .put("encode_ms", encodeMs).put("rank_ms", SystemClock.elapsedRealtime() - rankStart)
                    .put("ranking", JSONArray(ranked.map { JSONObject().put("id", it.first).put("score", it.second) })))
            }
            report.put("queries", results).put("index_bytes", directory.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                .put("complete", true)
            output.writeText(report.toString())
            assertTrue(results.length() > 0)
            complete = true
        } finally {
            if (complete) {
                owned.forEach { context.contentResolver.delete(it, null, null) }
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
                directory.deleteRecursively()
                app.indexing.pause(priorPause)
                if (!priorPause) app.indexScheduler.request()
            }
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
