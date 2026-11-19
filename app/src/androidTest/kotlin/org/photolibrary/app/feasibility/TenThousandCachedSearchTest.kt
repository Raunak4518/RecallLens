package org.photolibrary.app.feasibility

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexedPhoto
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
        check(ids.size == 10_000 && ids.toSet().size == ids.size && ids.all { it.matches(Regex("[0-9a-f]{16}")) })
        check(vectors.length() == ids.size * 2_048L)
        check(digest(vectors) == manifest.getString("vectorsSha256") && digest(idsFile) == manifest.getString("idsSha256"))
        val uris = ids.map { Uri.parse("content://photo-library-benchmark/$it") }
        val targets = uris.toSet()
        val indexDirectory = File(app.noBackupFilesDir, token)
        val storage = EncryptedPhotoIndex(indexDirectory, token)
        try {
            val writeStart = SystemClock.elapsedRealtimeNanos()
            storage.openEmbeddings().use { session ->
                DataInputStream(vectors.inputStream().buffered()).use { input ->
                    val row = ByteArray(2_048)
                    for (uri in uris) {
                        input.readFully(row)
                        session.save(uri, IndexedPhoto("benchmark", null, app.clip.revision, PhotoEmbedding.decode(row)))
                    }
                    check(input.read() < 0)
                }
            }
            val writeMs = millisecondsSince(writeStart)
            val readStart = SystemClock.elapsedRealtimeNanos()
            val reopened = EncryptedPhotoIndex(indexDirectory, token).openEmbeddings().use { session ->
                session.reconcile(targets).mapValues { it.value.output }
            }
            val restoreMs = millisecondsSince(readStart)
            check(reopened.keys == targets)
            val queryRows = manifest.getJSONArray("queries")
            check(queryRows.length() in 3..40)
            val results = JSONArray()
            for (position in 0 until queryRows.length()) {
                val query = queryRows.getString(position)
                val encodeStart = SystemClock.elapsedRealtimeNanos()
                val vector = app.clip.encodeQuery(query)
                val encodeMs = millisecondsSince(encodeStart)
                val rankStart = SystemClock.elapsedRealtimeNanos()
                val top = closestPhotos(vector, reopened, 24)
                val rankMs = millisecondsSince(rankStart)
                check(top.size == 24)
                results.put(JSONObject().put("query", query).put("encodeMs", encodeMs).put("rankMs", rankMs)
                    .put("topIds", JSONArray(top.map { it.lastPathSegment })))
            }
            stage.resolve("result.json").writeText(JSONObject().put("complete", true).put("photos", ids.size)
                .put("vectorsSha256", manifest.getString("vectorsSha256")).put("manifestSha256", manifest.getString("manifestSha256"))
                .put("writeMs", writeMs).put("restoreMs", restoreMs).put("queries", results)
                .put("encryptedBytes", indexDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                .put("scope", "Host-encoded real-photo vectors, Android SQLCipher restore and CLIP text search, no Android image indexing or UI timing")
                .toString())
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(token)
            indexDirectory.deleteRecursively()
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
