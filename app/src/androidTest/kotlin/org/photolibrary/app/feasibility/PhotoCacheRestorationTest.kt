package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.library.LibraryState
import org.photolibrary.app.library.PhotoCacheRestoration
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.PhotoFolder
import org.photolibrary.app.library.PhotoSourceSnapshot
import org.photolibrary.app.library.SelectedPhoto

@RunWith(AndroidJUnit4::class)
class PhotoCacheRestorationTest {
    private val photos = List(7) { position ->
        SelectedPhoto(Uri.parse("content://fixture/cache/$position"), "$position.jpg", PhotoOrigin.GALLERY, "first")
    }
    private val initial = LibraryState(photos = photos, galleryAccess = GalleryAccess.ALL, loading = false, imageRevision = 1)

    @Test
    fun sevenPhotoReadCannotRepopulateFourPhotoLibraryAfterReload() = runBlocking {
        withTimeout(10_000) {
            var state = initial
            val cache = mutableMapOf<Uri, String>()
            val restoration = PhotoCacheRestoration { state }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = async {
                restoration.restore(PhotoSourceSnapshot.capture(initial), {
                    started.complete(Unit)
                    release.await()
                    photos.associate { it.uri to "old" }
                }) { cache.putAll(it) }
            }
            started.await()
            state = initial.copy(photos = photos.take(4), imageRevision = 2)
            cache.clear()
            release.complete(Unit)
            assertFalse(old.await())
            assertTrue(cache.isEmpty())
            assertTrue(restoration.restore(PhotoSourceSnapshot.capture(state), {
                state.photos.associate { it.uri to "current" }
            }) { cache.putAll(it) })
            assertEquals(photos.take(4).map { it.uri }.toSet(), cache.keys)
            assertEquals(4, cache.size)
            assertTrue(cache.size <= state.photos.size)
        }
    }

    @Test
    fun sourceEditScopeLoadingAndGenerationChangesSuppressAwaitedPayloads() = runBlocking {
        withTimeout(10_000) {
            val changes = listOf(
                initial.copy(photos = photos.map { it.copy(sourceRevision = "edited") }),
                initial.copy(galleryAccess = GalleryAccess.SELECTED),
                initial.copy(galleryAccess = GalleryAccess.NONE),
                initial.copy(folders = listOf(PhotoFolder(Uri.parse("content://fixture/tree/new"), "New folder"))),
                initial.copy(unavailableVolumes = setOf("detached-volume")),
                initial.copy(imageRevision = 2),
                initial.copy(loading = true),
            )
            for (changed in changes) {
                var state = initial
                var published = false
                val restoration = PhotoCacheRestoration { state }
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val accepted = async {
                    restoration.restore(PhotoSourceSnapshot.capture(initial), {
                        started.complete(Unit)
                        release.await()
                        "stale"
                    }) { published = true }
                }
                started.await()
                state = changed
                release.complete(Unit)
                assertFalse(accepted.await())
                assertFalse(published)
            }
        }
    }

    @Test
    fun loadingSnapshotOrChangedSourceRejectsBeforeReadingAndQueryChangesDoNotInvalidateCache() = runBlocking {
        var state = initial.copy(loading = true)
        val restoration = PhotoCacheRestoration { state }
        assertFalse(restoration.restore(PhotoSourceSnapshot.capture(state), { error("Loading source was read") }) {})
        state = initial
        assertFalse(restoration.restore(PhotoSourceSnapshot.capture(initial.copy(imageRevision = 0)), {
            error("Old source was read")
        }) {})
        val snapshot = PhotoSourceSnapshot.capture(state)
        state = state.copy(query = "different query", searching = true)
        var value: String? = null
        assertTrue(restoration.restore(snapshot, { "fresh" }) { value = it })
        assertEquals("fresh", value)
    }

    @Test
    fun cancelledOwnerCannotApplyEvenWhenTheReadIgnoresCancellation() = runBlocking {
        withTimeout(10_000) {
            val restoration = PhotoCacheRestoration { initial }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val readFinished = CompletableDeferred<Unit>()
            var applied = false
            val owner = launch {
                restoration.restore(PhotoSourceSnapshot.capture(initial), {
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                        readFinished.complete(Unit)
                        "stale"
                    }
                }) { applied = true }
            }
            started.await()
            owner.cancel()
            release.complete(Unit)
            owner.join()
            assertTrue(readFinished.isCompleted)
            assertTrue(owner.isCancelled)
            assertFalse(applied)
        }
    }
}
