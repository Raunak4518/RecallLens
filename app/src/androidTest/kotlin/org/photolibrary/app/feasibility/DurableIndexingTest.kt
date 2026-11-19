package org.photolibrary.app.feasibility

import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.index.IndexingControl
import org.photolibrary.app.index.LocalIndexCoordinator
import org.photolibrary.app.index.LocalPhotoIndexer
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoProcessor
import org.photolibrary.app.index.PhotoProcessorFactory
import org.photolibrary.app.index.PhotoProcessingException
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.index.CloudTextEvidence
import org.photolibrary.app.index.CloudTextStore
import org.photolibrary.app.index.GarmentDependencies
import org.photolibrary.app.index.GarmentEvidenceStore
import org.photolibrary.app.index.IndexedPhoto
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.index.LocationCache
import org.photolibrary.app.index.CachedLocation
import org.photolibrary.app.index.PhotoCoordinates
import org.photolibrary.app.identity.IdentityCacheEntry
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.library.LibrarySources
import org.photolibrary.app.library.PhotoFolder
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.privacy.ExportMode
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection

@RunWith(AndroidJUnit4::class)
class DurableIndexingTest {
    @Test
    fun unavailableFolderRetainsPrivateRowsAndRevocationPrunesThem() = fixture { storage ->
        verifyUnavailableSource(storage, false)
    }

    @Test
    fun detachedMediaVolumeRetainsRowsAndGalleryRevocationPrunesThem() = fixture { storage ->
        verifyUnavailableSource(storage, true)
    }

    private fun verifyUnavailableSource(storage: EncryptedPhotoIndex, mediaVolume: Boolean) {
        runBlocking {
            val tree = DocumentsContract.buildTreeDocumentUri("fixture.documents", "removable:album")
            val uri = if (mediaVolume) Uri.parse("content://media/1111-2222/images/media/42")
                else DocumentsContract.buildDocumentUriUsingTree(tree, "removable:album/photo.jpg")
            val folder = PhotoFolder(tree, "Album")
            val origin = if (mediaVolume) PhotoOrigin.GALLERY else PhotoOrigin.FOLDER
            val photo = SelectedPhoto(uri, "photo.jpg", origin, "revision-one")
            val online = if (mediaVolume) LibrarySources(GalleryAccess.ALL, emptyList(), listOf(photo))
                else LibrarySources(GalleryAccess.NONE, listOf(folder), listOf(photo))
            var sources = online
            val model = ModelRevision("fixture", "1", 32)
            val reference = "source:revision-one"
            val names = NamedSubjectStore(storage)
            val cloud = CloudTextStore(storage)
            val garment = GarmentEvidenceStore(storage)
            val locations = LocationCache(storage)
            val location = CachedLocation("revision-one", PhotoCoordinates(21.17, 72.83))
            locations.save(uri, location)
            val dependencies = GarmentDependencies(model, model, model)
            val evidence = CloudTextEvidence(reference, "green coat", "a".repeat(64), "b".repeat(64), ExportMode.CLOUD_REDACTED)
            assertEquals(Confirmation.SAVED, names.confirm(uri, reference,
                SubjectSelection.New("Person", SubjectKind.PERSON, emptyList()), null))
            assertTrue(cloud.save(uri, evidence) { reference })
            assertTrue(garment.save(uri, reference, dependencies, emptyList()) { reference })
            storage.openIdentity(SubjectKind.PERSON).use {
                it.save(uri, IndexedPhoto(reference, null, model, IdentityCacheEntry()))
            }
            var loads = 0
            val text = index(storage, listOf(photo), { loads++ })
            fun coordinator() = LocalIndexCoordinator(IndexingControl(storage), { sources }, text, { null }, { "folder-v1" })
            assertFalse(coordinator().runBatch())
            assertEquals(1, loads)

            sources = if (mediaVolume) online.copy(photos = emptyList(), unavailableVolumes = setOf("1111-2222"))
                else online.copy(folders = listOf(folder.copy(available = false)), photos = emptyList())
            val unavailable = sources.unavailableSources
            assertFalse(coordinator().runBatch())
            assertTrue(text.readCached(emptyList(), setOf(uri), unavailable).isEmpty())
            assertTrue(names.reconcile(emptyMap(), unavailable).references.isEmpty())
            assertTrue(cloud.reconcile(emptyMap(), unavailable).isEmpty())
            garment.reconcile(emptyMap(), unavailable)
            assertTrue(locations.reconcile(emptyMap(), unavailable).isEmpty())
            storage.pruneIdentity(emptyMap(), unavailable)
            storage.openIdentity(SubjectKind.PERSON).use {
                assertTrue(it.reconcile(emptySet(), setOf(uri), unavailable).isEmpty())
            }

            sources = online
            assertFalse(coordinator().runBatch())
            assertEquals(1, loads)
            val visible = mapOf(uri to reference)
            assertEquals(setOf(uri), names.reconcile(visible).references.mapTo(hashSetOf()) { it.uri })
            assertEquals(evidence, cloud.reconcile(visible)[uri])
            assertEquals(location, locations.reconcile(mapOf(uri to "revision-one"))[uri])
            assertEquals(emptyList<Any>(), garment.read(uri, reference, dependencies))
            storage.openIdentity(SubjectKind.PERSON).use { assertEquals(setOf(uri), it.reconcile(setOf(uri)).keys) }

            sources = LibrarySources(GalleryAccess.NONE, emptyList(), emptyList())
            assertFalse(coordinator().runBatch())
            assertTrue(text.readCached(listOf(IndexPhoto(uri, photo.sourceRevision)), setOf(uri)).isEmpty())
            names.reconcile(emptyMap())
            cloud.reconcile(emptyMap())
            garment.reconcile(emptyMap())
            locations.reconcile(emptyMap())
            storage.pruneIdentity(emptyMap())
            assertTrue(names.reconcile(visible).references.isEmpty())
            assertTrue(cloud.reconcile(visible).isEmpty())
            assertTrue(locations.reconcile(mapOf(uri to "revision-one")).isEmpty())
            assertEquals(null, garment.read(uri, reference, dependencies))
            storage.openIdentity(SubjectKind.PERSON).use { assertTrue(it.reconcile(setOf(uri)).isEmpty()) }
        }
    }

    @Test
    fun reopenedCoordinatorResumesOnlyRemainingPhotosAndRetainsFullCacheScope() = fixture { storage ->
        runBlocking {
            val photos = List(5) { photo(it) }
            var loads = 0
            fun coordinator() = LocalIndexCoordinator(
                IndexingControl(storage), { LibrarySources(GalleryAccess.ALL, emptyList(), photos) },
                index(storage, photos, { loads++ }), { null }, { "test-v1" }, batchSize = 2,
            )
            val first = coordinator()
            assertTrue(first.runBatch())
            assertEquals(2, loads)
            assertEquals(2, IndexingControl(storage).read().cursor)
            val resumed = coordinator()
            assertTrue(resumed.runBatch())
            assertFalse(resumed.runBatch())
            assertEquals(5, loads)
            assertFalse(coordinator().runBatch())
            assertEquals(5, loads)
            storage.open().use { assertEquals(5, it.reconcile(photos.mapTo(hashSetOf()) { it.uri }).size) }
        }
    }

    @Test
    fun pausePersistsAndNewSourceRevisionIsProcessedAfterResume() = fixture { storage ->
        runBlocking {
            var photos = List(3) { photo(it) }
            var loads = 0
            fun coordinator() = LocalIndexCoordinator(
                IndexingControl(storage), { LibrarySources(GalleryAccess.ALL, emptyList(), photos) },
                index(storage, photos, { loads++ }), { null }, { "test-v1" }, batchSize = 1,
            )
            assertTrue(coordinator().runBatch())
            coordinator().pause(true)
            assertFalse(coordinator().runBatch())
            assertEquals(1, loads)
            photos = listOf(photos.first().copy(sourceRevision = "changed"), photos.last())
            val resumed = coordinator()
            resumed.pause(false)
            assertTrue(resumed.runBatch())
            assertFalse(resumed.runBatch())
            assertEquals(3, loads)
            val records = storage.open().use { it.reconcile(photos.mapTo(hashSetOf()) { it.uri }) }
            assertEquals(2, records.size)
            assertEquals("changed", records.getValue(photos.first().uri).sourceRevision)
        }
    }

    @Test
    fun unknownRevisionsAreVisitedOncePerPassAndExplicitRefreshRechecksThem() = fixture { storage ->
        runBlocking {
            val photos = List(3) { photo(it).copy(sourceRevision = null) }
            var loads = 0
            var inference = 0
            fun coordinator() = LocalIndexCoordinator(
                IndexingControl(storage), { LibrarySources(GalleryAccess.ALL, emptyList(), photos) },
                index(storage, photos, { loads++ }, { inference++ }), { null }, { "test-v1" }, batchSize = 1,
            )
            val coordinator = coordinator()
            assertTrue(coordinator.runBatch())
            assertTrue(coordinator.runBatch())
            assertFalse(coordinator.runBatch())
            assertEquals(6, loads)
            assertFalse(coordinator().runBatch())
            assertEquals(6, loads)
            coordinator.request()
            assertTrue(coordinator.runBatch())
            assertEquals(8, loads)
            assertEquals(3, inference)
        }
    }

    @Test
    fun scopedCacheRestoreReadsOnlyChangedPayloadsAndRejectsStaleSelectedRevision() = fixture { storage ->
        runBlocking {
            val photos = List(2) { photo(it) }
            var loads = 0
            val index = index(storage, photos, { loads++ })
            val inputs = photos.map { IndexPhoto(it.uri, it.sourceRevision) }
            index.refresh(inputs, { _, _ -> }) { _, _ -> }
            assertEquals(2, loads)
            storage.withDatabase {
                it.execSQL("UPDATE photo_text SET output = ? WHERE uri = ?",
                    arrayOf("corrupt-unselected-payload", photos.last().uri.toString()))
            }
            val selected = setOf(photos.first().uri)
            val saved = index.readCached(inputs, selected)
            assertEquals(selected, saved.keys)
            assertEquals(2, loads)
            val changed = listOf(inputs.first().copy(sourceRevision = "edited"), inputs.last())
            assertTrue(index.readCached(changed, selected).isEmpty())
            assertEquals(2, loads)
        }
    }

    @Test
    fun failedPhotosAreReportedAndExplicitRefreshRetriesThem() = fixture { storage ->
        runBlocking {
            val photos = listOf(photo(0))
            var fail = true
            val coordinator = LocalIndexCoordinator(
                IndexingControl(storage), { LibrarySources(GalleryAccess.ALL, emptyList(), photos) },
                index(storage, photos, {}, { if (fail) throw PhotoProcessingException(IllegalStateException("fixture failure")) }),
                { null }, { "test-v1" }, batchSize = 1,
            )
            assertFalse(coordinator.runBatch())
            assertEquals(1, IndexingControl(storage).read().failed)
            fail = false
            coordinator.request()
            assertFalse(coordinator.runBatch())
            assertEquals(0, IndexingControl(storage).read().failed)
            storage.open().use { assertEquals(1, it.reconcile(setOf(photos.single().uri)).size) }
        }
    }

    @Test
    fun cachedOnlyRestoreDoesNotOpenProcessorAndDoesNotPruneOtherAccessibleRows() = fixture { storage ->
        runBlocking {
            val photos = List(3) { photo(it) }
            var loads = 0
            val index = index(storage, photos, { loads++ })
            val inputs = photos.map { IndexPhoto(it.uri, it.sourceRevision) }
            index.refresh(inputs, { _, _ -> }, setOf(photos.first().uri)) { _, _ -> }
            assertEquals(1, loads)
            index.refresh(inputs, { saved, pending ->
                assertEquals(setOf(photos.first().uri), saved.keys)
                assertEquals(2, pending)
            }, emptySet()) { _, _ -> error("Unexpected processing") }
            assertEquals(1, loads)
            storage.open().use { assertEquals(1, it.reconcile(photos.mapTo(hashSetOf()) { it.uri }).size) }
        }
    }

    private fun index(
        storage: EncryptedPhotoIndex,
        photos: List<SelectedPhoto>,
        loaded: () -> Unit,
        processed: () -> Unit = {},
    ): LocalPhotoIndexer<PhotoText> {
        val revision = ModelRevision("fixture", "1", 32)
        return LocalPhotoIndexer(
            loadImage = { _, _ -> loaded(); Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888) },
            processorFactory = PhotoProcessorFactory(revision) {
                object : PhotoProcessor<PhotoText> {
                    override val model = revision
                    override fun process(image: Bitmap): PhotoText {
                        processed()
                        return PhotoText(listOf(TextLine("GATE 4821", "en", null)))
                    }
                    override fun close() = Unit
                }
            },
            openIndex = storage::open,
            readRevision = { uri -> photos.firstOrNull { it.uri == uri }?.sourceRevision },
        )
    }

    private fun photo(index: Int) =
        SelectedPhoto(Uri.parse("content://durable/photo/$index"), null, PhotoOrigin.GALLERY, "revision-$index")

    private fun fixture(action: (EncryptedPhotoIndex) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "durable-index-${UUID.randomUUID()}"
        val directory = context.noBackupFilesDir.resolve(alias)
        try {
            action(EncryptedPhotoIndex(directory, alias))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
