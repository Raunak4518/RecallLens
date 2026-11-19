package org.photolibrary.app.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PhotoSourceSnapshot(
    val imageRevision: Long,
    val photos: List<SelectedPhoto>,
    val galleryAccess: GalleryAccess,
    val folders: List<PhotoFolder>,
    val unavailableVolumes: Set<String>,
    val loading: Boolean,
) {
    fun matches(state: LibraryState): Boolean = !loading && !state.loading && this == capture(state)

    companion object {
        fun capture(state: LibraryState) = PhotoSourceSnapshot(
            state.imageRevision, state.photos.toList(), state.galleryAccess,
            state.folders.toList(), state.unavailableVolumes.toSet(), state.loading,
        )
    }
}

internal class PhotoCacheRestoration(private val current: () -> LibraryState) {
    suspend fun <T> restore(snapshot: PhotoSourceSnapshot, read: suspend () -> T, apply: (T) -> Unit): Boolean {
        currentCoroutineContext().ensureActive()
        if (!snapshot.matches(current())) return false
        val result = read()
        currentCoroutineContext().ensureActive()
        if (!snapshot.matches(current())) return false
        apply(result)
        return true
    }
}
