package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.graphics.ImageDecoder
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.privacy.photoExport
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.library.decodePhoto
import org.photolibrary.app.privacy.ExportEdits
import org.photolibrary.app.privacy.ExportMode
import org.photolibrary.app.privacy.OcrRequest
import org.photolibrary.app.privacy.WorkerKey
import org.photolibrary.app.privacy.sha256
import org.photolibrary.app.subjects.PhotoRegion

@RunWith(AndroidJUnit4::class)
class HybridPhotoOcrTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
    private val assets = instrumentation.context.assets

    @Test
    fun compareRememberedText() {
        val arguments = InstrumentationRegistry.getArguments()
        val configuredKey = arguments.getString("workerPublicKey")
        val configuredRun = arguments.getString("runId")
        assumeTrue("Start the hybrid OCR benchmark runner", configuredKey != null && configuredRun != null)
        val key = checkNotNull(configuredKey)
        val runId = checkNotNull(configuredRun)
        require(runId.matches(Regex("[0-9a-f]{32}")))
        val raw = Base64.decode(key, Base64.NO_WRAP)
        val worker = WorkerKey(sha256(raw), raw)
        val manifest = hybridManifest()
        val photos = manifest.getJSONArray("images")
        val variants = listOf("local", "full", "redacted")
        val index = variants.associateWith { linkedMapOf<String, PhotoText>() }
        val measurements = JSONArray()
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val setupStarted = SystemClock.elapsedRealtimeNanos()
        val recognizer = LocalTextIndex.recognizer()
        val setupMs = hybridElapsedMs(setupStarted)
        recognizer.use {
            for (ordinal in 0 until photos.length()) {
                val photo = photos.getJSONObject(ordinal)
                val id = photo.getString("id")
                val asset = "$HYBRID_ASSETS/${photo.getString("file")}"
                val decodeStarted = SystemClock.elapsedRealtimeNanos()
                val bitmap = decodePhoto(ImageDecoder.createSource(assets, asset), recognizer.model.maxImageEdge, software = true)
                val detail = JSONObject().put("id", id).put("local_decode_ms", hybridElapsedMs(decodeStarted))
                    .put("width", bitmap.width).put("height", bitmap.height)
                try {
                    val ocrStarted = SystemClock.elapsedRealtimeNanos()
                    index.getValue("local")[id] = recognizer.process(bitmap)
                    detail.put("local_ocr_ms", hybridElapsedMs(ocrStarted))
                } finally {
                    bitmap.recycle()
                }
                val resolver = context.contentResolver
                val fields = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "hybrid-bench-$id.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HybridOcrFixtures")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), fields))
                try {
                    assets.open(asset).use { input ->
                        checkNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) }
                    }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    val exporter = context.photoExport
                    for ((label, mode) in listOf("full" to ExportMode.CLOUD_FULL, "redacted" to ExportMode.CLOUD_REDACTED)) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        val source = exporter.inspect(uri, mode)
                        val redaction = source.sensitive
                        val edits = if (redaction != null) {
                            detail.put("automatic_text_masks", redaction.identifiers.size).put("automatic_face_masks", redaction.faces.size)
                            ExportEdits(masks = listOf(photo.getJSONArray("sensitive").toRegion()))
                        } else ExportEdits()
                        val artifact = exporter.render(source, edits)
                        val approved = exporter.approve(artifact, worker.id)
                        exporter.seal(approved, worker).use { request ->
                            detail.put("${label}_prepare_ms", hybridElapsedMs(started))
                            detail.put("${label}_bytes", artifact.bytesCount)
                            index.getValue(label)[id] = exchange(request, "$runId-$ordinal-$label").toHybridPhotoText()
                        }
                        artifact.preview.recycle()
                        source.image.recycle()
                    }
                } finally {
                    resolver.delete(uri, null, null)
                }
                measurements.put(detail)
            }
        }
        val report = JSONObject().put("adapter_setup_ms", setupMs).put("images", measurements)
        for ((variant, records) in index) report.put(variant, hybridMatches(manifest, records))
        context.openFileOutput("hybrid-ocr-android.json", 0).bufferedWriter().use { it.write(report.toString()) }
    }

    private fun exchange(request: OcrRequest, id: String): String {
        val outgoing = File(context.cacheDir, "$id.request")
        val staging = File(context.cacheDir, "$id.staging")
        val incoming = File(context.cacheDir, "$id.result")
        staging.writeBytes(request.encoded)
        check(staging.renameTo(outgoing))
        try {
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (!incoming.isFile && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(80)
            check(incoming.isFile) { "Worker did not return $id" }
            return request.readResult(incoming.readBytes())
        } finally {
            outgoing.delete()
            incoming.delete()
        }
    }
}

internal const val HYBRID_ASSETS = "hybrid-ocr"

internal fun hybridManifest(): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
    .open("$HYBRID_ASSETS/manifest.json").bufferedReader().use { JSONObject(it.readText()) }

internal fun hybridElapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

internal fun String.toHybridPhotoText() = PhotoText(lineSequence().filter(String::isNotBlank).map { TextLine(it, "und", null) }.toList())

internal fun hybridMatches(manifest: JSONObject, index: Map<String, PhotoText>): JSONArray {
    val queries = manifest.getJSONArray("queries")
    return JSONArray().apply {
        for (ordinal in 0 until queries.length()) {
            val query = queries.getJSONObject(ordinal)
            val normal = normalizePhotoText(query.getString("text"))
            val matches = index.filterValues { it.matches(normal) }.keys
            put(JSONObject().put("id", query.getString("id")).put("matches", JSONArray(matches)))
        }
    }
}

private fun JSONArray.toRegion() = PhotoRegion(getDouble(0).toFloat(), getDouble(1).toFloat(), getDouble(2).toFloat(), getDouble(3).toFloat())
