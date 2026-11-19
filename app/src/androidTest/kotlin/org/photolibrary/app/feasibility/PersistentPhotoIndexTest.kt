package org.photolibrary.app.feasibility

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.index.IndexKey
import org.photolibrary.app.index.IndexedPhoto
import org.photolibrary.app.index.LocalPhotoIndexer
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoIndexUnavailable
import org.photolibrary.app.index.PhotoProcessor
import org.photolibrary.app.index.PhotoProcessorFactory
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.PhotoTextCodec
import org.photolibrary.app.index.TextBounds
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.index.normalizePhotoText
import org.photolibrary.app.index.transaction
import org.photolibrary.app.identity.IdentityCacheCodec
import org.photolibrary.app.identity.IdentityCacheEntry
import org.photolibrary.app.identity.IdentityRegion
import org.photolibrary.app.identity.IdentityVector
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.semantic.PhotoEmbedding
import org.photolibrary.app.semantic.closestPhotos
import org.photolibrary.app.subjects.SubjectKind

@RunWith(AndroidJUnit4::class)
class PersistentPhotoIndexTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun versionSevenMigrationPreservesLegacyNamesWithoutInventingContentProofs() = fixture { storage, directory, alias ->
        val uri = Uri.parse("content://fixture/legacy-name")
        val store = org.photolibrary.app.index.NamedSubjectStore(storage)
        assertEquals(org.photolibrary.app.subjects.Confirmation.SAVED, store.confirm(uri, "source:legacy",
            org.photolibrary.app.subjects.SubjectSelection.New("My car", SubjectKind.OBJECT, emptyList()), null))
        storage.withDatabase { db ->
            db.execSQL("DROP TABLE named_photo_content")
            db.execSQL("ALTER TABLE person_garments DROP COLUMN write_token")
            db.execSQL("ALTER TABLE person_garments DROP COLUMN pixel_revision")
            db.execSQL("DROP TABLE self_identity")
            db.execSQL("DROP TABLE identity_rejections")
            db.execSQL("DROP TABLE named_dated_aliases")
            db.execSQL("DROP TABLE suggested_regions")
            db.version = 7
        }
        val reopened = EncryptedPhotoIndex(directory, alias)
        val migrated = org.photolibrary.app.index.NamedSubjectStore(reopened)
        assertEquals(setOf(uri), migrated.revisions())
        assertTrue(migrated.contentProofs().isEmpty())
        assertEquals(1, migrated.reconcile(mapOf(uri to "source:legacy")).references.size)
        assertTrue(migrated.reconcile(mapOf(uri to "source:renamed")).references.isEmpty())
        reopened.withDatabase { assertEquals(15, it.version) }
    }

    @Test
    fun localOcrSearchReopensWithoutPlaintextInPrivateCacheOrIndex() = runBlocking<Unit> {
        val marker = "CEDAR " + (100_000 + SecureRandom().nextInt(900_000))
        val uri = insertFixture(context, marker)
        val library = PhotoLibrary(context)
        val directory = File(context.noBackupFilesDir, "privacy-fixture-" + UUID.randomUUID())
        val alias = directory.name
        try {
            val revision = checkNotNull(library.currentRevision(uri))
            val source = IndexPhoto(uri, revision)
            val storage = EncryptedPhotoIndex(directory, alias)
            val indexer = LocalTextIndex.create(storage, library::loadPixels, library::currentRevision)
            val query = normalizePhotoText(marker)
            indexer.refresh(listOf(source), { _, _ -> }) { indexed, output ->
                assertEquals(uri, indexed)
                assertTrue("The OCR result must contain the synthetic phrase", output?.matches(query) == true)
            }
            val reopened = LocalTextIndex.create(
                EncryptedPhotoIndex(directory, alias),
                { _, _ -> error("Cached search opened source pixels") },
                library::currentRevision,
            )
            assertTrue(reopened.readCached(listOf(source), setOf(uri)).getValue(uri).matches(query))
            assertNoPlaintext(directory, listOf("SQLite format 3", marker, uri.toString()))
            val appData = File(context.applicationInfo.dataDir)
            val roots = listOf(context.cacheDir, context.codeCacheDir, context.filesDir,
                File(appData, "databases"), File(appData, "shared_prefs"))
            var inspected = 0
            for (file in roots.asSequence().flatMap { it.walkTopDown() }.filter { it.isFile && it.length() <= 8_388_608 }) {
                val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse("Synthetic OCR phrase found in private file " + file.name, bytes.contains(marker, ignoreCase = true))
                inspected++
            }
            Log.i("LocalPrivacyTest", "Real OCR matched after encrypted reopen. Inspected files up to 8 MiB: " + inspected)
        } finally {
            context.contentResolver.delete(uri, null, null)
            keyStore().deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    @Test
    fun runtimeStoreEncryptsRowsAndFailsClosedWhenKeyMaterialIsChanged() = fixture { storage, directory, alias ->
        val uri = Uri.parse("content://fixture/confidential-name-609413")
        val marker = "ENCRYPTED PRAIRIE 609413"
        val model = ModelRevision("test", "1", 384)
        storage.open().use {
            it.save(uri, IndexedPhoto("source-revision-609413", null, model,
                PhotoText(listOf(TextLine(marker, "en", TextBounds(1, 2, 32, 40))), complete = false)))
        }
        val database = File(directory, "catalog.db")
        val key = File(directory, "index.key")
        val passphrase = IndexKey(key, alias).load(databasePresent = true)
        val secrets = listOf("SQLite format 3", marker, uri.toString(), "source-revision-609413", "journal-transaction-marker-4901")
        try {
            SQLiteDatabase.openDatabase(database.path, passphrase, null, SQLiteDatabase.OPEN_READWRITE,
                { _, failure -> throw failure }, null).use { db ->
                db.beginTransaction()
                try {
                    db.execSQL("UPDATE photo_text SET output = ? WHERE uri = ?", arrayOf(secrets.last(), uri.toString()))
                    assertTrue(File(directory, "catalog.db-journal").length() > 512)
                    assertNoPlaintext(directory, secrets)
                } finally {
                    db.endTransaction()
                }
            }
        } finally {
            passphrase.fill(0)
        }
        val original = database.readBytes()
        assertTrue(original.isNotEmpty())
        assertNoPlaintext(directory, secrets)
        val wrongKey = ByteArray(32).also(SecureRandom()::nextBytes)
        assertThrows(android.database.SQLException::class.java) {
            SQLiteDatabase.openDatabase(database.path, wrongKey, null, SQLiteDatabase.OPEN_READONLY,
                { _, failure -> throw failure }, null).use { it.version }
        }
        assertArrayEquals(original, database.readBytes())

        val wrapped = key.readBytes()
        key.writeBytes(wrapped.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertThrows(PhotoIndexUnavailable::class.java) { storage.open().close() }
        assertArrayEquals(original, database.readBytes())
        key.writeBytes(wrapped)
        storage.open().use { session ->
            val record = checkNotNull(session.reconcile(setOf(uri))[uri])
            assertEquals(model, record.model)
            assertEquals("source-revision-609413", record.sourceRevision)
            assertEquals(marker, record.output.lines.single().text)
            assertEquals(TextBounds(1, 2, 32, 40), record.output.lines.single().bounds)
            assertFalse(record.output.complete)
        }
        keyStore().deleteEntry(alias)
        assertThrows(PhotoIndexUnavailable::class.java) { storage.open().close() }
        assertArrayEquals(original, database.readBytes())
    }

    @Test
    fun legacyTextRowsRemainReadable() {
        val text = PhotoTextCodec.decode("""[{"text":"Gate 4821","language":"en","bounds":[1,2,32,40]}]""")
        assertTrue(text.complete)
        assertTrue(text.matches(normalizePhotoText("gate 4821")))
        assertThrows(PhotoIndexUnavailable::class.java) {
            PhotoTextCodec.decode("""{"version":2,"complete":true,"lines":[]}""")
        }
    }

    @Test
    fun pendingMetadataAndScopedReadsAvoidUnrequestedPayloadsAndPruneRevokedRows() = fixture { storage, _, _ ->
        val kept = Uri.parse("content://fixture/kept")
        val revoked = Uri.parse("content://fixture/revoked")
        val pending = Uri.parse("content://fixture/pending")
        val model = ModelRevision("fixture", "1", 1600)
        storage.open().use { store ->
            for (uri in listOf(kept, revoked, pending)) {
                store.save(uri, IndexedPhoto("v1", null, model, PhotoText(emptyList())))
            }
        }
        storage.withDatabase { db ->
            db.execSQL("UPDATE photo_text SET output = ? WHERE uri = ?", arrayOf("invalid-json", kept.toString()))
        }
        storage.open().use { store ->
            assertEquals(setOf(kept), store.current(mapOf(kept to "v1", pending to "v2"), model))
            assertEquals(emptyMap<Uri, Any>(), store.reconcile(setOf(kept, pending), setOf(pending)))
        }
        storage.withDatabase { db ->
            db.rawQuery("SELECT uri FROM photo_text", emptyArray<String>()).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(kept.toString(), rows.getString(0))
                assertFalse(rows.moveToNext())
            }
        }
    }

    @Test
    fun fiveThousandRowsRestoreWithoutOpeningModelsOrReadingPixels() = fixture { storage, directory, alias ->
        runBlocking {
            val count = 5_000
            val photos = List(count) { IndexPhoto(Uri.parse("content://scale/photo/$it"), "revision-$it") }
            val target = photos.last().uri
            val accessible = photos.mapTo(hashSetOf()) { it.uri }
            val revisions = photos.associate { it.uri to it.sourceRevision }
            val model = ModelRevision("scale-fixture", "1", 1600)
            val positive = PhotoEmbedding.normalized(FloatArray(512) { if (it == 0) 1f else 0f })
            val negative = PhotoEmbedding.normalized(FloatArray(512) { if (it == 1) 1f else 0f })
            val text = PhotoTextCodec.encode(PhotoText(listOf(TextLine("CONFIDENTIAL SCALE FIXTURE", "en", null))))
            val targetText = PhotoTextCodec.encode(PhotoText(listOf(TextLine("GATE 4821", "en", null)), complete = false))
            val face = IdentityCacheCodec.encode(IdentityCacheEntry(listOf(
                IdentityRegion(null, IdentityVector(FloatArray(128) { .08838835f }, null)),
            )))
            val objectRegion = IdentityCacheCodec.encode(IdentityCacheEntry(listOf(
                IdentityRegion(null, IdentityVector(FloatArray(384) { .05103104f }, FloatArray(18) { .23570226f })),
            )))
            storage.withDatabase { database ->
                database.transaction {
                    for (photo in photos) {
                        fun insert(table: String, payload: Any, revision: String? = photo.sourceRevision) {
                            database.execSQL(
                                "INSERT INTO $table (uri, source_revision, model, model_version, model_edge, output) VALUES (?, ?, ?, ?, ?, ?)",
                                arrayOf(photo.uri.toString(), revision, model.name, model.version, model.maxImageEdge, payload),
                            )
                        }
                        val matching = photo.uri == target
                        insert("photo_text", if (matching) targetText else text)
                        insert("image_embeddings", if (matching) positive.encode() else negative.encode())
                        insert("face_embeddings", face, "source:${photo.sourceRevision}")
                        insert("object_embeddings", objectRegion, "source:${photo.sourceRevision}")
                    }
                }
            }
            val reopen = EncryptedPhotoIndex(directory, alias)
            suspend fun <T : Any> restore(open: () -> org.photolibrary.app.index.PhotoIndexSession<T>): Map<Uri, T> {
                var restored = emptyMap<Uri, T>()
                LocalPhotoIndexer(
                    loadImage = { _, _ -> error("Unexpected image decode") },
                    processorFactory = PhotoProcessorFactory<T>(model) { error("Unexpected processor creation") },
                    openIndex = open,
                    readRevision = { error("Unexpected per-photo revision lookup") },
                ).refresh(photos, { result, pending ->
                    assertEquals(0, pending)
                    restored = result
                }, { _, _ -> error("Unexpected indexing") })
                assertEquals(count, restored.size)
                return restored
            }
            val query = normalizePhotoText("gate 4821")
            suspend fun measure(pruneFirst: Boolean): Pair<Long, Long> {
                val start = SystemClock.elapsedRealtimeNanos()
                if (pruneFirst) reopen.pruneIdentity(revisions)
                val texts = restore(reopen::open)
                assertEquals(setOf(target), texts.filterValues { it.matches(query) }.keys)
                assertFalse(texts.getValue(target).complete)
                val textNs = SystemClock.elapsedRealtimeNanos() - start
                val vectors = restore(reopen::openEmbeddings)
                assertEquals(target, closestPhotos(positive, vectors, 24).first())
                val bothNs = SystemClock.elapsedRealtimeNanos() - start
                if (!pruneFirst) reopen.pruneIdentity(revisions)
                return textNs to bothNs
            }
            measure(true)
            measure(false)
            val before = mutableListOf<Pair<Long, Long>>()
            val after = mutableListOf<Pair<Long, Long>>()
            repeat(5) { run ->
                if (run % 2 == 0) {
                    before += measure(true)
                    after += measure(false)
                } else {
                    after += measure(false)
                    before += measure(true)
                }
            }
            val identityLoadMs = mutableListOf<Long>()
            for (kind in listOf(SubjectKind.PERSON, SubjectKind.OBJECT)) {
                val start = SystemClock.elapsedRealtime()
                val entries = reopen.openIdentity(kind).use { it.reconcile(accessible) }
                assertEquals(count, entries.size)
                assertEquals(1, entries.getValue(target).output.gallery?.size)
                identityLoadMs += SystemClock.elapsedRealtime() - start
            }
            val bytes = directory.listFiles().orEmpty().sumOf { it.length() }
            fun samplesMs(samples: List<Pair<Long, Long>>) =
                samples.joinToString { (textNs, bothNs) -> "${textNs / 1_000_000}/${bothNs / 1_000_000}" }
            Log.i("PhotoIndexScale", "photos=$count bytes=$bytes text/both ms before=[${samplesMs(before)}] " +
                "after=[${samplesMs(after)}] face/object load ms=$identityLoadMs")
        }
    }

    @Test
    fun realGalleryEditAndDeletionOnlyReprocessChangedPhotos() = runBlocking<Unit> {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, READ_MEDIA_IMAGES)
        assertEquals(PERMISSION_GRANTED, context.checkSelfPermission(READ_MEDIA_IMAGES))
        val library = PhotoLibrary(context)
        val first = insertFixture(context, "OAK STATION 4162")
        val second = insertFixture(context, "PINE BRIDGE 9157")
        val uriSet = setOf(first, second)
        val remainingFixtures = uriSet.toMutableSet()
        var decodes = 0
        var inference = 0
        var modelOpens = 0
        val model = LocalTextIndex.recognizer().use { it.model }
        val directory = File(context.noBackupFilesDir, "persistent-fixture-${UUID.randomUUID()}")
        val alias = "persistent-fixture-${UUID.randomUUID()}"
        fun createIndexer(): LocalPhotoIndexer<PhotoText> = LocalPhotoIndexer(
            loadImage = { uri, edge ->
                decodes++
                library.loadPixels(uri, edge)
            },
            processorFactory = PhotoProcessorFactory(model) {
                modelOpens++
                val recognizer = LocalTextIndex.recognizer()
                object : PhotoProcessor<PhotoText> by recognizer {
                    override fun process(image: Bitmap): PhotoText {
                        inference++
                        return recognizer.process(image)
                    }
                }
            },
            openIndex = EncryptedPhotoIndex(directory, alias)::open,
            readRevision = library::currentRevision,
        )
        suspend fun collect(indexer: LocalPhotoIndexer<PhotoText>): Map<Uri, PhotoText> {
            val inputs = library.list().photos.filter { it.uri in uriSet }.map { IndexPhoto(it.uri, it.sourceRevision) }
            inputs.forEach { assertNotNull(it.sourceRevision) }
            val results = mutableMapOf<Uri, PhotoText>()
            indexer.currentPhotos(inputs)
            indexer.refresh(inputs, { saved, _ -> results.putAll(saved) }) { uri, output ->
                if (output != null) results[uri] = output
            }
            return results
        }
        try {
            val initial = collect(createIndexer())
            assertTrue(initial.getValue(first).matches(normalizePhotoText("oak station")))
            assertTrue(initial.getValue(second).matches(normalizePhotoText("pine bridge")))
            assertEquals(2 to 2, decodes to inference)

            decodes = 0
            inference = 0
            val restored = collect(createIndexer())
            assertEquals(uriSet, restored.keys)
            assertTrue(restored.getValue(first).matches(normalizePhotoText("oak station")))
            assertEquals(0 to 0, decodes to inference)
            Log.i("PhotoLibraryIndexTest", "Initial 2 decodes / 2 OCR calls. Reopen 0 decodes / 0 OCR calls.")

            val resolver = context.contentResolver
            fun originalDigest() = resolver.openInputStream(first)!!.use { input ->
                MessageDigest.getInstance("SHA-256").digest(input.readBytes()).toList()
            }
            val original = originalDigest()
            val beforeRename = library.currentRevision(first)
            val opensBeforeMetadataChanges = modelOpens
            assertEquals(1, resolver.update(first, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "renamed-" + UUID.randomUUID() + ".png")
            }, null, null))
            assertTrue(beforeRename != library.currentRevision(first))
            val renamed = collect(createIndexer())
            assertTrue(renamed.getValue(first).matches(normalizePhotoText("oak station")))
            assertEquals(1 to 0, decodes to inference)
            assertEquals(opensBeforeMetadataChanges, modelOpens)
            assertEquals(original, originalDigest())

            val beforeMove = library.currentRevision(first)
            decodes = 0
            assertEquals(1, resolver.update(first, ContentValues().apply {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoLibrary-" + UUID.randomUUID() + "/")
            }, null, null))
            assertTrue(beforeMove != library.currentRevision(first))
            val moved = collect(createIndexer())
            assertTrue(moved.getValue(first).matches(normalizePhotoText("oak station")))
            assertEquals(1 to 0, decodes to inference)
            assertEquals(opensBeforeMetadataChanges, modelOpens)
            assertEquals(original, originalDigest())
            decodes = 0
            collect(createIndexer())
            assertEquals(0 to 0, decodes to inference)
            assertEquals(opensBeforeMetadataChanges, modelOpens)
            Log.i("PhotoLibraryIndexTest", "Rename and same-volume move each used 1 decode / 0 OCR / 0 model opens. Reopen used 0 decodes.")

            val originalRevision = library.currentRevision(first)
            rewriteImage(context, first, "MAPLE STATION 7185")
            val editedRevision = library.currentRevision(first)
            assertTrue("MediaStore kept the initial revision after edit: $editedRevision", originalRevision != editedRevision)
            val edited = collect(createIndexer())
            assertTrue("Edited fixture text: ${edited.getValue(first).lines.map { it.text }} ($decodes decodes, $inference OCR calls)",
                edited.getValue(first).matches(normalizePhotoText("7185")))
            assertFalse(edited.getValue(first).matches(normalizePhotoText("4162")))
            assertTrue(edited.getValue(second).matches(normalizePhotoText("pine bridge")))
            assertEquals(1 to 1, decodes to inference)

            assertEquals(1, context.contentResolver.delete(first, null, null))
            remainingFixtures.remove(first)
            val afterDeletion = collect(createIndexer())
            assertEquals(setOf(second), afterDeletion.keys)
            EncryptedPhotoIndex(directory, alias).open().use { session ->
                assertEquals(setOf(second), session.reconcile(uriSet).keys)
            }
            assertEquals(1 to 1, decodes to inference)
            Log.i("PhotoLibraryIndexTest", "Edit 1 decode / 1 OCR call. Deletion removed the stored row without OCR.")
        } finally {
            for (uri in remainingFixtures) context.contentResolver.delete(uri, null, null)
            keyStore().deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private fun fixture(action: (EncryptedPhotoIndex, File, String) -> Unit) {
        val directory = File(context.noBackupFilesDir, "encrypted-fixture-${UUID.randomUUID()}")
        val alias = "encrypted-fixture-${UUID.randomUUID()}"
        try {
            action(EncryptedPhotoIndex(directory, alias), directory, alias)
        } finally {
            keyStore().deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun assertNoPlaintext(directory: File, secrets: List<String>) {
        for (file in directory.listFiles().orEmpty().filter { it.isFile }) {
            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            for (secret in secrets) assertFalse(bytes.contains(secret))
        }
    }

    private fun insertFixture(context: Context, label: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "index-fixture-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoLibraryIndexTest/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        rewriteImage(context, uri, label)
        return uri
    }

    private fun rewriteImage(context: Context, uri: Uri, label: String) {
        val resolver = context.contentResolver
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
        val bitmap = Bitmap.createBitmap(1080, 420, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText(label, 45f, 180f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 94f
            })
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } finally {
            bitmap.recycle()
        }
    }
}
