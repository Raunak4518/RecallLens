package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.privacy.cloudSettings
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.privacy.CloudConfiguration
import org.photolibrary.app.privacy.CloudConnection
import org.photolibrary.app.privacy.CloudHttp
import org.photolibrary.app.privacy.CloudReply
import org.photolibrary.app.privacy.CloudTransport
import org.photolibrary.app.privacy.ExportMode
import org.photolibrary.app.privacy.PhotoExportViewModel

@RunWith(AndroidJUnit4::class)
class CloudClientTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
    private val publicKey = Base64.encodeToString(ByteArray(32) { (it + 1).toByte() }, Base64.NO_WRAP)

    @Test
    fun connectionUsesEncryptedStorageAndRejectsUnsafeAddresses() {
        val alias = "cloud-settings-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, alias)
        try {
            val connection = CloudConnection.create("https://worker.example.test", "private-access-key-marker-83019741", publicKey, false)
            val store = CloudConfiguration(EncryptedPhotoIndex(directory, alias))
            store.save(connection)
            val reopened = CloudConfiguration(EncryptedPhotoIndex(directory, alias))
            assertEquals(connection.worker.id, reopened.load()?.worker?.id)
            assertEquals(connection.token, reopened.load()?.token)
            assertFalse(File(directory, "catalog.db").readBytes().toString(Charsets.ISO_8859_1).contains(connection.token))
            assertThrows(IllegalArgumentException::class.java) {
                CloudConnection.create("http://worker.example.test", connection.token, publicKey, true)
            }
            assertThrows(IllegalArgumentException::class.java) {
                CloudConnection.create("https://user:password@worker.example.test", connection.token, publicKey, false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                CloudConnection.create("https://worker.example.test?redirect=bad", connection.token, publicKey, false)
            }
            store.clear()
            assertEquals(null, reopened.load())
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    @Test
    fun lostSubmitResponseRetriesExactBytesAndFailureDeletesTheJob() = fixture { uri ->
        val calls = mutableListOf<Pair<String, ByteArray?>>()
        var job = ""
        val fake = object : CloudHttp {
            override suspend fun exchange(connection: CloudConnection, method: String, path: String, body: ByteArray?): CloudReply {
                synchronized(calls) { calls += method to body?.clone() }
                if (method == "POST") {
                    job = JSONObject(checkNotNull(body).decodeToString()).getString("job")
                    if (calls.count { it.first == "POST" } == 1) throw IOException("Response was lost")
                    return CloudReply(202, JSONObject().put("id", job).put("protocol", 1).put("task", "OCR_ENGLISH").toString().encodeToByteArray())
                }
                return CloudReply(if (method == "DELETE") 200 else 503, byteArrayOf())
            }
        }
        val previous = context.cloudSettings.load()
        val connection = CloudConnection.create("https://worker.example.test", "private-access-key-marker-83019741", publicKey, false)
        context.cloudSettings.save(connection)
        val viewModel = PhotoExportViewModel(context, CloudTransport(fake))
        try {
            instrumentation.runOnMainSync { viewModel.open(uri) }
            await { viewModel.state.value?.busy == false }
            instrumentation.runOnMainSync { viewModel.mode(ExportMode.CLOUD_FULL) }
            await { viewModel.state.value?.artifact != null && viewModel.state.value?.busy == false }
            instrumentation.runOnMainSync { viewModel.approve() }
            await { synchronized(calls) { calls.any { it.first == "DELETE" } } }
            val posts = synchronized(calls) { calls.filter { it.first == "POST" }.map { checkNotNull(it.second) } }
            assertEquals(2, posts.size)
            assertTrue(posts[0].contentEquals(posts[1]))
            assertNotNull(viewModel.state.value?.error)
            assertEquals(null, viewModel.state.value?.cloudText)
            assertFalse(viewModel.state.value?.cleanupPending == true)
        } finally {
            instrumentation.runOnMainSync { viewModel.close() }
            if (previous == null) context.cloudSettings.clear() else context.cloudSettings.save(previous)
        }
    }

    @Test
    fun normalExportWorkflowUsesAuthenticatedHttpService() {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("cloudServiceUrl")
        assumeTrue(url != null)
        val token = checkNotNull(arguments.getString("cloudServiceToken"))
        val key = checkNotNull(arguments.getString("cloudWorkerKey"))
        val connection = CloudConnection.create(checkNotNull(url), token, key, true)
        val previous = context.cloudSettings.load()
        context.cloudSettings.save(connection)
        try {
            fixture { uri ->
                val viewModel = PhotoExportViewModel(context)
                try {
                    for (mode in ExportMode.entries) {
                        instrumentation.runOnMainSync { viewModel.open(uri) }
                        await { viewModel.state.value?.busy == false }
                        instrumentation.runOnMainSync { viewModel.mode(mode) }
                        await { viewModel.state.value?.artifact != null && viewModel.state.value?.busy == false }
                        instrumentation.runOnMainSync { viewModel.approve() }
                        await {
                            viewModel.state.value?.cloudText != null ||
                                viewModel.state.value?.error != null && viewModel.state.value?.busy == false
                        }
                        val text = checkNotNull(viewModel.state.value?.cloudText) { "Cloud workflow failed: ${viewModel.state.value?.error}" }.lowercase()
                        assertTrue(text.contains("public catalog"))
                        assertEquals(mode == ExportMode.CLOUD_FULL, text.contains("example.com"))
                        val revision = kotlinx.coroutines.runBlocking { context.library.referenceRevision(uri) }
                        val persisted = context.cloudText.reconcile(mapOf(uri to checkNotNull(revision))).getValue(uri)
                        val provenance = JSONObject(checkNotNull(persisted.modelProvenance))
                        assertEquals("Tesseract", provenance.getString("engine"))
                        assertTrue(provenance.getString("weights_sha256").matches(Regex("[0-9a-f]{64}")))
                        await { viewModel.state.value?.cleanupPending != true }
                        instrumentation.runOnMainSync { viewModel.close() }
                    }
                } finally {
                    instrumentation.runOnMainSync { viewModel.close() }
                }
            }
        } finally {
            if (previous == null) context.cloudSettings.clear() else context.cloudSettings.save(previous)
        }
    }

    @Test
    fun editAfterLostUploadResponsePreventsRetryAndRemovesTheJob() = interruptLostUpload(cancel = false)

    @Test
    fun cancellationAfterLostUploadResponsePreventsRetryAndRemovesTheJob() = interruptLostUpload(cancel = true)

    private fun interruptLostUpload(cancel: Boolean) = fixture { uri ->
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        lateinit var viewModel: PhotoExportViewModel
        val previous = context.cloudSettings.load()
        val connection = CloudConnection.create("https://worker.example.test", "private-access-key-marker-83019741", publicKey, false)
        context.cloudSettings.save(connection)
        val fake = object : CloudHttp {
            override suspend fun exchange(connection: CloudConnection, method: String, path: String, body: ByteArray?): CloudReply {
                calls += method
                if (method == "POST") {
                    if (cancel) instrumentation.runOnMainSync { viewModel.close() } else {
                        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
                        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    }
                    throw IOException("Response was lost")
                }
                return CloudReply(200, byteArrayOf())
            }
        }
        viewModel = PhotoExportViewModel(context, CloudTransport(fake))
        try {
            instrumentation.runOnMainSync { viewModel.open(uri) }
            await { viewModel.state.value?.busy == false }
            instrumentation.runOnMainSync { viewModel.mode(ExportMode.CLOUD_FULL) }
            await { viewModel.state.value?.artifact != null && viewModel.state.value?.busy == false }
            instrumentation.runOnMainSync { viewModel.approve() }
            await { synchronized(calls) { "DELETE" in calls } }
            assertEquals(listOf("POST", "DELETE"), synchronized(calls) { calls.toList() })
            assertEquals(null, viewModel.state.value?.cloudText)
            assertFalse(uri in context.cloudText.references())
            if (cancel) assertEquals(null, viewModel.state.value)
            else assertNotNull(viewModel.state.value?.error)
        } finally {
            instrumentation.runOnMainSync { viewModel.close() }
            if (previous == null) context.cloudSettings.clear() else context.cloudSettings.save(previous)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for cloud workflow", condition())
    }

    private fun fixture(action: (Uri) -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "cloud-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CloudFixtures")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
                drawText("PUBLIC CATALOG", 70f, 120f, paint)
                drawText("Mail private@example.com", 70f, 275f, paint)
            }
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            action(uri)
        } finally {
            bitmap.recycle()
            resolver.delete(uri, null, null)
        }
    }
}
