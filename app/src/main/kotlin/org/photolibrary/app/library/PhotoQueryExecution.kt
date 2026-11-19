package org.photolibrary.app.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import android.net.Uri
import org.photolibrary.app.subjects.NamedLibrary

internal data class PhotoQuerySnapshot(
    val query: String,
    val mode: SearchMode,
    val galleryAccess: GalleryAccess,
    val photos: List<SelectedPhoto>,
    val named: NamedLibrary,
    val folders: List<PhotoFolder>,
    val unavailableVolumes: Set<String>,
    val imageRevision: Long,
    val loading: Boolean,
    val visualSeed: Uri? = null,
    val indexedUris: Set<Uri> = emptySet(),
    val visualSeedMode: VisualSeedMode = VisualSeedMode.SIMILAR,
) {
    companion object {
        fun capture(state: LibraryState) = PhotoQuerySnapshot(
            state.query, state.searchMode, state.galleryAccess, state.photos, state.named,
            state.folders, state.unavailableVolumes, state.imageRevision, state.loading, state.visualSeed, state.indexedUris, state.visualSeedMode,
        )
    }
}

internal class PhotoQueryPublication(private val valid: () -> Boolean) {
    fun publish(change: () -> Unit) {
        if (valid()) change()
    }
}

internal class PhotoQueryExecution(
    private val scope: CoroutineScope,
    private val current: () -> PhotoQuerySnapshot,
) {
    private var generation = 0L
    private var job: Job? = null
    private var pending: (() -> Unit)? = null
    val isActive get() = job?.isActive == true

    fun cancel() {
        generation++
        pending = null
        job?.cancel()
        job = null
    }

    fun launch(snapshot: PhotoQuerySnapshot, whenIdle: Boolean = false, work: suspend (PhotoQueryPublication) -> Unit) {
        if (whenIdle && isActive) {
            generation++
            pending = { launch(snapshot, work = work) }
            return
        }
        cancel()
        val request = generation
        job = scope.launch {
            val owner = currentCoroutineContext()[Job]
            val publication = PhotoQueryPublication { owner?.isActive == true && request == generation && snapshot == current() }
            try {
                work(publication)
            } finally {
                if (job === owner) {
                    val next = pending
                    pending = null
                    job = null
                    if (scope.coroutineContext[Job]?.isActive != false) next?.invoke()
                }
            }
        }
    }
}
