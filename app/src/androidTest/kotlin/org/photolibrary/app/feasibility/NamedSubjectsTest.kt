package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.index.NamedContentProof
import org.photolibrary.app.library.PhotoReferenceSource
import org.photolibrary.app.library.PhotoSubjects
import org.photolibrary.app.library.SubjectReferenceSelection
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.library.UnavailableSources
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.DatedAliasDraft
import org.photolibrary.app.subjects.IdentityRejection
import org.photolibrary.app.subjects.identityKey
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectGroupChange
import org.photolibrary.app.subjects.SubjectGroupResult
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.searchNamed

@RunWith(AndroidJUnit4::class)
class NamedSubjectsTest {
    private val first = Uri.parse("content://fixture/names/confirmed-view-one")
    private val second = Uri.parse("content://fixture/names/confirmed-view-two")
    private val lookalike = Uri.parse("content://fixture/names/unconfirmed-similar-car")
    private val available = mapOf(first to "gallery:1", second to "gallery:2", lookalike to "gallery:3")
    private val region = PhotoRegion(.1f, .15f, .9f, .75f)

    @Test
    fun batchConfirmationSavesOnlySelectedRegionsAndRejectsAStaleBatch() = withStore { store, reopen, _ ->
        runBlocking {
            val revisions = available.mapValues { "source:${it.value}" }.toMutableMap()
            val source = object : PhotoReferenceSource {
                override suspend fun referenceRevision(uri: Uri) = revisions[uri]
                override suspend fun contentDigest(uri: Uri, expectedRevision: String): String? =
                    if (revisions[uri] == expectedRevision) "sha256-v1:${uri.lastPathSegment}" else null
            }
            val subjects = PhotoSubjects(source, store)
            val photos = available.keys.associateWith { SelectedPhoto(it, "fixture.png", PhotoOrigin.FOLDER) }
            val otherRegion = PhotoRegion(.3f, .2f, .8f, .85f)
            val selected = listOf(
                SubjectReferenceSelection(photos.getValue(first), region, revisions[first]),
                SubjectReferenceSelection(photos.getValue(second), otherRegion, revisions[second]),
            )
            val name = SubjectSelection.New("My car", SubjectKind.OBJECT, listOf("My BMW"))
            revisions[second] = "source:edited"
            assertEquals(Confirmation.PHOTO_CHANGED, subjects.confirmAll(name, selected))
            assertTrue(reopen().reconcile(revisions).subjects.isEmpty())
            revisions[second] = "source:${available[second]}"
            assertEquals(Confirmation.SAVED, subjects.confirmAll(name, selected))
            val restored = reopen().reconcile(revisions)
            assertEquals(NamedSearch.Matches(setOf(first, second)), searchNamed("my bmw", restored, true))
            assertEquals(setOf(region, otherRegion), restored.references.map { it.region }.toSet())
            assertTrue(restored.referencesIn(lookalike).isEmpty())
            val saved = SubjectSelection.Existing(restored.subjects.single().id)
            val next = SubjectReferenceSelection(photos.getValue(lookalike), region, revisions[lookalike])
            revisions[lookalike] = "source:edited-lookalike"
            assertEquals(Confirmation.PHOTO_CHANGED, subjects.confirmAll(saved, listOf(selected.first(), next)))
            assertEquals(restored, reopen().reconcile(revisions))
        }
    }

    @Test
    fun overlappingDatedNamesAreRejectedAndRemovalIsScoped() = withStore { store, reopen, _ ->
        val original = SubjectSelection.New("North Campus", SubjectKind.ORGANIZATION, emptyList(),
            datedAlias = DatedAliasDraft("my college", 2019, 2021))
        assertEquals(Confirmation.SAVED, store.confirm(first, available.getValue(first), original, region))
        val next = SubjectSelection.New("South Campus", SubjectKind.ORGANIZATION, emptyList(),
            datedAlias = DatedAliasDraft("my college", 2021, 2025))
        assertEquals(Confirmation.INVALID_DATED_ALIAS, store.confirm(second, available.getValue(second), next, region))
        val corrected = next.copy(datedAlias = next.datedAlias!!.copy(firstYear = 2022))
        assertEquals(Confirmation.SAVED, store.confirm(second, available.getValue(second), corrected, region))
        val restored = reopen().reconcile(available)
        assertEquals("North Campus", restored.atAliasYear(2020).subjectFor(listOf("my", "college"))?.name)
        assertEquals("South Campus", restored.atAliasYear(2022).subjectFor(listOf("my", "college"))?.name)
        val older = restored.datedAliases.first { it.period.firstYear == 2019 }
        reopen().removeDatedAlias(older)
        assertEquals(listOf(2022), store.reconcile(available).datedAliases.map { it.period.firstYear })
    }

    @Test
    fun groupEditsKeepConfirmationProvenanceAndUndoRestoresSources() = withStore { store, reopen, _ ->
        val original = SubjectSelection.New("Primary", SubjectKind.PERSON, listOf("Original alias"))
        assertEquals(Confirmation.SAVED, store.confirm(first, available.getValue(first), original, region))
        val primary = store.reconcile(available).subjects.single().id
        assertEquals(Confirmation.SAVED, store.confirm(second, available.getValue(second), SubjectSelection.Existing(primary), region))
        assertTrue(store.setSelf(primary))
        val before = reopen().reconcile(available)
        val split = store.editGroups(before, primary, SubjectGroupChange.Split(setOf(second), "Second person"))
            as SubjectGroupResult.Applied
        val splitState = reopen().reconcile(available)
        val secondary = splitState.subjectFor(listOf("second", "person"))!!.id
        assertEquals(setOf(first), splitState.photosOf(setOf(primary)))
        assertEquals(setOf(second), splitState.photosOf(setOf(secondary)))
        assertEquals(region, splitState.referencesIn(second).single().region)
        assertEquals(primary, splitState.selfSubjectId)
        val merged = reopen().editGroups(splitState, primary, SubjectGroupChange.Merge(secondary)) as SubjectGroupResult.Applied
        val mergedState = store.reconcile(available)
        assertEquals(secondary, mergedState.selfSubjectId)
        assertEquals(NamedSearch.Matches(setOf(first, second)), searchNamed("original alias", mergedState, true))
        assertTrue(reopen().undoGroupEdit(merged.edit))
        assertEquals(splitState, store.reconcile(available))
        assertTrue(reopen().undoGroupEdit(split.edit))
        assertEquals(before, store.reconcile(available))
    }

    @Test
    fun mergeRejectsConflictingRegionsAndUndoRejectsAnEditedGroup() = withStore { store, _, _ ->
        val source = SubjectSelection.New("First item", SubjectKind.OBJECT, emptyList())
        val target = SubjectSelection.New("Second item", SubjectKind.OBJECT, emptyList())
        assertEquals(Confirmation.SAVED, store.confirm(first, available.getValue(first), source, region))
        assertEquals(Confirmation.SAVED, store.confirm(first, available.getValue(first), target, PhotoRegion(.1f, .1f, .2f, .2f)))
        val before = store.reconcile(available)
        val firstId = before.subjectFor(listOf("first", "item"))!!.id
        val secondId = before.subjectFor(listOf("second", "item"))!!.id
        assertEquals(SubjectGroupResult.Rejected.CONFLICTING_REGIONS,
            store.editGroups(before, firstId, SubjectGroupChange.Merge(secondId)))
        assertEquals(before, store.reconcile(available))
        store.remove(first, secondId)
        val merged = store.editGroups(store.reconcile(available), firstId, SubjectGroupChange.Merge(secondId))
            as SubjectGroupResult.Applied
        assertEquals(Confirmation.SAVED, store.confirm(second, available.getValue(second), SubjectSelection.Existing(secondId), region))
        assertFalse(store.undoGroupEdit(merged.edit))
        assertEquals(setOf(first, second), store.reconcile(available).photosOf(setOf(secondId)))
    }

    @Test
    fun identityRejectionsPersistOnlyForTheExplicitSubjectRegionAndRevision() = withStore { store, reopen, _ ->
        val declared = SubjectSelection.New("My car", SubjectKind.OBJECT, emptyList())
        assertEquals(Confirmation.SAVED, store.confirm(first, "source:one", declared, region))
        val id = reopen().reconcile(mapOf(first to "source:one")).subjects.single().id
        val negative = IdentityRejection(second, "source:two", region.identityKey())
        assertTrue(store.rejectIdentity(id, negative))
        assertEquals(setOf(negative), reopen().identityRejections(id))
        assertTrue(reopen().identityRejections(id + 1).isEmpty())
        reopen().undoIdentityRejection(id, negative)
        assertTrue(store.identityRejections(id).isEmpty())
        assertTrue(store.rejectIdentity(id, negative))
        val currentPhoto = SelectedPhoto(second, "revised.jpg", PhotoOrigin.GALLERY, "three")
        reopen().pruneIdentityRejections(listOf(currentPhoto), UnavailableSources.NONE)
        assertTrue(store.identityRejections(id).isEmpty())
        assertTrue(store.rejectIdentity(id, negative))
        val otherRegion = PhotoRegion(.2f, .3f, .4f, .6f)
        assertEquals(Confirmation.SAVED, store.confirm(second, negative.revision, SubjectSelection.Existing(id), otherRegion))
        assertEquals(setOf(negative), reopen().identityRejections(id))
        assertEquals(Confirmation.SAVED, store.confirm(second, negative.revision, SubjectSelection.Existing(id), region))
        assertTrue(reopen().identityRejections(id).isEmpty())
    }

    @Test
    fun unversionedSourcesRecheckOriginalBytesEvenWhenDecodedPixelsMatch() = withStore { store, reopen, _ ->
        runBlocking {
            var digest: String? = "sha256-v1:original"
            var reads = 0
            val source = object : PhotoReferenceSource {
                override suspend fun referenceRevision(uri: Uri) = "pixels:unchanged"
                override suspend fun contentDigest(uri: Uri, expectedRevision: String): String? {
                    assertEquals("pixels:unchanged", expectedRevision)
                    reads++
                    return digest
                }
            }
            val subjects = PhotoSubjects(source, store)
            val photo = SelectedPhoto(first, "fixture.png", PhotoOrigin.FOLDER)
            assertEquals(Confirmation.SAVED, subjects.confirm(photo, SubjectSelection.New("My car", SubjectKind.OBJECT, emptyList()), region))
            assertEquals(NamedSearch.Matches(setOf(first)), searchNamed("my car", subjects.refresh(listOf(photo)), true))
            assertEquals(2, reads)
            digest = "sha256-v1:edited-metadata"
            assertEquals(NamedSearch.Matches(emptySet<Uri>()), searchNamed("my car", subjects.refresh(listOf(photo)), true))
            assertTrue(reopen().contentProofs().isEmpty())
            val id = reopen().reconcile(emptyMap()).subjects.single().id
            assertEquals(Confirmation.SAVED, subjects.confirm(photo, SubjectSelection.Existing(id), region))
            digest = null
            assertTrue(subjects.refresh(listOf(photo)).references.isEmpty())
            assertTrue(reopen().contentProofs().isEmpty())
        }
    }

    @Test
    fun contentProofRebindsOnlyExistingConfirmationsAndCannotResurrectRemovedOnes() = withStore { store, reopen, database ->
        val choice = SubjectSelection.New("My car", SubjectKind.OBJECT, emptyList())
        assertEquals(Confirmation.SAVED, store.confirm(first, available.getValue(first), choice, region, "sha256-v1:fixture"))
        val proof = reopen().contentProofs().getValue(first)
        val disk = database.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains("sha256-v1:fixture"))
        assertFalse(disk.contains("named_photo_content"))
        val revised = "gallery:renamed"
        assertFalse(store.rebindUnchanged(first, proof, revised, "different-file"))
        assertEquals(proof, store.contentProofs().getValue(first))
        assertTrue(store.rebindUnchanged(first, proof, revised, proof.digest))
        assertFalse(store.rebindUnchanged(first, proof, "stale-write", proof.digest))
        val current = reopen().reconcile(available + (first to revised))
        assertEquals(region, current.references.single().region)
        assertEquals(NamedSearch.Matches(setOf(first)), searchNamed("my car", current, true))
        assertFalse(current.references.any { it.uri == lookalike })
        val next = NamedContentProof(revised, proof.digest)
        store.remove(first, current.subjects.single().id)
        assertTrue(reopen().contentProofs().isEmpty())
        assertFalse(store.rebindUnchanged(first, next, "later", proof.digest))
        assertEquals(Confirmation.SAVED, store.confirm(first, revised, SubjectSelection.Existing(current.subjects.single().id), region, proof.digest))
        store.reconcile(available - first)
        assertTrue(reopen().contentProofs().isEmpty())
        assertFalse(store.rebindUnchanged(first, next, "later", proof.digest))
    }

    @Test
    fun confirmedViewsOnlyAfterReopenAndRevisionChanges() = withStore { store, reopen, database ->
        confirm(store, first, "My car", SubjectKind.OBJECT, listOf("My BMW car"), region)
        val original = store.reconcile(available)
        val id = original.subjects.single().id
        assertEquals(Confirmation.SAVED, store.confirm(second, available.getValue(second), SubjectSelection.Existing(id), null))
        assertEquals(Confirmation.DUPLICATE_NAME, store.confirm(lookalike, available.getValue(lookalike),
            SubjectSelection.New("Someone else's car", SubjectKind.OBJECT, listOf("MY CAR")), null))

        val reopened = reopen()
        val restored = reopened.reconcile(available)
        assertEquals(original.subjects, restored.subjects)
        assertEquals(region, restored.references.single { it.uri == first }.region)
        assertEquals(NamedSearch.Matches(setOf(first, second)), searchNamed("my car", restored, namedOnly = false))
        assertEquals(NamedSearch.Matches(setOf(first, second)), searchNamed("Find photos of MY BMW car!", restored, namedOnly = false))
        assertEquals(NamedSearch.Unknown, searchNamed("my carpet", restored, namedOnly = false))
        assertNull(searchNamed("a dog on a beach", restored, namedOnly = false))
        assertNull(searchNamed("show me a dog on a beach", restored, namedOnly = false))
        val disk = database.readBytes().toString(Charsets.ISO_8859_1)
        for (value in listOf("SQLite format 3", "named_references", "My car", "My BMW car", first.toString())) {
            assertFalse(value, disk.contains(value))
        }
        val changed = available + (first to "gallery:4")
        assertEquals(NamedSearch.Matches(setOf(second)), searchNamed("my car", reopened.reconcile(changed), true))
        assertEquals(NamedSearch.Matches(emptySet()), searchNamed("my car", reopened.reconcile(changed - second), true))
        assertEquals(emptySet<Uri>(), reopened.revisions())
    }

    @Test
    fun changingCurrentCollegePreservesPreviousConfirmedPhotos() = withStore { store, reopen, database ->
        assertEquals(Confirmation.INVALID_COLLEGE, store.confirm(lookalike, available.getValue(lookalike),
            SubjectSelection.New("BMW", SubjectKind.OBJECT, emptyList(), currentCollege = true), null))
        val old = SubjectSelection.New("First campus", SubjectKind.ORGANIZATION, emptyList(), currentCollege = true)
        val new = SubjectSelection.New("Second campus", SubjectKind.ORGANIZATION, emptyList(), currentCollege = true)
        assertEquals(Confirmation.SAVED, store.confirm(first, available.getValue(first), old, null))
        assertEquals(NamedSearch.Matches(setOf(first)), searchNamed("my current college", store.reconcile(available), true))
        assertEquals(Confirmation.SAVED, store.confirm(second, available.getValue(second), new, null))
        val current = reopen().reconcile(available)
        assertEquals(NamedSearch.Matches(setOf(first)), searchNamed("my previous college", current, true))
        assertEquals(NamedSearch.Matches(setOf(second)), searchNamed("my college", current, true))
        assertEquals(NamedSearch.Matches(setOf(second)), searchNamed("my new college", current, true))
        val currentId = current.collegeHistory.first()
        assertEquals(Confirmation.SAVED, store.confirm(second, available.getValue(second), SubjectSelection.Existing(currentId, true), null))
        assertEquals(current.collegeHistory, reopen().reconcile(available).collegeHistory)
        assertFalse(database.readBytes().toString(Charsets.ISO_8859_1).contains("First campus"))
    }

    @Test
    fun joinsConfirmedKindsAndRequiresEvidenceForExtraWords() = withStore { store, _, _ ->
        confirm(store, first, "Me", SubjectKind.PERSON, listOf("Myself"), region)
        confirm(store, first, "Red shirt", SubjectKind.CLOTHING)
        confirm(store, first, "My BMW car", SubjectKind.OBJECT)
        confirm(store, first, "Surat", SubjectKind.PLACE)
        confirm(store, first, "Old college", SubjectKind.ORGANIZATION, listOf("My previous college"))
        confirm(store, second, "New college", SubjectKind.ORGANIZATION, listOf("My current college"))
        confirm(store, second, "Passport", SubjectKind.DOCUMENT)
        confirm(store, second, "Study group", SubjectKind.GROUP)
        confirm(store, second, "Reaction meme", SubjectKind.MEME)
        val current = store.reconcile(available)
        assertEquals(NamedSearch.Matches(setOf(first)), searchNamed("my photo with red shirt and my bmw car and surat", current, true))
        assertEquals(NamedSearch.Nearby(setOf(first), setOf(first)), searchNamed("me and red shirt and my bmw car around Surat", current, true))
        assertEquals(NamedSearch.Nearby(setOf(first), setOf(first)), searchNamed("my bmw car in Surat", current, true))
        assertEquals(NamedSearch.Nearby(null, setOf(first)), searchNamed("near Surat", current, true))
        assertEquals(NamedSearch.Unresolved, searchNamed("me in red shirt", current, true))
        assertEquals(NamedSearch.Matches(setOf(second)), searchNamed("passport and study group and reaction meme", current, true))
        assertEquals(NamedSearch.Matches(emptySet()), searchNamed("passport with my BMW car", current, true))
        assertEquals(NamedSearch.Matches(setOf(first)), searchNamed("my previous college", current, true))
        assertEquals(NamedSearch.Matches(setOf(second)), searchNamed("my current college", current, true))
        assertEquals(NamedSearch.Unresolved, searchNamed("me in red shirt in front of my bmw car in surat", current, true))
        assertEquals(NamedSearch.Unresolved, searchNamed("my BMW car without passport", current, true))
        assertEquals(NamedSearch.Unknown, searchNamed("my unknown friend", current, false))
        assertNull(searchNamed("show me a dog on a beach", current, namedOnly = false))
        val shirt = current.subjects.single { it.kind == SubjectKind.CLOTHING }
        store.remove(first, shirt.id)
        val afterRemoval = store.reconcile(available)
        assertEquals(NamedSearch.Matches(emptySet()), searchNamed("red shirt with my BMW car", afterRemoval, true))
    }

    private fun confirm(
        store: NamedSubjectStore, uri: Uri, name: String, kind: SubjectKind,
        aliases: List<String> = emptyList(), area: PhotoRegion? = null,
    ) {
        assertEquals(Confirmation.SAVED, store.confirm(uri, available.getValue(uri), SubjectSelection.New(name, kind, aliases), area))
    }

    private fun withStore(action: (NamedSubjectStore, () -> NamedSubjectStore, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "named-subjects-${UUID.randomUUID()}"
        val directory = context.noBackupFilesDir.resolve(alias)
        val reopen = { NamedSubjectStore(EncryptedPhotoIndex(directory, alias)) }
        try {
            action(reopen(), reopen, directory.resolve("catalog.db"))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
