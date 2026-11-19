package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.privacy.photoExport
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.privacy.ExportEdits
import org.photolibrary.app.privacy.ExportFailure
import org.photolibrary.app.privacy.ExportMode
import org.photolibrary.app.privacy.PhotoExport
import org.photolibrary.app.privacy.SensitiveRegions
import org.photolibrary.app.privacy.WorkerKey
import org.photolibrary.app.privacy.isSupportedStill
import org.photolibrary.app.privacy.isVerifiedExport
import org.photolibrary.app.privacy.sha256
import org.photolibrary.app.subjects.PhotoRegion

@RunWith(AndroidJUnit4::class)
class PhotoExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
    private val resolver = context.contentResolver

    @Test
    fun realOcrMasksIdentifierAndPreservesTheExactApprovedCopy() = fixture { uri ->
        val flow = context.photoExport
        val full = flow.render(flow.inspect(uri, ExportMode.CLOUD_FULL), ExportEdits())
        val redacted = flow.render(flow.inspect(uri, ExportMode.CLOUD_REDACTED), ExportEdits())
        assertEquals(1, redacted.source.sensitive?.identifiers?.size)
        assertEquals(0, redacted.source.sensitive?.faces?.size)
        assertTrue(isVerifiedExport(full.copyBytes()))
        assertTrue(isVerifiedExport(redacted.copyBytes()))
        assertNotEquals(full.digest, redacted.digest)
        val hiddenMetadata = "synthetic-metadata-marker"
        val original = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        assertTrue(original.toString(Charsets.ISO_8859_1).contains(hiddenMetadata))
        assertFalse(full.copyBytes().toString(Charsets.ISO_8859_1).contains(hiddenMetadata))
        val mask = checkNotNull(redacted.source.sensitive).identifiers.single()
        val x = ((mask.left + mask.right) / 2 * redacted.preview.width).toInt()
        val y = ((mask.top + mask.bottom) / 2 * redacted.preview.height).toInt()
        assertEquals(Color.BLACK, redacted.preview.getPixel(x, y))
        assertEquals(redacted.digest, flow.approve(redacted, "fixture-worker").artifact.digest)
        assertEquals(redacted.source.digest, sha256(checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }))
        assertFalse(isSupportedStill(full.copyBytes() + byteArrayOf(1, 2, 3)))
    }

    @Test
    fun masksFaceOnTheRenderedCopy() = fixture { uri ->
        val portrait = instrumentation.context.assets.open("detection/astronaut.png").use { it.readBytes() }
        writeFixtureBytes(uri, portrait)
        val flow = context.photoExport
        val output = flow.render(flow.inspect(uri, ExportMode.CLOUD_REDACTED), ExportEdits())
        val face = checkNotNull(output.source.sensitive).faces.single()
        val x = ((face.left + face.right) * .5f * output.preview.width).toInt()
        val y = ((face.top + face.bottom) * .5f * output.preview.height).toInt()
        assertEquals(Color.BLACK, output.preview.getPixel(x, y))
    }

    @Test
    fun failureOrChangedSourceCannotReuseAnApproval() = fixture { uri ->
        val library = PhotoLibrary(context)
        val failing = PhotoExport(library) { throw ExportFailure.Detector() }
        assertThrows(ExportFailure.Detector::class.java) { failing.inspect(uri, ExportMode.CLOUD_REDACTED) }
        val manual = PhotoExport(library) { SensitiveRegions(emptyList(), emptyList()) }
        val source = manual.inspect(uri, ExportMode.CLOUD_REDACTED)
        val unchanged = manual.render(source, ExportEdits())
        assertThrows(ExportFailure.Unredacted::class.java) { manual.approve(unchanged, "fixture-worker") }
        val crop = PhotoRegion(0f, 0f, 1f, .5f)
        val redacted = manual.render(source, ExportEdits(crop = crop))
        assertEquals(200, redacted.preview.height)
        assertEquals(redacted.digest, manual.approve(redacted, "fixture-worker").artifact.digest)
        write(uri, "Mail changed@example.com")
        assertThrows(ExportFailure.Source::class.java) { manual.approve(redacted, "fixture-worker") }
    }

    @Test
    fun encryptedRoundTripWithHostWorker() {
        val key = InstrumentationRegistry.getArguments().getString("workerPublicKey")
        assumeTrue(key != null)
        val raw = Base64.decode(key, Base64.NO_WRAP)
        val worker = WorkerKey(sha256(raw), raw)
        fixture { uri ->
            val flow = context.photoExport
            for (mode in ExportMode.entries) {
                val artifact = flow.render(flow.inspect(uri, mode), ExportEdits())
                val approved = flow.approve(artifact, worker.id)
                flow.seal(approved, worker).use { request ->
                    val requestFile = File(context.cacheDir, "${mode.name}.request")
                    val resultFile = File(context.cacheDir, "${mode.name}.result")
                    resultFile.delete()
                    val staging = File(context.cacheDir, "${mode.name}.staging")
                    staging.writeBytes(request.encoded)
                    check(staging.renameTo(requestFile))
                    val deadline = SystemClock.elapsedRealtime() + 120_000
                    while (!resultFile.isFile && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                    assertTrue("Worker response missing", resultFile.isFile)
                    val result = resultFile.readBytes()
                    requestFile.delete()
                    resultFile.delete()
                    val json = org.json.JSONObject(result.decodeToString())
                    val ciphertext = Base64.decode(json.getString("box"), Base64.NO_WRAP)
                    ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
                    val corrupted = json.put("box", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).toString().encodeToByteArray()
                    assertThrows(GeneralSecurityException::class.java) { request.readResult(corrupted) }
                    val text = request.readResult(result).lowercase()
                    assertTrue(text.contains("public catalog"))
                    assertEquals(mode == ExportMode.CLOUD_FULL, text.contains("example.com"))
                    assertThrows(IllegalArgumentException::class.java) { request.readResult(result) }
                }
            }
        }
    }

    private fun fixture(action: (Uri) -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val fields = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "privacy-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PrivacyFixtures")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), fields))
        try {
            write(uri, "Mail private@example.com")
            action(uri)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun write(uri: Uri, identifier: String) {
        val image = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        try {
            Canvas(image).apply {
                drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
                drawText("PUBLIC CATALOG", 70f, 120f, paint)
                drawText(identifier, 70f, 275f, paint)
            }
            val png = ByteArrayOutputStream().use { buffer ->
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, buffer))
                buffer.toByteArray()
            }
            val metadata = "source\u0000synthetic-metadata-marker".encodeToByteArray()
            val type = "tEXt".encodeToByteArray()
            val inserted = ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.write(png, 0, png.size - 12)
                    output.writeInt(metadata.size)
                    output.write(type)
                    output.write(metadata)
                    output.writeInt(CRC32().apply { update(type); update(metadata) }.value.toInt())
                    output.write(png, png.size - 12, 12)
                }
                buffer.toByteArray()
            }
            writeFixtureBytes(uri, inserted)
        } finally {
            image.recycle()
        }
    }

    private fun writeFixtureBytes(uri: Uri, bytes: ByteArray) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
        checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
}
