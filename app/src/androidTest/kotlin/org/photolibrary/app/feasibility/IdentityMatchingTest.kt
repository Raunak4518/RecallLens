package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
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
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.identity.IdentityModels
import org.photolibrary.app.identity.IdentityEncoder
import org.photolibrary.app.identity.IdentityModelFactory
import org.photolibrary.app.identity.IdentityCacheCodec
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.library.IdentityMatcher
import org.photolibrary.app.library.IdentityCacheUsage
import org.photolibrary.app.library.IdentityRanking
import org.photolibrary.app.library.IdentityReview
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoSubjects
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.searchNamed

@RunWith(AndroidJUnit4::class)
class IdentityMatchingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver

    @Test
    fun alignedFaceRanksAnotherPhotoAheadOfAnotherAstronaut() = runBlocking<Unit> {
        checkRanking(SubjectKind.PERSON, "Face fixture", listOf("face-reference.png", "face-same.jpg", "face-lookalike.jpg"))
    }

    @Test
    fun objectRanksAnotherViewAheadOfTwoDifferentToyCars() = runBlocking<Unit> {
        checkRanking(SubjectKind.OBJECT, "Car fixture", listOf(
            "object-reference.png", "object-same.png", "object-lookalike.png", "object-other.png",
        ))
    }

    private suspend fun checkRanking(kind: SubjectKind, name: String, files: List<String>) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val library = PhotoLibrary(context)
        val matcher = IdentityMatcher(library, OnnxRegionModels(context.assets), IdentityModels(context.assets))
        assertTrue(matcher.available(kind))
        val alias = "identity-fixture-${UUID.randomUUID()}"
        val directory = context.noBackupFilesDir.resolve(alias)
        val storage = EncryptedPhotoIndex(directory, alias)
        val subjects = PhotoSubjects(library, NamedSubjectStore(storage))
        val inserted = mutableListOf<Uri>()
        try {
            files.forEach { insertFixture(it, inserted) }
            val photos = library.list().photos.filter { it.uri in inserted }
            val (anchor, same, lookalike) = inserted
            val confirmedPhoto = photos.single { it.uri == anchor }
            assertEquals(Confirmation.SAVED, subjects.confirm(confirmedPhoto, SubjectSelection.New(name, kind, emptyList()), null))
            val before = subjects.refresh(photos)
            val subject = before.subjects.single()
            val checked = mutableListOf<Int>()
            val result = matcher.rank(kind, before.references, photos) { done, total ->
                assertEquals(photos.size - 1, total)
                checked += done
            }
            assertTrue("Expected candidates from ${files.first()}, got $result", result is IdentityRanking.Candidates)
            val complete = result as IdentityRanking.Candidates
            val ranked = complete.photos
            Log.i("IdentityMatchingTest", "$kind " + ranked.joinToString { "${files[inserted.indexOf(it.photo.uri)]}=${it.score}" })
            assertEquals(same, ranked.first().photo.uri)
            assertTrue(ranked.any { it.photo.uri == lookalike })
            assertEquals((0 until photos.size).toList(), checked)
            assertEquals(NamedSearch.Matches(setOf(anchor)), searchNamed(name, subjects.refresh(photos), true))
            verifyCache(kind, library, storage, directory, before, photos, complete)
            assertEquals(NamedSearch.Matches(setOf(anchor)), searchNamed(name, subjects.refresh(photos), true))

            val review = IdentityReview(subject, result = complete, limit = 1)
            assertEquals(listOf(same), review.visibleSuggestions(setOf(anchor)).map { it.photo.uri })
            val accepted = ranked.first()
            assertEquals(Confirmation.SAVED, subjects.confirm(accepted.photo,
                SubjectSelection.Existing(subject.id), accepted.region, accepted.revision))
            val after = subjects.refresh(photos)
            assertEquals(NamedSearch.Matches(setOf(anchor, same)), searchNamed(name, after, true))
            assertTrue(review.visibleSuggestions(setOf(anchor, same)).all { it.photo.uri != same })
            assertEquals(emptyList<Uri>(), review.copy(skipped = ranked.map { it.photo.uri }.toSet())
                .visibleSuggestions(setOf(anchor, same)).map { it.photo.uri })
            if (kind == SubjectKind.OBJECT) {
                verifyIncrementalRefresh(library, storage, photos, before.references, lookalike, inserted)
            }
        } finally {
            inserted.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private suspend fun verifyCache(
        kind: SubjectKind, library: PhotoLibrary, storage: EncryptedPhotoIndex, directory: java.io.File,
        names: NamedLibrary, photos: List<SelectedPhoto>, previouslyRanked: IdentityRanking.Candidates,
    ) {
        storage.withDatabase { db ->
            db.execSQL("DROP TABLE face_embeddings")
            db.execSQL("DROP TABLE object_embeddings")
            db.execSQL("DROP TABLE photo_locations")
            db.execSQL("DROP TABLE person_garments")
            db.execSQL("DROP TABLE named_photo_content")
            db.execSQL("DROP TABLE self_identity")
            db.version = 4
        }
        val count = photos.size - 1
        suspend fun query() = cachedMatcher(library, storage).rank(kind, names.references, photos) { _, _ -> } as IdentityRanking.Candidates
        val cold = query()
        val reopened = query()
        assertEquals(previouslyRanked.photos, cold.photos)
        assertEquals(cold.photos, reopened.photos)
        assertEquals(IdentityCacheUsage(0, count, 0, 1), cold.cacheUsage)
        assertEquals(IdentityCacheUsage(count, 0, 1, 0), reopened.cacheUsage)
        val installed = IdentityModels(context.assets)
        val cacheOnly = object : IdentityModelFactory by installed {
            override fun open(kind: SubjectKind): IdentityEncoder = error("Warm identity search opened an encoder")
        }
        val injected = IdentityMatcher(library, OnnxRegionModels(context.assets), cacheOnly, storage)
            .rank(kind, names.references, photos) { _, _ -> } as IdentityRanking.Candidates
        assertEquals(reopened.photos, injected.photos)
        assertEquals(IdentityCacheUsage(count, 0, 1, 0), injected.cacheUsage)
        val sample = storage.openIdentity(kind).use { store ->
            val record = store.reconcile(photos.map { it.uri }.toSet()).getValue(previouslyRanked.photos.first().photo.uri)
            IdentityCacheCodec.encode(record.output).toString(Charsets.ISO_8859_1)
        }
        val raw = directory.resolve("catalog.db").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.startsWith("SQLite format 3"))
        assertFalse(raw.contains(sample))
    }

    private suspend fun verifyIncrementalRefresh(
        library: PhotoLibrary, storage: EncryptedPhotoIndex, photos: List<SelectedPhoto>,
        references: List<SubjectReference>, edited: Uri, owned: MutableList<Uri>,
    ) {
        val kind = SubjectKind.OBJECT
        val matcher = cachedMatcher(library, storage)
        val currentUris = photos.map { it.uri }.toSet()
        val before = photos.first { it.uri == edited }.sourceRevision
        val pending = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }
        assertEquals(1, resolver.update(edited, pending, null, null))
        checkNotNull(resolver.openOutputStream(edited, "wt")).use { target ->
            instrumentation.context.assets.open("identity/object-other.png").use { it.copyTo(target) }
        }
        val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        assertEquals(1, resolver.update(edited, published, null, null))
        val updated = library.list().photos.filter { it.uri in currentUris }
        assertTrue(before != updated.first { it.uri == edited }.sourceRevision)
        matcher.reconcile(updated)
        storage.openIdentity(kind).use { store ->
            val pending = store.reconcile(currentUris).getValue(edited)
            assertTrue(pending.sourceRevision != "source:" + updated.first { it.uri == edited }.sourceRevision)
        }
        val revised = matcher.rank(kind, references, updated) { _, _ -> } as IdentityRanking.Candidates
        assertEquals(IdentityCacheUsage(photos.size - 2, 1, 1, 0), revised.cacheUsage)
        storage.openIdentity(kind).use { store ->
            val entry = store.reconcile(currentUris).getValue(edited)
            store.save(edited, entry.copy(model = entry.model.copy(version = "superseded")))
        }
        val replaced = matcher.rank(kind, references, updated) { _, _ -> } as IdentityRanking.Candidates
        assertEquals(IdentityCacheUsage(photos.size - 2, 1, 1, 0), replaced.cacheUsage)

        assertEquals(1, resolver.delete(edited, null, null))
        owned.remove(edited)
        val deleted = updated.filter { it.uri != edited }
        matcher.reconcile(deleted)
        storage.openIdentity(kind).use { store -> assertFalse(edited in store.reconcile(currentUris)) }
        val pruned = matcher.rank(kind, references, deleted) { _, _ -> } as IdentityRanking.Candidates
        assertEquals(IdentityCacheUsage(photos.size - 2, 0, 1, 0), pruned.cacheUsage)
        assertFalse(pruned.photos.any { it.photo.uri == edited })

        val outOfScope = pruned.photos.last().photo.uri
        matcher.reconcile(deleted.filter { it.uri != outOfScope })
        storage.openIdentity(kind).use { store -> assertFalse(outOfScope in store.reconcile(currentUris)) }
        assertTrue(library.referenceRevision(outOfScope) != null)
    }

    private fun cachedMatcher(library: PhotoLibrary, storage: EncryptedPhotoIndex) =
        IdentityMatcher(library, OnnxRegionModels(context.assets), IdentityModels(context.assets), storage)

    private fun insertFixture(file: String, inserted: MutableList<Uri>) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${UUID.randomUUID()}-$file")
            put(MediaStore.Images.Media.MIME_TYPE, if (file.endsWith(".png")) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IdentityMatchingTest")
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        inserted += uri
        checkNotNull(resolver.openOutputStream(uri)).use { target ->
            instrumentation.context.assets.open("identity/$file").use { it.copyTo(target) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
}
