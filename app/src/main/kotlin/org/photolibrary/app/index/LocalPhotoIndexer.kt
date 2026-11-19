package org.photolibrary.app.index

import android.graphics.Bitmap
import android.net.Uri
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.photolibrary.app.library.UnavailableSources

internal data class PhotoEvidenceUpgrade<Output>(val previous: ModelRevision, val enrich: (Bitmap, Output) -> Output)

internal class LocalPhotoIndexer<Output : Any> private constructor(
    private val loadImage: suspend (Uri, Int) -> Bitmap?,
    private val createProcessor: () -> PhotoProcessor<Output>,
    private val openIndex: () -> PhotoIndexSession<Output>,
    private val readRevision: (Uri) -> String?,
    private val model: ModelRevision?,
    private val refine: (suspend (Uri, Bitmap, Output, PhotoProcessor<Output>) -> Output)? = null,
    private val upgrade: PhotoEvidenceUpgrade<Output>? = null,
    reuseEquivalentPixels: Boolean = false,
) {
    constructor(
        loadImage: suspend (Uri, Int) -> Bitmap?,
        processorFactory: PhotoProcessorFactory<Output>,
        openIndex: () -> PhotoIndexSession<Output>,
        readRevision: (Uri) -> String?,
        upgrade: PhotoEvidenceUpgrade<Output>? = null,
        reuseEquivalentPixels: Boolean = false,
    ) : this(loadImage, processorFactory::open, openIndex, readRevision, processorFactory.model,
        upgrade = upgrade, reuseEquivalentPixels = reuseEquivalentPixels)

    constructor(
        loadImage: suspend (Uri, Int) -> Bitmap?,
        createProcessor: () -> PhotoProcessor<Output>,
        openIndex: () -> PhotoIndexSession<Output>,
        readRevision: (Uri) -> String?,
    ) : this(loadImage, createProcessor, openIndex, readRevision, null)

    constructor(
        loadImage: suspend (Uri, Int) -> Bitmap?,
        processorFactory: PhotoProcessorFactory<Output>,
        openIndex: () -> PhotoIndexSession<Output>,
        readRevision: (Uri) -> String?,
        effectiveModel: ModelRevision,
        refine: suspend (Uri, Bitmap, Output, PhotoProcessor<Output>) -> Output,
    ) : this(loadImage, processorFactory::open, openIndex, readRevision, effectiveModel, refine)

    private val lock = Mutex()
    private val recentOutputs = if (reuseEquivalentPixels) android.util.LruCache<Pair<ModelRevision, String>, Output>(64) else null

    suspend fun currentPhotos(photos: List<IndexPhoto>, unavailable: UnavailableSources = UnavailableSources.NONE): Set<Uri> = lock.withLock {
        openIndex().use { index ->
            val revision = model ?: createProcessor().use { it.model }
            index.current(photos.associate { it.uri to it.sourceRevision }, revision, unavailable)
        }
    }

    suspend fun readCached(
        photos: List<IndexPhoto>, selected: Set<Uri>, unavailable: UnavailableSources = UnavailableSources.NONE,
    ): Map<Uri, Output> = lock.withLock {
        openIndex().use { index ->
            val revision = model ?: createProcessor().use { it.model }
            val records = index.reconcile(photos.mapTo(hashSetOf()) { it.uri }, selected, unavailable)
            buildMap {
                for (photo in photos) {
                    val record = records[photo.uri] ?: continue
                    if (record.model != revision && record.model != upgrade?.previous) continue
                    val current = if (photo.sourceRevision != null) record.sourceRevision == photo.sourceRevision
                        else record.pixelRevision != null && currentPixelRevision(photo.uri, revision.maxImageEdge) == record.pixelRevision
                    if (current) put(photo.uri, record.output)
                }
            }
        }
    }

    suspend fun refresh(
        photos: List<IndexPhoto>,
        restore: suspend (Map<Uri, Output>, Int) -> Unit,
        publish: suspend (Uri, Output?) -> Unit,
    ) = refresh(photos, restore, null, publish = publish)

    suspend fun refresh(
        photos: List<IndexPhoto>,
        restore: suspend (Map<Uri, Output>, Int) -> Unit,
        processUris: Set<Uri>?,
        unavailable: UnavailableSources = UnavailableSources.NONE,
        writeBatchSize: Int = 1,
        publish: suspend (Uri, Output?) -> Unit,
    ) = lock.withLock {
        require(writeBatchSize in 1..32)
        openIndex().use { index ->
            val accessible = photos.mapTo(hashSetOf()) { it.uri }
            val read = processUris?.takeIf { it.isNotEmpty() } ?: accessible
            val records = index.reconcile(accessible, read, unavailable)
            currentCoroutineContext().ensureActive()
            if (photos.isEmpty()) {
                restore(emptyMap(), 0)
                return@withLock
            }
            val processor = lazy(LazyThreadSafetyMode.NONE, createProcessor)
            try {
                val revision = model ?: processor.value.model
                val ready = mutableMapOf<Uri, Output>()
                val pending = ArrayList<IndexPhoto>(minOf(photos.size, processUris?.size ?: photos.size))
                var pendingCount = 0
                for (photo in photos) {
                    val previous = records[photo.uri]
                    val fresh = previous != null && (previous.model == revision || previous.model == upgrade?.previous) &&
                        if (photo.sourceRevision != null) previous.sourceRevision == photo.sourceRevision
                        else processUris?.isEmpty() == true && previous.pixelRevision != null &&
                            currentPixelRevision(photo.uri, revision.maxImageEdge) == previous.pixelRevision
                    if (fresh) ready[photo.uri] = previous.output
                    if (!fresh || previous?.model != revision) {
                        pendingCount++
                        if (processUris == null || photo.uri in processUris) pending.add(photo)
                    }
                }
                restore(ready, pendingCount)
                var start = 0
                while (start < pending.size) {
                    val end = minOf(start + writeBatchSize, pending.size)
                    val candidates = pending.subList(start, end)
                    val batch = if (candidates.any { it.sourceRevision == null }) candidates.take(1) else candidates
                    val prepared = batch.map { photo ->
                        currentCoroutineContext().ensureActive()
                        photo to preparePhoto(photo, records[photo.uri], revision, processor, index)
                    }
                    val results = prepared.map { (photo, record) ->
                        currentCoroutineContext().ensureActive()
                        val current = record != null && if (photo.sourceRevision != null) {
                            readRevision(photo.uri) == photo.sourceRevision
                        } else {
                            currentPixelRevision(photo.uri, revision.maxImageEdge) == record.pixelRevision
                        }
                        if (!current && record != null) index.remove(photo.uri)
                        photo.uri to record.takeIf { current }
                    }
                    currentCoroutineContext().ensureActive()
                    val writes = results.mapNotNull { (uri, record) -> record?.let { uri to it } }
                    tracePhotoStage("photos.index.save") {
                        if (writes.size == 1) {
                            val (uri, record) = writes.single()
                            index.save(uri, record)
                        } else if (writes.isNotEmpty()) index.saveBatch(writes)
                    }
                    for ((uri, record) in results) {
                        currentCoroutineContext().ensureActive()
                        if (record != null && record.pixelRevision != null) {
                            recentOutputs?.put(revision to record.pixelRevision, record.output)
                        }
                        publish(uri, record?.output)
                    }
                    start += batch.size
                }
            } finally {
                if (processor.isInitialized()) processor.value.close()
            }
        }
    }

    private suspend fun preparePhoto(
        photo: IndexPhoto,
        previous: IndexedPhoto<Output>?,
        model: ModelRevision,
        processor: Lazy<PhotoProcessor<Output>>,
        index: PhotoIndexSession<Output>,
    ): IndexedPhoto<Output>? {
        val (uri, sourceRevision) = photo
        val image = loadImage(uri, model.maxImageEdge) ?: run {
            index.remove(uri)
            return null
        }
        val pixelRevision: String
        val output: Output
        try {
            pixelRevision = pixelRevision(image)
            val enrich = upgrade?.takeIf { previous?.model == it.previous && previous.pixelRevision == pixelRevision }
            output = when {
                enrich != null && previous != null -> enrich.enrich(image, previous.output)
                previous?.pixelRevision == pixelRevision && previous.model == model &&
                    (refine == null || previous.sourceRevision == sourceRevision) -> previous.output
                else -> recentOutputs?.get(model to pixelRevision) ?: run {
                    if (previous != null) index.remove(uri)
                    val initial = tracePhotoStage("photos.index.inference") { processor.value.process(image) }
                    if (sourceRevision != null && refine != null) refine.invoke(uri, image, initial, processor.value)
                    else initial
                }
            }
        } catch (_: PhotoProcessingException) {
            return null
        } finally {
            image.recycle()
        }

        return IndexedPhoto(sourceRevision, pixelRevision, model, output)
    }

    private suspend fun currentPixelRevision(uri: Uri, edge: Int): String? {
        val bitmap = loadImage(uri, edge) ?: return null
        return try {
            pixelRevision(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

}

internal fun pixelRevision(image: Bitmap): String = tracePhotoStage("photos.index.pixelDigest") {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(ByteBuffer.allocate(8).putInt(image.width).putInt(image.height).array())
    val width = image.width
    val rowsPerStrip = (16_384 / width).coerceIn(1, image.height)
    val pixels = IntArray(width * rowsPerStrip)
    val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
    val values = bytes.asIntBuffer()
    var y = 0
    while (y < image.height) {
        val rows = minOf(rowsPerStrip, image.height - y)
        val count = width * rows
        image.getPixels(pixels, 0, width, 0, y, width, rows)
        values.clear()
        values.put(pixels, 0, count)
        digest.update(bytes.array(), 0, count * Int.SIZE_BYTES)
        y += rows
    }
    android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP)
}
