package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.library.LibraryState
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.PhotoQueryExecution
import org.photolibrary.app.library.PhotoQuerySnapshot
import org.photolibrary.app.library.SearchMode
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.groupPhotoResults
import org.photolibrary.app.subjects.planPhotoQuery

@RunWith(AndroidJUnit4::class)
class PhotoQueryExecutionTest {
    private fun queryTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000, block) }

    private val confirmed = Uri.parse("content://fixture/query/confirmed")
    private val lookalike = Uri.parse("content://fixture/query/lookalike")
    private val named = NamedLibrary(
        listOf(NamedSubject(1, "My car", SubjectKind.OBJECT, emptyList())),
        listOf(SubjectReference(1, confirmed, "source:one", null)),
    )
    private val initial = LibraryState(
        galleryAccess = GalleryAccess.ALL, query = "my car", searchMode = SearchMode.NAMED,
        photos = listOf(SelectedPhoto(confirmed, "car.jpg", PhotoOrigin.GALLERY, "one"),
            SelectedPhoto(lookalike, "similar.jpg", PhotoOrigin.GALLERY, "two")),
        named = named, loading = false, imageRevision = 1,
    )

    @Test
    fun advancingCachedEvidenceQueuesOnlyLatestRerunAndWithholdsOlderRanking() = queryTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val snapshot = PhotoQuerySnapshot.capture(initial)
        val execution = PhotoQueryExecution(scope) { snapshot }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val latest = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        var supersededRun = false
        try {
            execution.launch(snapshot) { publication ->
                started.complete(Unit)
                release.await()
                publication.publish { results += "old embedding index" }
            }
            started.await()
            execution.launch(snapshot, whenIdle = true) { publication ->
                supersededRun = true
                publication.publish { results += "intermediate embedding index" }
            }
            execution.launch(snapshot, whenIdle = true) { publication ->
                publication.publish { results += "latest embedding index" }
                latest.complete(Unit)
            }
            release.complete(Unit)
            latest.await()
            assertFalse(supersededRun)
            assertEquals(listOf("latest embedding index"), results)
        } finally { scope.cancel() }
    }

    @Test
    fun cancellingOwningScopeRejectsNonCancellableCompletionWithoutAnotherRequest() = queryTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val snapshot = PhotoQuerySnapshot.capture(initial)
        val execution = PhotoQueryExecution(scope) { snapshot }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var published = false
        try {
            execution.launch(snapshot) { publication ->
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    publication.publish { published = true }
                    finished.complete(Unit)
                }
            }
            started.await()
            scope.cancel()
            release.complete(Unit)
            finished.await()
            assertFalse(published)
        } finally { scope.cancel() }
    }

    @Test
    fun newerSameQuerySuppressesLateResultAndLateErrorEvenWhenWorkIgnoresCancellation() = queryTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val snapshot = PhotoQuerySnapshot.capture(initial)
        val execution = PhotoQueryExecution(scope) { snapshot }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val output = mutableListOf<String>()
        try {
            execution.launch(snapshot) { publication ->
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    publication.publish { output += "stale result" }
                    publication.publish { output += "stale error" }
                    finished.complete(Unit)
                }
            }
            started.await()
            val latest = CompletableDeferred<Unit>()
            execution.launch(snapshot) { publication ->
                publication.publish { output += "current result" }
                latest.complete(Unit)
            }
            latest.await()
            release.complete(Unit)
            finished.await()
            assertEquals(listOf("current result"), output)
        } finally { scope.cancel() }
    }

    @Test
    fun modeNamedScopeSourceRevisionAndLoadingChangesRejectOldSnapshots() = queryTest {
        val scope = CoroutineScope(coroutineContext + Job())
        var current = PhotoQuerySnapshot.capture(initial)
        val execution = PhotoQueryExecution(scope) { current }
        val changed = listOf(
            initial.copy(searchMode = SearchMode.TEXT),
            initial.copy(galleryAccess = GalleryAccess.NONE),
            initial.copy(named = NamedLibrary(named.subjects, emptyList())),
            initial.copy(named = named.copy(selfIdentity = org.photolibrary.app.subjects.SelfIdentity(null))),
            initial.copy(photos = initial.photos.drop(1)),
            initial.copy(photos = initial.photos.map { it.copy(sourceRevision = "edited") }),
            initial.copy(photos = initial.photos.map { it.copy(capturedAtMillis = 1_700_000_000_000L) }),
            initial.copy(unavailableVolumes = setOf("removable-volume")),
            initial.copy(imageRevision = 2),
            initial.copy(loading = true),
        )
        try {
            for (state in changed) {
                current = PhotoQuerySnapshot.capture(initial)
                val snapshot = current
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                var published = false
                execution.launch(snapshot) { publication ->
                    started.complete(Unit)
                    release.await()
                    publication.publish { published = true }
                    finished.complete(Unit)
                }
                started.await()
                current = PhotoQuerySnapshot.capture(state)
                release.complete(Unit)
                finished.await()
                assertFalse("Published stale query after state transition: " + state, published)
            }
        } finally { scope.cancel() }
    }

    @Test
    fun confirmedOnlyResultsStayConfirmedAndExplicitCancellationRejectsLatePublication() = queryTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val snapshot = PhotoQuerySnapshot.capture(initial)
        val execution = PhotoQueryExecution(scope) { snapshot }
        val published = mutableListOf<Uri>()
        val complete = CompletableDeferred<Unit>()
        try {
            execution.launch(snapshot) { publication ->
                val plan = planPhotoQuery(snapshot.query, snapshot.named, namedOnly = true)
                val groups = groupPhotoResults(plan, snapshot.photos.mapTo(hashSetOf()) { it.uri },
                    rankedContents = listOf(lookalike), identitySuggestions = listOf(lookalike), namedOnly = true)
                publication.publish { published += groups.confirmed + groups.related }
                complete.complete(Unit)
            }
            complete.await()
            assertEquals(listOf(confirmed), published)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            execution.launch(snapshot) { publication ->
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    publication.publish { published += lookalike }
                    finished.complete(Unit)
                }
            }
            started.await()
            assertTrue(execution.isActive)
            execution.cancel()
            assertFalse(execution.isActive)
            release.complete(Unit)
            finished.await()
            yield()
            assertEquals(listOf(confirmed), published)
        } finally { scope.cancel() }
    }
}
