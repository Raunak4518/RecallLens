package org.photolibrary.app.index

import android.net.Uri
import android.os.SystemClock
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.photolibrary.app.library.LibrarySources
import org.photolibrary.app.library.UnavailableSources
import org.photolibrary.app.semantic.PhotoEmbedding

internal data class IndexingProgress(
    val checkpoint: IndexingCheckpoint = IndexingCheckpoint(),
    val textReady: Int = 0,
    val visualReady: Int = 0,
    val generation: Long = 0,
    val textUpdated: Map<Uri, Long> = emptyMap(),
    val visualUpdated: Map<Uri, Long> = emptyMap(),
    val unavailable: Boolean = false,
)

internal class LocalIndexCoordinator(
    control: IndexingControl,
    listSources: suspend () -> LibrarySources,
    text: LocalPhotoIndexer<PhotoText>,
    visual: () -> LocalPhotoIndexer<PhotoEmbedding>?,
    modelRevision: () -> String,
    batchSize: Int = 8,
    visualBatchSize: Int = batchSize,
    private val fair: Boolean = false,
) {
    private val control = control
    private val listSources = listSources
    private val text = text
    private val visual = visual
    private val modelRevision = modelRevision
    private val batchSize = batchSize
    private val visualBatchSize = visualBatchSize
    private val lock = Mutex()
    private val mutableProgress = MutableStateFlow(IndexingProgress())
    private var accessible = emptySet<Uri>()
    private var publishedRevision: String? = null
    val progress = mutableProgress.asStateFlow()

    init {
        require(batchSize > 0 && visualBatchSize >= batchSize)
    }

    suspend fun request() = lock.withLock {
        control.request()
        publish()
    }

    suspend fun restoreProgress() = lock.withLock {
        if (progress.value.generation == 0L) publish(control.read())
    }

    suspend fun pause(paused: Boolean) {
        control.pause(paused)
        publish()
    }

    suspend fun runBatch(): Boolean = lock.withLock {
        val persisted = control.read()
        if (persisted.paused) {
            if (progress.value.checkpoint != persisted) publish(persisted)
            return@withLock false
        }
        val (_, folders, listed, unavailableVolumes) = listSources()
        val photos = listed.sortedBy { it.uri.toString() }
        val unavailable = UnavailableSources.from(folders, unavailableVolumes)
        val inputs = photos.map { IndexPhoto(it.uri, it.sourceRevision) }
        accessible = inputs.mapTo(hashSetOf()) { it.uri }
        val scene = visual()
        val stage = if (scene == null) IndexingStage.TEXT else IndexingStage.VISUAL
        val unmounted = folders.filterNot { it.available }.map { it.uri.toString() }.sorted()
        val schedule = if (fair) "time-sliced-v1" else "visual-first-v1"
        val revision = signature(inputs, "$schedule:${modelRevision()}", unmounted, unavailableVolumes.sorted())
        val checkpoint = control.begin(revision, inputs.size, stage, fair)
        if (checkpoint.paused) return@withLock false
        val previous = progress.value
        val changedSources = publishedRevision != revision
        if (checkpoint.finished) {
            if (changedSources) publish(checkpoint, text.currentPhotos(inputs, unavailable).size,
                scene?.currentPhotos(inputs, unavailable).orEmpty().size)
            publishedRevision = revision
            return@withLock false
        }
        if (checkpoint.stage == IndexingStage.VISUAL && scene != null) {
            val textReady = if (changedSources) text.currentPhotos(inputs, unavailable).size else previous.textReady
            val limit = if (fair || checkpoint.cursor == 0) batchSize else visualBatchSize
            val batch = runStage(scene, checkpoint, inputs, unavailable, limit)
            publish(batch.checkpoint, textReady, batch.ready, visualChanged = batch.changed)
        } else {
            val visualReady = if (changedSources) scene?.currentPhotos(inputs, unavailable).orEmpty().size else previous.visualReady
            val batch = runStage(text, checkpoint, inputs, unavailable, if (fair) minOf(batchSize, 2) else batchSize)
            publish(batch.checkpoint, batch.ready, visualReady, textChanged = batch.changed)
        }
        publishedRevision = revision
        val updated = progress.value.checkpoint
        !updated.paused && !updated.finished
    }

    fun unavailable() {
        mutableProgress.update { it.copy(unavailable = true) }
    }

    private fun publish(
        checkpoint: IndexingCheckpoint = control.read(),
        textReady: Int = progress.value.textReady,
        visualReady: Int = progress.value.visualReady,
        textChanged: Set<Uri> = emptySet(),
        visualChanged: Set<Uri> = emptySet(),
    ) {
        mutableProgress.update { previous ->
            val generation = previous.generation + 1
            fun updates(old: Map<Uri, Long>, changed: Set<Uri>): Map<Uri, Long> {
                if (changed.isEmpty() && old.keys.all { it in accessible }) return old
                return old.filterKeys { it in accessible }.toMutableMap().apply { changed.forEach { put(it, generation) } }
            }
            IndexingProgress(checkpoint, textReady, visualReady, generation,
                updates(previous.textUpdated, textChanged), updates(previous.visualUpdated, visualChanged))
        }
    }

    private suspend fun <Output : Any> runStage(index: LocalPhotoIndexer<Output>, checkpoint: IndexingCheckpoint,
        inputs: List<IndexPhoto>, unavailable: UnavailableSources, limit: Int): IndexingBatch {
        val started = if (fair) SystemClock.elapsedRealtime() else 0L
        val ready = index.currentPhotos(inputs, unavailable).toMutableSet()
        val selected = LinkedHashSet<Uri>()
        var next = inputs.size
        var selectedCount = 0
        var afterSelected = checkpoint.cursor
        for (i in checkpoint.cursor until inputs.size) {
            val uri = inputs[i].uri
            if (uri in ready) continue
            if (selectedCount == limit) {
                next = afterSelected
                break
            }
            selected.add(uri)
            selectedCount++
            afterSelected = i + 1
        }
        var completed = false
        try {
            if (selected.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val current = control.read()
                if (current.paused) return IndexingBatch(current, ready.size, emptySet())
                index.refresh(inputs, { _, _ -> }, selected, unavailable, writeBatchSize = 8) { uri, output ->
                    if (output != null) ready.add(uri)
                }
            }
            currentCoroutineContext().ensureActive()
            val failed = checkpoint.failed + selected.count { it !in ready }
            val elapsed = if (fair) SystemClock.elapsedRealtime() - started else null
            val updated = control.checkpoint(checkpoint, next, failed, elapsed)
            completed = true
            return IndexingBatch(updated, ready.size, selected)
        } finally {
            if (!completed && selected.isNotEmpty()) publishInterrupted(checkpoint.stage, selected)
        }
    }

    private fun publishInterrupted(stage: IndexingStage, changed: Set<Uri>) {
        mutableProgress.update { previous ->
            val generation = previous.generation + 1
            val updates = changed.associateWith { generation }
            if (stage == IndexingStage.VISUAL) previous.copy(generation = generation, visualUpdated = previous.visualUpdated + updates)
            else previous.copy(generation = generation, textUpdated = previous.textUpdated + updates)
        }
    }

    private data class IndexingBatch(val checkpoint: IndexingCheckpoint, val ready: Int, val changed: Set<Uri>)
}

private fun signature(photos: List<IndexPhoto>, model: String, unavailableFolders: List<String>, unavailableVolumes: List<String>): String {
    val hash = MessageDigest.getInstance("SHA-256")
    val size = java.nio.ByteBuffer.allocate(4)
    fun append(value: String) {
        val bytes = value.encodeToByteArray()
        size.clear()
        size.putInt(bytes.size)
        hash.update(size.array())
        hash.update(bytes)
    }
    append(model)
    append(unavailableFolders.size.toString())
    unavailableFolders.forEach(::append)
    append(unavailableVolumes.size.toString())
    unavailableVolumes.forEach(::append)
    for (photo in photos) {
        append(photo.uri.toString())
        append(photo.sourceRevision.orEmpty())
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
