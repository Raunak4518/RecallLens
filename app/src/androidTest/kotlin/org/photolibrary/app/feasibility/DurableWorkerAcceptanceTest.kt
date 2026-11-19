package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexingControl

@RunWith(AndroidJUnit4::class)
class DurableWorkerAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as PhotoLibraryApplication
    private val storage get() = EncryptedPhotoIndex(context.noBackupFilesDir.resolve("photo-text-index"), "photo-library.ocr-wrap.v1")
    private val receipt get() = context.filesDir.resolve("durable-worker-acceptance.json")

    @Test
    fun prepareAndWaitForPartialProgress() = runBlocking {
        enabled()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        app.indexScheduler.cancel()
        app.indexing.pause(false)
        val images = JSONArray()
        receipt.writeText(JSONObject().put("images", images).toString())
        repeat(24) { position ->
            val uri = insert(position)
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            images.put(JSONObject().put("uri", uri.toString()).put("sha256", digest(bytes)))
            receipt.writeText(JSONObject().put("images", images).toString())
        }
        app.indexing.request()
        app.indexScheduler.request()
        await {
            val count = rows("photo_text", imageUris()).size
            count in 1 until images.length()
        }
        val partial = rows("photo_text", imageUris())
        assertTrue(partial.isNotEmpty())
        assertTrue(partial.size < images.length())
        val report = JSONObject(receipt.readText())
        report.put("partial_rows", JSONObject(partial))
        report.put("pid_before", android.os.Process.myPid())
        receipt.writeText(report.toString())
    }

    @Test
    fun verifyResumeAndSetPersistentPause() = runBlocking {
        enabled()
        val report = JSONObject(receipt.readText())
        assertTrue(report.getInt("pid_before") != android.os.Process.myPid())
        val uris = imageUris()
        await {
            rows("photo_text", uris).size == uris.size && rows("image_embeddings", uris).size == uris.size
        }
        val finished = rows("photo_text", uris)
        val partial = report.getJSONObject("partial_rows")
        for (uri in partial.keys()) assertEquals(partial.getString(uri), finished.getValue(uri))
        verifyOriginals(report)
        app.indexing.pause(true)
        app.indexScheduler.cancel()
        assertTrue(IndexingControl(storage).read().paused)
        val pending = insert(24)
        val bytes = context.contentResolver.openInputStream(pending)!!.use { it.readBytes() }
        report.getJSONArray("images").put(JSONObject().put("uri", pending.toString()).put("sha256", digest(bytes)))
        report.put("paused_uri", pending.toString())
        report.put("pid_paused", android.os.Process.myPid())
        report.put("resumed_count", finished.size)
        receipt.writeText(report.toString())
        app.indexScheduler.request()
    }

    @Test
    fun verifyPersistentPauseResumeAndCleanup() = runBlocking {
        enabled()
        val report = JSONObject(receipt.readText())
        assertTrue(report.getInt("pid_paused") != android.os.Process.myPid())
        assertTrue(IndexingControl(storage).read().paused)
        val pending = report.getString("paused_uri")
        SystemClock.sleep(2_000)
        assertFalse(pending in rows("photo_text", setOf(pending)))
        app.indexing.pause(false)
        app.indexScheduler.request()
        await {
            pending in rows("photo_text", setOf(pending)) && pending in rows("image_embeddings", setOf(pending))
        }
        verifyOriginals(report)
        report.put("pause_survived_restart", true).put("completed_photos", report.getJSONArray("images").length())
        receipt.writeText(report.toString())
        app.indexing.pause(true)
        app.indexScheduler.cancel()
        for (uri in imageUris()) context.contentResolver.delete(Uri.parse(uri), null, null)
        val accessible = app.library.list().photos.mapTo(hashSetOf()) { it.uri }
        storage.open().use { it.reconcile(accessible, emptySet()) }
        storage.openEmbeddings().use { it.reconcile(accessible, emptySet()) }
        app.indexing.pause(false)
    }

    private fun enabled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("durableWorkerAcceptance") == "true")
        assertTrue(app.clip.available)
    }

    private fun imageUris(): Set<String> {
        val images = JSONObject(receipt.readText()).getJSONArray("images")
        return (0 until images.length()).mapTo(hashSetOf()) { images.getJSONObject(it).getString("uri") }
    }

    private fun rows(table: String, uris: Set<String>): Map<String, String> = storage.withDatabase { db ->
        require(table in setOf("photo_text", "image_embeddings"))
        val placeholders = List(uris.size) { "?" }.joinToString(",")
        db.rawQuery("SELECT uri, output FROM $table WHERE uri IN ($placeholders)", uris.toTypedArray()).use { result ->
            buildMap {
                while (result.moveToNext()) {
                    val output = if (table == "photo_text") result.getString(1).encodeToByteArray() else result.getBlob(1)
                    put(result.getString(0), digest(output))
                }
            }
        }
    }

    private fun verifyOriginals(report: JSONObject) {
        val images = report.getJSONArray("images")
        for (position in 0 until images.length()) {
            val image = images.getJSONObject(position)
            val bytes = context.contentResolver.openInputStream(Uri.parse(image.getString("uri")))!!.use { it.readBytes() }
            assertEquals(image.getString("sha256"), digest(bytes))
        }
    }

    private fun insert(position: Int): Uri {
        val bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; textSize = 30f; isAntiAlias = true }
        for (line in 0 until 18) canvas.drawText("GATE $position PLATFORM $line", 32f, 70f + line * 46f, paint)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "durable-worker-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoLibraryAcceptance")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        try {
            resolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } finally {
            bitmap.recycle()
        }
        return uri
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 240_000
        while (!condition()) {
            assertTrue("Timed out waiting for persisted WorkManager progress", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(200)
        }
    }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
