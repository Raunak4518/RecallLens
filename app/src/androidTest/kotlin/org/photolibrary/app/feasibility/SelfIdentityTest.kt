package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.SelfIdentity
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.planPhotoQuery
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.searchNamed
import org.photolibrary.app.subjects.subjectForReview

@RunWith(AndroidJUnit4::class)
class SelfIdentityTest {
    private val old = Uri.parse("content://fixture/self/legacy")
    private val actual = Uri.parse("content://fixture/self/selected")
    private val other = Uri.parse("content://fixture/self/other")
    private val scope = mapOf(old to "source:one", actual to "source:two", other to "source:three")

    @Test
    fun explicitPersonSelectionOverridesLegacyPronounsAndPreservesConfirmationsAfterReopen() = fixture { storage, reopen, directory ->
        val store = NamedSubjectStore(storage)
        seed(store)
        val legacy = store.reconcile(scope)
        val selected = legacy.subjects.single { it.name == "Ronak private identity fixture" }.id
        assertEquals(old, legacy.photosOf(setOf(checkNotNull(legacy.selfSubjectId))).single())
        assertTrue(store.setSelf(selected))
        val current = reopen().reconcile(scope)
        assertEquals(SelfIdentity(selected), current.selfIdentity)
        assertEquals(selected, current.selfSubjectId)
        assertEquals(legacy.subjects, current.subjects)
        assertEquals(legacy.references, current.references)
        for (query in listOf("me", "myself", "my photos", "find photos of myself")) {
            assertEquals(query, NamedSearch.Matches(setOf(actual)), searchNamed(query, current, true))
        }
        assertEquals(selected, subjectForReview("me", current)?.id)
        assertEquals(NamedSearch.Matches(setOf(old)), searchNamed("Legacy person", current, true))
        assertTrue(store.setSelf(legacy.subjects.single { it.name == "Me" }.id))
        assertEquals(NamedSearch.Matches(setOf(old)), searchNamed("my photos", reopen().reconcile(scope), true))
        val raw = directory.resolve("catalog.db").readBytes().toString(Charsets.ISO_8859_1)
        for (marker in listOf("SQLite format 3", "self_identity", "Ronak private identity fixture", actual.toString())) {
            assertFalse(marker, raw.contains(marker))
        }
    }

    @Test
    fun explicitClearSurvivesReopenAndDoesNotReenableLegacyNames() = fixture { storage, reopen, _ ->
        val store = NamedSubjectStore(storage)
        seed(store)
        val before = store.reconcile(scope)
        assertTrue(store.setSelf(null))
        val cleared = reopen().reconcile(scope)
        assertEquals(SelfIdentity(null), cleared.selfIdentity)
        assertNull(cleared.selfSubjectId)
        assertEquals(before.subjects, cleared.subjects)
        assertEquals(before.references, cleared.references)
        for (query in listOf("me", "myself", "my photos")) assertEquals(NamedSearch.Unknown, searchNamed(query, cleared, true))
        assertTrue(QueryRequirement.SELF_IDENTITY in planPhotoQuery("my photos", cleared, false).requirements)
        assertEquals(NamedSearch.Matches(setOf(old)), searchNamed("Legacy person", cleared, true))
        assertTrue(reopen().setSelf(before.subjects.single { it.kind == SubjectKind.PERSON && it.name != "Me" }.id))
        assertEquals(NamedSearch.Matches(setOf(actual)), searchNamed("me", reopen().reconcile(scope), true))
    }

    @Test
    fun missingAndNonPersonSelectionsDoNotOverwritePriorChoiceOrInventReferences() = fixture { storage, reopen, _ ->
        val store = NamedSubjectStore(storage)
        seed(store)
        val named = store.reconcile(scope)
        val person = named.subjects.single { it.name == "Ronak private identity fixture" }.id
        val objectId = named.subjects.single { it.kind == SubjectKind.OBJECT }.id
        assertTrue(store.setSelf(person))
        assertFalse(store.setSelf(objectId))
        assertFalse(store.setSelf(Long.MAX_VALUE))
        assertEquals(SelfIdentity(person), reopen().reconcile(scope).selfIdentity)
        val inaccessible = reopen().reconcile(scope - actual)
        assertEquals(person, inaccessible.selfSubjectId)
        assertEquals(NamedSearch.Matches(emptySet<Uri>()), searchNamed("me", inaccessible, true))
        assertEquals(NamedSearch.Matches(emptySet<Uri>()), searchNamed("me", reopen().reconcile(scope), true))
    }

    @Test
    fun schemaTenMigrationKeepsLegacyBehaviorUntilUserMakesAChoice() = fixture { storage, reopen, _ ->
        val store = NamedSubjectStore(storage)
        seed(store)
        val before = store.reconcile(scope)
        storage.withDatabase { db ->
            db.execSQL("DROP TABLE self_identity")
            db.version = 10
        }
        val migrated = reopen().reconcile(scope)
        assertNull(migrated.selfIdentity)
        assertEquals(before.selfSubjectId, migrated.selfSubjectId)
        assertEquals(before.references, migrated.references)
        assertEquals(NamedSearch.Matches(setOf(old)), searchNamed("me", migrated, true))
        storage.withDatabase { assertEquals(11, it.version) }
        assertTrue(reopen().setSelf(null))
        assertNull(reopen().reconcile(scope).selfSubjectId)
    }

    @Test
    fun explicitPronounsOverrideBothLiteralLabelsAndAmbiguousLegacyFallback() {
        val people = listOf(
            NamedSubject(1, "Me", SubjectKind.PERSON, emptyList()),
            NamedSubject(2, "Myself", SubjectKind.PERSON, emptyList()),
            NamedSubject(3, "Ronak", SubjectKind.PERSON, emptyList()),
        )
        val references = listOf(SubjectReference(1, old, "one", null), SubjectReference(2, other, "three", null),
            SubjectReference(3, actual, "two", null))
        val legacy = NamedLibrary(people, references)
        assertNull(legacy.selfSubjectId)
        val selected = legacy.copy(selfIdentity = SelfIdentity(3))
        assertEquals(NamedSearch.Matches(setOf(actual)), searchNamed("me", selected, true))
        assertEquals(NamedSearch.Matches(setOf(actual)), searchNamed("myself", selected, true))
        val cleared = legacy.copy(selfIdentity = SelfIdentity(null))
        assertEquals(NamedSearch.Unknown, searchNamed("me", cleared, true))
        assertEquals(NamedSearch.Unknown, searchNamed("myself", cleared, true))
        assertNull(legacy.copy(selfIdentity = SelfIdentity(999)).selfSubjectId)
    }

    private fun seed(store: NamedSubjectStore) {
        val entries = listOf(
            Triple(old, "Me", SubjectKind.PERSON), Triple(actual, "Ronak private identity fixture", SubjectKind.PERSON),
            Triple(other, "My car", SubjectKind.OBJECT),
        )
        for ((uri, name, kind) in entries) {
            assertEquals(Confirmation.SAVED, store.confirm(uri, scope.getValue(uri),
                SubjectSelection.New(name, kind, if (uri == old) listOf("Legacy person") else emptyList()), null))
        }
    }

    private fun fixture(action: (EncryptedPhotoIndex, () -> NamedSubjectStore, java.io.File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "self-identity-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val storage = EncryptedPhotoIndex(directory, alias)
        try { action(storage, { NamedSubjectStore(EncryptedPhotoIndex(directory, alias)) }, directory) }
        finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
