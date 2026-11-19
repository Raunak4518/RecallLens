package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.LocationCache
import org.photolibrary.app.index.PhotoCoordinates
import org.photolibrary.app.library.PhotoContentSearch
import org.photolibrary.app.library.PhotoEvidenceValidation
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoLocations
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.library.UnavailableSources
import org.photolibrary.app.semantic.PhotoEmbedding
import org.photolibrary.app.semantic.ResidualVisualRanker
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.PhotoQueryPlan
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.planPhotoQuery

@RunWith(AndroidJUnit4::class)
class PhotoContentSearchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
    private val resolver = app.contentResolver

    @Test
    fun residualRerankerOnlyChangesUnscopedSingleVectorVisualResults() = fixture {
        val weights = ByteBuffer.allocate(80 + 2 * 512 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        weights.put("PLR1".encodeToByteArray()).putInt(512).putInt(1).putInt(5).position(80)
        weights.putFloat(1f)
        weights.putFloat(80 + 513 * Float.SIZE_BYTES, 2f)
        val trained = ResidualVisualRanker.decode(weights.array(), "0".repeat(64))
        val inputs = photos.associate { photo ->
            val position = when (photo.uri) {
                anchor -> 0
                near -> 1
                else -> 2
            }
            photo.uri to PhotoEmbedding.normalized(FloatArray(512).apply { this[position] = 1f })
        }
        val route = engine(trained)
        val plain = PhotoQueryPlan(named = null)
        assertEquals(near, route.search(plain, photos, inputs) { vector }.related.first())
        assertEquals(anchor, route.search(plain.copy(relatedScope = inputs.keys), photos, inputs) { vector }.related.first())
        val variants = route.searchVariants(plain, photos, inputs, UnavailableSources.NONE) { listOf(vector, vector) }
        assertEquals(anchor, variants.related.first())
    }

    @Test
    fun nearbyRankingUsesConfirmedIntersectionAndEncryptedCoordinatesAcrossReopen() = fixture {
        val first = engine().search(plan, photos, embeddings) { vector }
        assertEquals(listOf(near), first.related)
        assertEquals(NamedSearch.NearbyMatches(setOf(near)), first.location)
        val initialReads = reads
        assertTrue(initialReads > 0)
        val second = engine().search(plan, photos, embeddings) { vector }
        assertEquals(first, second)
        assertEquals(initialReads, reads)
        assertFalse(directory.resolve("catalog.db").readBytes().toString(Charsets.ISO_8859_1).contains(near.toString()))
    }

    @Test
    fun deniedMissingAndEmptyLocationScopesDoNotEncodeOrFallBackToUnfilteredPhotos() = fixture {
        allowed = false
        val denied = engine().search(plan, photos, embeddings) { error("Encoded a denied request") }
        assertTrue(denied.related.isEmpty())
        assertEquals(NamedSearch.LocationPermission, denied.location)
        assertTrue(cache.reconcile(photos.associate { it.uri to it.sourceRevision }).isEmpty())
        allowed = true
        val missing = plan.copy(nearby = checkNotNull(plan.nearby).copy(anchors = setOf(noGps)))
        assertEquals(NamedSearch.NoPlaceCoordinates,
            engine().search(missing, photos, embeddings) { error("Encoded without anchor coordinates") }.location)
        val empty = plan.copy(nearby = checkNotNull(plan.nearby).copy(candidates = emptySet()))
        assertTrue(engine().search(empty, photos, embeddings) { error("Encoded an empty scope") }.related.isEmpty())
    }

    @Test
    fun locationPermissionRevokedWhileEncodingWithholdsResultsAndClearsCachedPoints() = fixture {
        val result = engine().search(plan, photos, embeddings) {
            allowed = false
            vector
        }
        assertEquals(NamedSearch.LocationPermission, result.location)
        assertTrue(result.related.isEmpty())
        assertTrue(cache.reconcile(photos.associate { it.uri to it.sourceRevision }).isEmpty())
    }

    @Test
    fun anchorEditedDuringEncodingCannotValidateTheOldNearbyClaim() = fixture {
        val result = engine().search(plan, photos, embeddings) {
            assertEquals(1, resolver.update(anchor,
                ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "changed-${UUID.randomUUID()}.jpg") }, null, null))
            vector
        }
        assertTrue(result.related.isEmpty())
        assertEquals(NamedSearch.Unresolved, result.location)
    }

    @Test
    fun editedCandidateIsRemovedAfterSlowEncoding() = fixture {
        val result = engine().search(plan, photos, embeddings) {
            assertEquals(1, resolver.update(near,
                ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "changed-${UUID.randomUUID()}.jpg") }, null, null))
            vector
        }
        assertTrue(result.related.isEmpty())
        assertEquals(NamedSearch.NearbyMatches(emptySet<Uri>()), result.location)
    }

    @Test
    fun deletedCandidateCannotReturnFromTheSavedEmbeddingMap() = fixture {
        val result = engine().search(plan, photos, embeddings) {
            assertEquals(1, resolver.delete(near, null, null))
            inserted.remove(near)
            vector
        }
        assertTrue(result.related.isEmpty())
        assertEquals(NamedSearch.NearbyMatches(emptySet<Uri>()), result.location)
    }

    private fun fixture(block: suspend Gallery.() -> Unit) = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val priorPause = app.indexing.progress.value.checkpoint.paused
        app.indexing.pause(true)
        app.indexScheduler.cancel()
        val gallery = Gallery()
        try { gallery.prepare(); gallery.block() }
        finally {
            gallery.inserted.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(gallery.alias)
            gallery.directory.deleteRecursively()
            app.indexing.pause(priorPause)
        }
    }

    private inner class Gallery {
        val alias = "complex-scope-" + UUID.randomUUID()
        val directory = app.noBackupFilesDir.resolve(alias)
        val storage = EncryptedPhotoIndex(directory, alias)
        val cache = LocationCache(storage)
        val library = PhotoLibrary(app)
        val inserted = mutableListOf<Uri>()
        var allowed = true
        var reads = 0
        lateinit var photos: List<SelectedPhoto>
        val vector = PhotoEmbedding.normalized(FloatArray(512).apply { this[0] = 1f })
        val anchor get() = inserted[0]
        val near get() = inserted[1]
        val noGps get() = inserted[3]
        val embeddings get() = photos.associate { it.uri to vector }
        val plan get() = planPhotoQuery("me behind my car around Surat", named, false)
        private val named get() = NamedLibrary(listOf(
            NamedSubject(1, "Me", SubjectKind.PERSON, emptyList()),
            NamedSubject(2, "My car", SubjectKind.OBJECT, emptyList()),
            NamedSubject(3, "Surat", SubjectKind.PLACE, emptyList()),
        ), buildList {
            add(SubjectReference(3, anchor, "place", null))
            for (position in listOf(1, 2, 3)) {
                add(SubjectReference(1, inserted[position], "person", null))
                add(SubjectReference(2, inserted[position], "car", null))
            }
            add(SubjectReference(1, inserted[4], "person", null))
        })

        fun engine(reranker: ResidualVisualRanker? = null) = PhotoContentSearch(PhotoLocations(app, library, cache, { allowed }) { photo ->
            reads++
            when (inserted.indexOf(photo.uri)) {
                0 -> PhotoCoordinates(21.1702, 72.8311)
                1, 4, 5 -> PhotoCoordinates(21.18, 72.8311)
                2 -> PhotoCoordinates(23.0, 72.8311)
                else -> null
            }
        }, PhotoEvidenceValidation(library), reranker)

        suspend fun prepare() {
            repeat(6) {
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "complex-scope-${UUID.randomUUID()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                inserted += uri
                val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                try {
                    resolver.openOutputStream(uri)!!.use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                } finally { image.recycle() }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            photos = library.list().photos.filter { it.uri in inserted }
            assertEquals(6, photos.size)
        }
    }
}
