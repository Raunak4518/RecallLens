package org.photolibrary.app.library

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.photolibrary.app.R
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.index.LocalPhotoIndexer
import org.photolibrary.app.index.PhotoIndexUnavailable
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.semantic.PhotoEmbedding

internal data class PhotoIndexRestorationBarrier(
    val text: CompletableDeferred<Unit>,
    val visual: CompletableDeferred<Unit>,
)

internal class PhotoIndexRestoration(
    private val scope: CoroutineScope,
    private val state: StateFlow<LibraryState>,
    private val mutableState: MutableStateFlow<LibraryState>,
    private val textIndexer: LocalPhotoIndexer<PhotoText>,
    private val visualIndexer: () -> LocalPhotoIndexer<PhotoEmbedding>?,
    private val searchableText: MutableMap<Uri, PhotoText>,
    private val embeddings: MutableMap<Uri, PhotoEmbedding>,
    private val withIndexedPhotos: (LibraryState) -> LibraryState,
    private val refreshSearchResults: () -> Unit,
    private val prepareTextSearch: () -> Unit,
    private val scheduleVisualSearch: () -> Unit,
) {
    var textJob: Job? = null
        private set
    var visualJob: Job? = null
        private set

    fun cancel() {
        textJob?.cancel()
        visualJob?.cancel()
    }

    fun start(photos: List<SelectedPhoto>, unavailable: UnavailableSources): PhotoIndexRestorationBarrier {
        val snapshot = PhotoSourceSnapshot.capture(state.value)
        val textRestored = CompletableDeferred<Unit>()
        val visualRestored = CompletableDeferred<Unit>()
        val inputs = photos.map { IndexPhoto(it.uri, it.sourceRevision) }
        restoreText(snapshot, inputs, unavailable, textRestored)
        val visual = visualIndexer()
        if (visual == null) visualRestored.complete(Unit)
        else restoreVisual(snapshot, inputs, unavailable, visual, visualRestored)
        return PhotoIndexRestorationBarrier(textRestored, visualRestored)
    }

    private fun restoreText(
        snapshot: PhotoSourceSnapshot,
        inputs: List<IndexPhoto>,
        unavailable: UnavailableSources,
        restored: CompletableDeferred<Unit>,
    ) {
        textJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    textIndexer.refresh(
                        photos = inputs,
                        restore = { saved, pending -> withContext(Dispatchers.Main.immediate) restoreText@{
                            if (!snapshot.matches(state.value)) return@restoreText
                            searchableText.putAll(saved)
                            mutableState.update {
                                withIndexedPhotos(it.copy(restoringIndex = false, indexing = pending > 0, textIndexed = saved.size))
                            }
                            if (state.value.searchMode != SearchMode.NAMED) refreshSearchResults()
                            restored.complete(Unit)
                        } },
                        processUris = emptySet(),
                        unavailable = unavailable,
                    ) { uri, text ->
                        if (text != null) withContext(Dispatchers.Main.immediate) publishText@{
                            if (!snapshot.matches(state.value)) return@publishText
                            searchableText[uri] = text
                            mutableState.update { withIndexedPhotos(it.copy(textIndexed = searchableText.size)) }
                            if (state.value.searchMode != SearchMode.NAMED) refreshSearchResults()
                        }
                    }
                }
                if (snapshot.matches(state.value)) mutableState.update { it.copy(restoringIndex = false, indexing = false) }
            } catch (_: PhotoIndexUnavailable) {
                if (snapshot.matches(state.value)) mutableState.update {
                    it.copy(restoringIndex = false, indexing = false, notice = R.string.library_index_unavailable)
                }
            }
        }.also { job -> job.invokeOnCompletion { restored.complete(Unit) } }
    }

    private fun restoreVisual(
        snapshot: PhotoSourceSnapshot,
        inputs: List<IndexPhoto>,
        unavailable: UnavailableSources,
        indexer: LocalPhotoIndexer<PhotoEmbedding>,
        restored: CompletableDeferred<Unit>,
    ) {
        visualJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    indexer.refresh(
                        photos = inputs,
                        restore = { saved, pending -> withContext(Dispatchers.Main.immediate) restoreVisual@{
                            if (!snapshot.matches(state.value)) return@restoreVisual
                            embeddings.putAll(saved)
                            mutableState.update { withIndexedPhotos(it.copy(visualIndexing = pending > 0, visualIndexed = saved.size)) }
                            prepareTextSearch()
                            refreshSearchResults()
                            restored.complete(Unit)
                        } },
                        processUris = emptySet(),
                        unavailable = unavailable,
                    ) { uri, vector ->
                        if (vector != null) withContext(Dispatchers.Main.immediate) publishVisual@{
                            if (!snapshot.matches(state.value)) return@publishVisual
                            embeddings[uri] = vector
                            mutableState.update { withIndexedPhotos(it.copy(visualIndexed = embeddings.size)) }
                            refreshSearchResults()
                        }
                    }
                }
                if (snapshot.matches(state.value)) {
                    mutableState.update { it.copy(visualIndexing = false) }
                    scheduleVisualSearch()
                }
            } catch (_: PhotoIndexUnavailable) {
                if (snapshot.matches(state.value)) mutableState.update {
                    it.copy(visualIndexing = false, notice = R.string.library_visual_unavailable)
                }
            }
        }.also { job -> job.invokeOnCompletion { restored.complete(Unit) } }
    }
}
