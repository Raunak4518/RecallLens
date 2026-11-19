package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.library.PhotoLocationPublication
import org.photolibrary.app.subjects.NamedSearch

@RunWith(AndroidJUnit4::class)
class PhotoLocationPublicationTest {
    private val photo = Uri.parse("content://fixture/location/published")
    private val result = NamedSearch.NearbyMatches(setOf(photo))

    @Test
    fun successfulIoResultCannotPassTheQueuedMainPublicationAfterPermissionRevocation() = runBlocking {
        withTimeout(10_000) {
            var allowed = true
            val publication = PhotoLocationPublication({ allowed }, { GalleryAccess.ALL })
            val ready = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val checked = async {
                val old = withContext(Dispatchers.IO) { result }
                withContext(Dispatchers.Main) {
                    ready.complete(Unit)
                    release.await()
                    publication.current(old, GalleryAccess.ALL)
                }
            }
            ready.await()
            allowed = false
            release.complete(Unit)
            assertEquals(NamedSearch.LocationPermission, checked.await())
        }
    }

    @Test
    fun galleryScopeChangesSuppressLocationResultsButOrdinaryNamedResultsStayIndependent() {
        var access = GalleryAccess.ALL
        val publication = PhotoLocationPublication({ true }, { access })
        assertEquals(result, publication.current(result, GalleryAccess.ALL))
        for (changed in listOf(GalleryAccess.SELECTED, GalleryAccess.NONE)) {
            access = changed
            assertEquals(NamedSearch.Unresolved, publication.current(result, GalleryAccess.ALL))
        }
        val ordinary = NamedSearch.Matches(setOf(photo))
        val noLocation = PhotoLocationPublication({ error("Ordinary name requested location permission") }, { access })
        assertEquals(ordinary, noLocation.current(ordinary, GalleryAccess.ALL))
    }
}
