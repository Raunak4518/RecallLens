package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.detection.GarmentColor
import org.photolibrary.app.detection.GarmentModels
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.detection.PersonGarmentEvidence
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.GarmentEvidenceStore
import org.photolibrary.app.library.CachedGarmentAnalysis
import org.photolibrary.app.library.ClothingSearch
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.library.PhotoEvidenceValidation
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoLocations
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.subjects.ClothingQuery
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference

@RunWith(AndroidJUnit4::class)
class ClothingSearchRaceTest {
    @Test
    fun finalSnapshotDropsEditedDeletedAndRevokedClothingResultsWithoutOpeningModels() = fixture { store, analysis, library ->
        runBlocking {
            val first = Uri.parse("content://fixture/clothing/first")
            val second = Uri.parse("content://fixture/clothing/second")
            val unconfirmed = Uri.parse("content://fixture/clothing/lookalike")
            val photos = listOf(first, second, unconfirmed).map { SelectedPhoto(it, "fixture.jpg", PhotoOrigin.GALLERY, "one") }
            val face = PhotoRegion(.1f, .1f, .3f, .3f)
            val person = PhotoRegion(0f, 0f, .5f, 1f)
            val named = NamedLibrary(listOf(NamedSubject(1, "Me", SubjectKind.PERSON, emptyList())),
                listOf(first, second).map { SubjectReference(1, it, "source:one", face) })
            val query = ClothingQuery(1, GarmentColor.RED, NamedSearch.Matches(setOf(first, second, unconfirmed)))
            for (uri in listOf(first, second, unconfirmed)) {
                val fraction = if (uri == second) .4f else .9f
                assertTrue(store.save(uri, "source:one", analysis.dependencies,
                    listOf(PersonGarmentEvidence.Found(face, person, analysis.dependencies.parser, 100,
                        mapOf(GarmentColor.RED to fraction, GarmentColor.BLACK to 1f - fraction)))) { "source:one" })
            }
            val snapshots = listOf(
                mapOf(first to "one", second to "one", unconfirmed to "one"),
                mapOf(first to "edited", second to "one", unconfirmed to "one"),
                mapOf(second to "one", unconfirmed to "one"),
                emptyMap(),
            )
            for ((index, latest) in snapshots.withIndex()) {
                var reads = 0
                val source = PhotoEvidenceValidation({ GalleryAccess.ALL }, {
                    reads++
                    latest
                }, { error("Known gallery search used per-photo source validation") })
                val search = ClothingSearch(analysis, store, PhotoLocations(libraryContext(), library), source)
                val result = search.search(query, named, photos)
                assertEquals(1, reads)
                assertEquals(NamedSearch.Matches(if (index == 0) setOf(first) else emptySet()), result.matches)
                assertEquals(if (index < 3) listOf(second) else emptyList<Uri>(), result.related)
            }
            var checks = 0
            val revoked = PhotoEvidenceValidation({ if (++checks == 1) GalleryAccess.ALL else GalleryAccess.NONE },
                { snapshots.first() }, { error("Unexpected pixel/source read") })
            val result = ClothingSearch(analysis, store, PhotoLocations(libraryContext(), library), revoked).search(query, named, photos)
            assertEquals(NamedSearch.Matches(emptySet<Uri>()), result.matches)
            assertTrue(result.related.isEmpty())
        }
    }

    @Test
    fun nearbyAnchorLossInvalidatesPlaceResultAndLocationPermissionRemainsExplicit() = fixture { store, analysis, library ->
        runBlocking {
            val uri = Uri.parse("content://fixture/clothing/place")
            val photo = SelectedPhoto(uri, "place.jpg", PhotoOrigin.GALLERY, "one")
            val named = NamedLibrary(listOf(NamedSubject(1, "Me", SubjectKind.PERSON, emptyList())),
                listOf(SubjectReference(1, uri, "source:one", PhotoRegion(.1f, .1f, .2f, .2f))))
            val query = ClothingQuery(1, GarmentColor.RED, NamedSearch.Nearby(emptySet(), setOf(uri)))
            val missing = PhotoEvidenceValidation({ GalleryAccess.ALL }, { emptyMap() }, { null })
            val search = ClothingSearch(analysis, store, PhotoLocations(libraryContext(), library, locationAllowed = { true }), missing)
            assertEquals(NamedSearch.Unresolved, search.search(query, named, listOf(photo)).matches)
            val denied = ClothingSearch(analysis, store, PhotoLocations(libraryContext(), library, locationAllowed = { false }), missing)
            assertEquals(NamedSearch.LocationPermission, denied.search(query, named, listOf(photo)).matches)
        }
    }

    @Test
    fun unversionedConfirmedSourceIsValidatedAndUnavailableCopyCannotLeakIntoResults() = fixture { store, analysis, library ->
        runBlocking {
            val uri = Uri.parse("content://fixture/unversioned/clothing")
            val photo = SelectedPhoto(uri, "fixture.png", PhotoOrigin.FOLDER)
            val face = PhotoRegion(.1f, .1f, .3f, .3f)
            val named = NamedLibrary(listOf(NamedSubject(1, "Me", SubjectKind.PERSON, emptyList())),
                listOf(SubjectReference(1, uri, "pixels:one", face)))
            assertTrue(store.save(uri, "pixels:one", analysis.dependencies,
                listOf(PersonGarmentEvidence.Found(face, PhotoRegion(0f, 0f, .5f, 1f), analysis.dependencies.parser,
                    100, mapOf(GarmentColor.RED to 1f)))) { "pixels:one" })
            var revision: String? = "pixels:one"
            val validation = PhotoEvidenceValidation({ GalleryAccess.NONE }, { error("Folder query enumerated gallery") }, { revision })
            val search = ClothingSearch(analysis, store, PhotoLocations(libraryContext(), library), validation)
            val query = ClothingQuery(1, GarmentColor.RED, NamedSearch.Matches(setOf(uri)))
            assertEquals(NamedSearch.Matches(setOf(uri)), search.search(query, named, listOf(photo)).matches)
            revision = null
            assertEquals(NamedSearch.Matches(emptySet<Uri>()), search.search(query, named, listOf(photo)).matches)
        }
    }

    private fun libraryContext() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(action: (GarmentEvidenceStore, CachedGarmentAnalysis, PhotoLibrary) -> Unit) {
        val context = libraryContext()
        val alias = "clothing-race-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val store = GarmentEvidenceStore(EncryptedPhotoIndex(directory, alias))
        val library = PhotoLibrary(context)
        val analysis = CachedGarmentAnalysis(library, store, GarmentModels(context.assets), OnnxRegionModels(context.assets))
        try { action(store, analysis, library) } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
