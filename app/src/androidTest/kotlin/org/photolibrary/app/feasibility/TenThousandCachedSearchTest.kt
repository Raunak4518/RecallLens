package org.photolibrary.app.feasibility

import android.net.Uri
import android.graphics.ImageDecoder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.BuildConfig
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexedPhoto
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.library.decodePhoto
import org.photolibrary.app.semantic.PhotoEmbedding
import org.photolibrary.app.semantic.closestPhotos

@RunWith(AndroidJUnit4::class)
class TenThousandCachedSearchTest {
    @Test
    fun restoresAndRanksRealGalleryVectors() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("cache10k") == "true")
        val token = checkNotNull(arguments.getString("cache10kFixture"))
        require(token.matches(Regex("cache10k-[0-9a-f]{32}")))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoLibraryApplication
        val stage = File(app.filesDir, token)
        val manifest = JSONObject(stage.resolve("input.json").readText())
        val vectors = stage.resolve("scene-vectors-f32le.bin")
        val idsFile = stage.resolve("scene-vector-ids.txt")
        val ids = idsFile.readLines()
        val idPattern = Regex("(?:[0-9a-f]{16}|docci:[0-9a-f]{24})")
        val storageStress = manifest.optBoolean("storageStress")
        val supportedSize = if (storageStress) ids.size in setOf(25_000, 50_000) else ids.size in 5_000..12_000
        check(supportedSize && ids.toSet().size == ids.size && ids.all(idPattern::matches))
        val depth = manifest.optInt("depth", 24)
        check(depth in 5..100)
        val writeBatchSize = manifest.optInt("writeBatchSize", 1)
        check(writeBatchSize in 1..32)
        check(vectors.length() == ids.size * 2_048L)
        check(digest(vectors) == manifest.getString("vectorsSha256") && digest(idsFile) == manifest.getString("idsSha256"))
        val uris = ids.map { Uri.parse("content://photo-library-benchmark/$it") }
        val targets = uris.toSet()
        val indexDirectory = File(app.noBackupFilesDir, token)
        val storage = EncryptedPhotoIndex(indexDirectory, token)
        try {
            val reencoded = reencodeTargets(app, stage, manifest.optJSONArray("targetImages"), uris)
            val similarities = JSONArray()
            val writeStart = SystemClock.elapsedRealtimeNanos()
            storage.openEmbeddings().use { session ->
                DataInputStream(vectors.inputStream().buffered()).use { input ->
                    val row = ByteArray(2_048)
                    for (batch in uris.chunked(writeBatchSize)) {
                        val records = batch.map { uri ->
                            input.readFully(row)
                            val original = PhotoEmbedding.decode(row)
                            val replacement = reencoded[uri]
                            if (replacement != null) similarities.put(replacement.sceneSimilarity(original).toDouble())
                            uri to IndexedPhoto("benchmark", null, app.clip.revision, replacement ?: original)
                        }
                        if (writeBatchSize == 1) {
                            val (uri, photo) = records.single()
                            session.save(uri, photo)
                        } else session.saveBatch(records)
                    }
                    check(input.read() < 0)
                }
            }
            val writeMs = millisecondsSince(writeStart)
            val dbFile = indexDirectory.resolve("catalog.db")
            val sceneBytes = dbFile.length()
            val textStress = if (storageStress) writeTextStress(storage, uris, writeBatchSize) else null
            val readStart = SystemClock.elapsedRealtimeNanos()
            val reopened = EncryptedPhotoIndex(indexDirectory, token).openEmbeddings().use { session ->
                session.reconcile(targets).mapValues { it.value.output }
            }
            val restoreMs = millisecondsSince(readStart)
            check(reopened.keys == targets)
            val queryRows = manifest.getJSONArray("queries")
            check(queryRows.length() in 3..200)
            val results = JSONArray()
            for (position in 0 until queryRows.length()) {
                val query = queryRows.getString(position)
                val encodeStart = SystemClock.elapsedRealtimeNanos()
                val vector = app.clip.encodeQuery(query)
                val encodeMs = millisecondsSince(encodeStart)
                val rankStart = SystemClock.elapsedRealtimeNanos()
                val top = closestPhotos(vector, reopened, depth)
                val rankMs = millisecondsSince(rankStart)
                check(top.size == depth)
                val recorded = JSONObject().put("query", query).put("encodeMs", encodeMs).put("rankMs", rankMs)
                    .put("topIds", JSONArray(top.map { it.lastPathSegment }))
                app.visualReranker?.let { reranker ->
                    val rerankStart = SystemClock.elapsedRealtimeNanos()
                    val reranked = reranker.reorder(vector, top, reopened)
                    recorded.put("rerankMs", millisecondsSince(rerankStart))
                        .put("rerankedIds", JSONArray(reranked.map { it.lastPathSegment }))
                }
                results.put(recorded)
            }
            val report = JSONObject().put("complete", true).put("photos", ids.size)
                .put("textGraph", BuildConfig.CLIP_TEXT_MODEL_FILE)
                .put("writeBatchSize", writeBatchSize)
                .put("vectorsSha256", manifest.getString("vectorsSha256")).put("manifestSha256", manifest.getString("manifestSha256"))
                .put("writeMs", writeMs).put("restoreMs", restoreMs).put("queries", results)
                .put("encryptedBytes", indexDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                .put("androidTargetCosines", similarities)
                .put("scope", when {
                    storageStress -> "Repeated host image vectors and synthetic text rows with Android SQLCipher and cached visual ranking"
                    reencoded.isEmpty() -> "Host image vectors with Android SQLCipher, text encoding and ranking"
                    else -> "Android-encoded target images, host background vectors and Android text encoding and ranking"
                })
            if (textStress != null) {
                val pageStats = storage.withDatabase { db ->
                    fun value(name: String): Long = db.rawQuery("PRAGMA $name", emptyArray<String>()).use {
                        check(it.moveToFirst())
                        it.getLong(0)
                    }
                    JSONObject().put("pageSize", value("page_size")).put("pageCount", value("page_count"))
                        .put("freelistPages", value("freelist_count"))
                }
                report.put("sceneDatabaseBytes", sceneBytes).put("syntheticText", textStress).put("pageStats", pageStats)
            }
            stage.resolve("result.json").writeText(report.toString())
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(token)
            indexDirectory.deleteRecursively()
        }
    }

    private fun writeTextStress(storage: EncryptedPhotoIndex, uris: List<Uri>, batchSize: Int): JSONObject {
        val version = ModelRevision("synthetic-text", "1", 896)
        val filler = "sample text for encrypted storage measurement ".repeat(380)
        val start = SystemClock.elapsedRealtimeNanos()
        var inputBytes = 0L
        storage.open().use { session ->
            for ((batch, members) in uris.chunked(batchSize).withIndex()) {
                session.saveBatch(members.mapIndexed { offset, uri ->
                    val position = batch * batchSize + offset
                    val length = if (position % 20 == 0) 16_384 else 2_384
                    val text = ("row $position " + filler).take(length)
                    inputBytes += text.length
                    uri to IndexedPhoto("benchmark", null, version, PhotoText(listOf(TextLine(text, "en", null))))
                })
            }
        }
        val writeMs = millisecondsSince(start)
        val sample = uris.filterIndexed { index, _ -> index % 250 == 0 }.toSet()
        val restoreStart = SystemClock.elapsedRealtimeNanos()
        storage.open().use { session ->
            val restored = session.reconcile(uris.toSet(), sample)
            check(restored.keys == sample && restored.values.all { it.output.lines.single().text.length == 16_384 ||
                it.output.lines.single().text.length == 2_384 })
        }
        return JSONObject().put("rows", uris.size).put("heavyRows", (uris.size + 19) / 20)
            .put("inputTextBytes", inputBytes).put("writeMs", writeMs)
            .put("sampleRows", sample.size).put("sampleRestoreMs", millisecondsSince(restoreStart))
    }

    private fun reencodeTargets(
        app: PhotoLibraryApplication, stage: File, rows: JSONArray?, uris: List<Uri>,
    ): Map<Uri, PhotoEmbedding> {
        if (rows == null) return emptyMap()
        require(rows.length() in 1..60)
        return ZipFile(stage.resolve("target-images.zip")).use { archive ->
            app.clip.imageProcessor().use { processor ->
                buildMap {
                    for (position in 0 until rows.length()) {
                        val uri = uris[position]
                        val spec = rows.getJSONObject(position)
                        check(spec.getString("id") == uri.lastPathSegment)
                        val entry = checkNotNull(archive.getEntry("${uri.lastPathSegment}.jpg"))
                        check(entry.size in 1..16_777_216)
                        val bytes = archive.getInputStream(entry).use { stream -> stream.readNBytes(entry.size.toInt() + 1) }
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        check(hash == spec.getString("sha256"))
                        val bitmap = decodePhoto(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), processor.model.maxImageEdge, software = true)
                        try { put(uri, processor.process(bitmap)) } finally { bitmap.recycle() }
                    }
                }
            }
        }
    }

    private fun millisecondsSince(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(32_768)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                sha.update(chunk, 0, read)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
