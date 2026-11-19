package org.photolibrary.app.privacy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import org.photolibrary.app.index.PhotoProcessingException
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.decodePhoto
import org.photolibrary.app.subjects.PhotoRegion


internal sealed class ExportFailure : Exception() {
    class Source : ExportFailure()
    class Unsupported : ExportFailure()
    class Detector : ExportFailure()
    class Render : ExportFailure()
    class Unredacted : ExportFailure()
}

internal const val EXPORT_POLICY = "photo-export-v1"
internal const val EXPORT_TASK = "OCR_ENGLISH"
internal const val MAX_MANUAL_MASKS = 32

internal data class ExportSource(
    val uri: Uri,
    val revision: String?,
    val digest: String,
    val mode: ExportMode,
    val image: Bitmap,
    val sensitive: SensitiveRegions?,
)

internal data class ExportEdits(val masks: List<PhotoRegion> = emptyList(), val crop: PhotoRegion? = null)

internal class ExportArtifact(
    val source: ExportSource,
    val edits: ExportEdits,
    val preview: Bitmap,
    val effectiveCrop: PhotoRegion?,
    private val png: ByteArray,
) {
    val digest = sha256(png)
    val bytesCount get() = png.size
    fun copyBytes(): ByteArray = png.copyOf()
}

internal class ApprovedExport internal constructor(
    val id: String,
    val artifact: ExportArtifact,
    val destination: String,
    val task: String,
    val policy: String,
)

internal class PhotoExport(
    private val library: PhotoLibrary,
    private val sensitiveRegions: (Bitmap) -> SensitiveRegions,
) {
    fun inspect(uri: Uri, mode: ExportMode): ExportSource {
        val revision = library.currentRevision(uri)
        val bytes = library.readBoundedSource(uri, MAX_SOURCE_BYTES) ?: throw ExportFailure.Source()
        if (!isSupportedStill(bytes)) throw ExportFailure.Unsupported()
        val image = try {
            decodePhoto(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), EXPORT_EDGE, software = true, stillOnly = true)
        } catch (_: IOException) {
            throw ExportFailure.Unsupported()
        }
        try {
            val detected = if (mode == ExportMode.CLOUD_REDACTED) {
                try {
                    sensitiveRegions(image)
                } catch (_: PhotoProcessingException) {
                    throw ExportFailure.Detector()
                }
            } else null
            if (library.currentRevision(uri) != revision) throw ExportFailure.Source()
            return ExportSource(uri, revision, sha256(bytes), mode, image, detected)
        } catch (error: Exception) {
            image.recycle()
            throw error
        }
    }

    fun render(source: ExportSource, edits: ExportEdits): ExportArtifact {
        val image = source.image
        val masks = if (source.mode == ExportMode.CLOUD_REDACTED) {
            val findings = source.sensitive ?: throw ExportFailure.Detector()
            findings.masks + edits.masks
        } else {
            require(edits == ExportEdits())
            emptyList()
        }
        require(edits.masks.size <= MAX_MANUAL_MASKS)
        val frame = edits.crop?.pixels(image.width, image.height) ?: Rect(0, 0, image.width, image.height)
        val output = Bitmap.createBitmap(frame.width(), frame.height(), Bitmap.Config.ARGB_8888)
        val png = try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(image, frame, Rect(0, 0, output.width, output.height), null)
            val paint = Paint().apply { color = Color.BLACK }
            for (region in masks) {
                val mask = region.pixels(image.width, image.height)
                mask.offset(-frame.left, -frame.top)
                canvas.drawRect(mask, paint)
            }
            ByteArrayOutputStream().use { buffer ->
                if (!output.compress(Bitmap.CompressFormat.PNG, 100, buffer)) throw ExportFailure.Render()
                canonicalExport(buffer.toByteArray())
            }
        } finally {
            output.recycle()
        }
        val preview = BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw ExportFailure.Render()
        val effectiveCrop = edits.crop?.let {
            PhotoRegion(frame.left.toFloat() / image.width, frame.top.toFloat() / image.height,
                frame.right.toFloat() / image.width, frame.bottom.toFloat() / image.height)
        }
        return ExportArtifact(source, edits, preview, effectiveCrop, png)
    }

    fun approve(artifact: ExportArtifact, destination: String): ApprovedExport {
        require(destination.isNotBlank())
        val source = artifact.source
        if (source.mode == ExportMode.CLOUD_REDACTED) {
            val findings = source.sensitive ?: throw ExportFailure.Detector()
            val crop = artifact.effectiveCrop
            val cropped = crop != null && crop != PhotoRegion(0f, 0f, 1f, 1f)
            if (findings.masks.isEmpty() && artifact.edits.masks.isEmpty() && !cropped) {
                throw ExportFailure.Unredacted()
            }
        }
        validate(source)
        return ApprovedExport(UUID.randomUUID().toString(), artifact, destination, EXPORT_TASK, EXPORT_POLICY)
    }

    fun seal(approval: ApprovedExport, worker: WorkerKey): OcrRequest {
        validate(approval.artifact.source)
        return OcrRequest.seal(approval, worker)
    }

    fun validate(source: ExportSource) {
        val revision = library.currentRevision(source.uri)
        val current = library.readBoundedSource(source.uri, MAX_SOURCE_BYTES) ?: throw ExportFailure.Source()
        if (revision != source.revision || sha256(current) != source.digest || library.currentRevision(source.uri) != revision) {
            throw ExportFailure.Source()
        }
    }
}

internal fun PhotoRegion.toOriginal(crop: PhotoRegion?): PhotoRegion {
    if (crop == null) return this
    val width = crop.right - crop.left
    val height = crop.bottom - crop.top
    return PhotoRegion(
        (crop.left + left * width).coerceIn(crop.left, crop.right), (crop.top + top * height).coerceIn(crop.top, crop.bottom),
        (crop.left + right * width).coerceIn(crop.left, crop.right), (crop.top + bottom * height).coerceIn(crop.top, crop.bottom),
    )
}

private fun PhotoRegion.pixels(width: Int, height: Int) = Rect(
    floor(left * width).toInt(), floor(top * height).toInt(), ceil(right * width).toInt(), ceil(bottom * height).toInt(),
)

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
