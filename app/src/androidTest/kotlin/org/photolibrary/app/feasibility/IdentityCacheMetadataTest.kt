package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.identity.IdentityCacheCodec
import org.photolibrary.app.identity.IdentityEncoder
import org.photolibrary.app.identity.IdentityModelFactory
import org.photolibrary.app.identity.IdentityModels
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.library.IdentityCacheUsage
import org.photolibrary.app.library.IdentityMatcher
import org.photolibrary.app.library.IdentityRanking
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoSubjects
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.searchNamed

@RunWith(AndroidJUnit4::class)
class IdentityCacheMetadataTest {
    @Test
    fun faceCacheSurvivesMetadataChangesButEditedPixelsRunInference() = runBlocking {
        verify(SubjectKind.PERSON, listOf("face-reference.png", "face-same.jpg", "face-lookalike.jpg"))
    }

    @Test
    fun objectCacheSurvivesMetadataChangesButEditedPixelsRunInference() = runBlocking {
        verify(SubjectKind.OBJECT, listOf("object-reference.png", "object-same.png", "object-lookalike.png", "object-other.png"))
    }

    private suspend fun verify(kind: SubjectKind, files: List<String>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val resolver = context.contentResolver
        val alias = "identity-metadata-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val storage = EncryptedPhotoIndex(directory, alias)
        val library = PhotoLibrary(context)
        val names = PhotoSubjects(library, NamedSubjectStore(storage))
        val models = IdentityModels(context.assets)
        val matcher = IdentityMatcher(library, OnnxRegionModels(context.assets), models, storage)
        val cacheOnly = object : IdentityModelFactory by models {
            override fun open(kind: SubjectKind): IdentityEncoder = error("Metadata-only search opened an identity encoder")
        }
        val cached = IdentityMatcher(library, OnnxRegionModels(context.assets), cacheOnly, storage)
        val owned = mutableListOf<Uri>()
        try {
            for (file in files) {
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, UUID.randomUUID().toString() + "-" + file)
                        put(MediaStore.Images.Media.MIME_TYPE, if (file.endsWith(".png")) "image/png" else "image/jpeg")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                owned += uri
                resolver.openOutputStream(uri)!!.use { target ->
                    instrumentation.context.assets.open("identity/" + file).use { it.copyTo(target) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            suspend fun photos() = library.list().photos.filter { it.uri in owned }
            val originalPhotos = photos()
            val anchor = owned.first()
            val candidate = owned[1]
            val label = if (kind == SubjectKind.PERSON) "Me" else "My car"
            assertEquals(Confirmation.SAVED, names.confirm(originalPhotos.single { it.uri == anchor },
                SubjectSelection.New(label, kind, emptyList()), null))
            var named = names.refresh(originalPhotos)
            val first = matcher.rank(kind, named.references, originalPhotos) { _, _ -> } as IdentityRanking.Candidates
            assertEquals(IdentityCacheUsage(0, files.size - 1, 0, 1), first.cacheUsage)
            val initial = storage.openIdentity(kind).use { it.reconcile(owned.toSet()) }
            assertTrue(initial.values.all { it.pixelRevision != null })
            val operations = listOf(
                anchor to ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, UUID.randomUUID().toString() + "-renamed.png") },
                candidate to ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Identity-" + UUID.randomUUID() + "/") },
            )
            for ((uri, values) in operations) {
                assertEquals(1, resolver.update(uri, values, null, null))
                val current = photos()
                named = names.refresh(current)
                assertEquals(NamedSearch.Matches(setOf(anchor)), searchNamed(label, named, true))
                cached.reconcile(current)
                val result = cached.rank(kind, named.references, current) { _, _ -> } as IdentityRanking.Candidates
                assertEquals(IdentityCacheUsage(files.size - 1, 0, 1, 0), result.cacheUsage)
                assertEquals(first.photos.map { it.photo.uri to it.score }, result.photos.map { it.photo.uri to it.score })
                storage.openIdentity(kind).use { session ->
                    for ((path, record) in session.reconcile(owned.toSet())) {
                        assertEquals("source:" + current.single { it.uri == path }.sourceRevision, record.sourceRevision)
                        assertEquals(initial.getValue(path).pixelRevision, record.pixelRevision)
                        assertArrayEquals(IdentityCacheCodec.encode(initial.getValue(path).output), IdentityCacheCodec.encode(record.output))
                    }
                }
            }
            val stale = cached.rank(kind, named.references, originalPhotos) { _, _ -> }
            assertEquals(IdentityRanking.NoReference, stale)
            storage.openIdentity(kind).use { assertEquals(owned.toSet(), it.reconcile(owned.toSet()).keys) }
            val current = photos()
            val repeat = cached.rank(kind, named.references, current) { _, _ -> } as IdentityRanking.Candidates
            assertEquals(IdentityCacheUsage(files.size - 1, 0, 1, 0), repeat.cacheUsage)
            val priorFingerprint = storage.openIdentity(kind).use { it.reconcile(owned.toSet()).getValue(candidate).pixelRevision }
            resolver.update(candidate, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
            resolver.openOutputStream(candidate, "wt")!!.use { target ->
                instrumentation.context.assets.open("identity/" + files.last()).use { it.copyTo(target) }
            }
            resolver.update(candidate, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val edited = photos()
            matcher.reconcile(edited)
            val refreshed = matcher.rank(kind, named.references, edited) { _, _ -> } as IdentityRanking.Candidates
            assertEquals(IdentityCacheUsage(files.size - 2, 1, 1, 0), refreshed.cacheUsage)
            storage.openIdentity(kind).use {
                assertTrue(priorFingerprint != it.reconcile(owned.toSet()).getValue(candidate).pixelRevision)
            }
            val narrowed = edited.filterNot { it.uri == candidate }
            cached.reconcile(narrowed)
            storage.openIdentity(kind).use { assertFalse(candidate in it.reconcile(owned.toSet())) }
            named = names.refresh(narrowed)
            assertEquals(NamedSearch.Matches(setOf(anchor)), searchNamed(label, named, true))
            val noAnchor = narrowed.filterNot { it.uri == anchor }
            cached.reconcile(noAnchor)
            named = names.refresh(noAnchor)
            assertEquals(NamedSearch.Matches(emptySet<Uri>()), searchNamed(label, named, true))
            assertEquals(IdentityRanking.NoReference, cached.rank(kind, named.references, noAnchor) { _, _ -> })
            Log.i("IdentityMetadataTest", "$kind rename/move and repeat: zero encoder opens and zero inference misses; edited photo: one miss; Named remained confirmed-only")
        } finally {
            owned.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
