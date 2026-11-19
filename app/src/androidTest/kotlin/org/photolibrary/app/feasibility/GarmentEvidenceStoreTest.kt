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
import org.photolibrary.app.detection.GarmentColor
import org.photolibrary.app.detection.PersonGarmentEvidence
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.GarmentDependencies
import org.photolibrary.app.index.GarmentEvidenceStore
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference

@RunWith(AndroidJUnit4::class)
class GarmentEvidenceStoreTest {
    private val uri = Uri.parse("content://fixture/private-red-clothing")
    private val revision = "source:garment-revision-1"
    private val face = PhotoRegion(.1f, .05f, .3f, .25f)
    private val body = PhotoRegion(.05f, 0f, .45f, 1f)
    private val parser = ModelRevision("private-parser", "1", 512)
    private val dependencies = GarmentDependencies(ModelRevision("face", "1", 640), ModelRevision("person", "1", 416), parser)
    private val records = listOf(PersonGarmentEvidence.Found(face, body, parser, 50, mapOf(GarmentColor.RED to .9f, GarmentColor.WHITE to .1f)))

    @Test
    fun verifiedInputCanRebindButDependenciesAccessAndDeletionStillInvalidate() = fixture { _, open ->
        val store = open()
        assertTrue(store.save(uri, revision, dependencies, records, "pixels:original") { revision })
        val renamed = "source:renamed"
        store.reconcile(mapOf(uri to renamed))
        assertNull(store.read(uri, renamed, dependencies))
        assertEquals("pixels:original", open().pendingFingerprint(uri, dependencies))
        assertFalse(store.rebind(uri, renamed, dependencies, "pixels:edited") { renamed })
        assertFalse(store.rebind(uri, renamed, dependencies.copy(colorPolicy = "different"), "pixels:original") { renamed })
        assertFalse(store.rebind(uri, renamed, dependencies, "pixels:original") { null })
        assertTrue(store.rebind(uri, renamed, dependencies, "pixels:original") { renamed })
        assertEquals(records, open().read(uri, renamed, dependencies))
        var calls = 0
        assertFalse(store.rebind(uri, "source:move", dependencies, "pixels:original") {
            if (++calls == 1) "source:move" else null
        })
        assertNull(store.pendingFingerprint(uri, dependencies))
        assertFalse(store.rebind(uri, renamed, dependencies, "pixels:original") { renamed })
        assertTrue(store.save(uri, revision, dependencies, records, "pixels:original") { revision })
        store.reconcile(emptyMap())
        assertNull(open().pendingFingerprint(uri, dependencies))
        assertFalse(store.rebind(uri, renamed, dependencies, "pixels:original") { renamed })
    }

    @Test
    fun copiedDatabaseHidesRelationsAndCacheRequiresExactRevisions() = fixture { root, open ->
        val store = open()
        assertTrue(store.save(uri, revision, dependencies, records) { revision })
        assertEquals(records, open().read(uri, revision, dependencies))
        assertNull(store.read(uri, "source:edited", dependencies))
        assertNull(store.read(uri, revision, dependencies.copy(parser = parser.copy(version = "2"))))
        val database = root.resolve("catalog.db").readBytes().toString(Charsets.ISO_8859_1)
        for (marker in listOf("SQLite format 3", uri.toString(), revision, "private-parser", "RED")) {
            assertFalse(marker, database.contains(marker))
        }
    }

    @Test
    fun onlyConfirmedSpecificFaceCanSatisfyColorQuery() = fixture { _, open ->
        val store = open()
        assertTrue(store.save(uri, revision, dependencies, records) { revision })
        val subject = NamedSubject(1, "Me", SubjectKind.PERSON, emptyList())
        val named = NamedLibrary(listOf(subject), listOf(SubjectReference(1, uri, revision, face)))
        val scope = mapOf(uri to revision)
        assertEquals(mapOf(uri to .9f), store.colorFractions(1, GarmentColor.RED, named, scope, dependencies))
        assertEquals(setOf(uri), store.confirmedColorMatches(1, GarmentColor.RED, named, scope, dependencies))
        assertTrue(store.confirmedColorMatches(1, GarmentColor.WHITE, named, scope, dependencies).isEmpty())
        assertTrue(store.confirmedColorMatches(1, GarmentColor.RED, named.copy(references = emptyList()), scope, dependencies).isEmpty())
        assertTrue(store.confirmedColorMatches(1, GarmentColor.RED,
            named.copy(references = listOf(SubjectReference(1, uri, revision, null))), scope, dependencies).isEmpty())
        assertTrue(store.confirmedColorMatches(1, GarmentColor.RED, named, emptyMap(), dependencies).isEmpty())
        val wrongFace = PhotoRegion(.6f, .1f, .8f, .3f)
        val wrong = named.copy(references = listOf(SubjectReference(1, uri, revision, wrongFace)))
        assertTrue(store.confirmedColorMatches(1, GarmentColor.RED, wrong, scope, dependencies).isEmpty())
        val splitColors = listOf(PersonGarmentEvidence.Found(face, body, parser, 100,
            mapOf(GarmentColor.GREEN to .49f, GarmentColor.YELLOW to .45f, GarmentColor.WHITE to .06f)))
        assertTrue(store.save(uri, revision, dependencies, splitColors) { revision })
        assertEquals(mapOf(uri to .49f), store.colorFractions(1, GarmentColor.GREEN, named, scope, dependencies))
        assertTrue(store.confirmedColorMatches(1, GarmentColor.GREEN, named, scope, dependencies).isEmpty())
        assertTrue(store.colorFractions(1, GarmentColor.GREEN, wrong, scope, dependencies).isEmpty())
    }

    @Test
    fun sourceChangesDuringCommitAndRevocationPurgeCache() = fixture { _, open ->
        val store = open()
        var reads = 0
        assertFalse(store.save(uri, revision, dependencies, records) { if (++reads == 1) revision else "source:edited" })
        assertNull(store.read(uri, revision, dependencies))
        assertTrue(store.save(uri, revision, dependencies, records) { revision })
        store.reconcile(mapOf(uri to "source:edited"))
        assertNull(store.read(uri, revision, dependencies))
        assertTrue(store.save(uri, revision, dependencies, records) { revision })
        store.reconcile(emptyMap())
        assertNull(store.read(uri, revision, dependencies))
    }

    @Test
    fun schemaEightGarmentRowsMigrateWithoutInventingFingerprints() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "garment-migration-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val storage = EncryptedPhotoIndex(directory, alias)
        try {
            val store = GarmentEvidenceStore(storage)
            assertTrue(store.save(uri, revision, dependencies, records) { revision })
            storage.withDatabase { db ->
                db.execSQL("ALTER TABLE person_garments DROP COLUMN write_token")
                db.execSQL("ALTER TABLE person_garments DROP COLUMN pixel_revision")
                db.execSQL("DROP TABLE self_identity")
                db.version = 8
            }
            val migrated = GarmentEvidenceStore(EncryptedPhotoIndex(directory, alias))
            assertEquals(records, migrated.read(uri, revision, dependencies))
            assertNull(migrated.pendingFingerprint(uri, dependencies))
            migrated.reconcile(mapOf(uri to "source:renamed"))
            assertNull(migrated.read(uri, revision, dependencies))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private fun fixture(action: (java.io.File, () -> GarmentEvidenceStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "garment-fixture-${UUID.randomUUID()}"
        val directory = context.noBackupFilesDir.resolve(alias)
        try {
            action(directory) { GarmentEvidenceStore(EncryptedPhotoIndex(directory, alias)) }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
