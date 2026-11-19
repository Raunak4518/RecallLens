package org.photolibrary.app.index

import android.database.SQLException
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.photolibrary.app.identity.IdentityCacheCodec
import org.photolibrary.app.identity.IdentityCacheEntry
import org.photolibrary.app.library.UnavailableSources
import org.photolibrary.app.library.SuggestionRecordCodec
import org.photolibrary.app.library.SuggestionRegion
import org.json.JSONException
import org.photolibrary.app.semantic.PhotoEmbedding
import org.photolibrary.app.subjects.SubjectKind

internal class EncryptedPhotoIndex(private val directory: File, alias: String) {
    private val database = File(directory, "catalog.db")
    private val key = IndexKey(File(directory, "index.key"), alias)
    private val lock = Any()

    fun open(): PhotoIndexSession<PhotoText> = openTable(TEXT)

    fun openEmbeddings(): PhotoIndexSession<PhotoEmbedding> = openTable(EMBEDDINGS)

    fun openIdentity(kind: SubjectKind): PhotoIndexSession<IdentityCacheEntry> = openTable(IDENTITY.getValue(kind))

    fun openSuggestions(): PhotoIndexSession<List<SuggestionRegion>> = openTable(SUGGESTIONS)

    fun pruneIdentity(accessible: Map<Uri, String?>, unavailable: UnavailableSources = UnavailableSources.NONE) = withDatabase { db ->
        db.transaction {
            for (table in IDENTITY.values + SUGGESTIONS) {
                val stale = db.rawQuery("SELECT uri, source_revision, pixel_revision FROM ${table.name}", emptyArray<String>()).use { rows ->
                    buildList {
                        while (rows.moveToNext()) {
                            val path = rows.getString(0)
                            val uri = Uri.parse(path)
                            val source = accessible[uri]
                            if (uri !in accessible && uri !in unavailable ||
                                source != null && rows.getString(1) != "source:$source" && rows.isNull(2)) add(path)
                        }
                    }
                }
                stale.forEach { db.execSQL("DELETE FROM ${table.name} WHERE uri = ?", arrayOf(it)) }
            }
        }
    }

    private fun <Output> openTable(table: Table<Output>): PhotoIndexSession<Output> = synchronized(lock) { storageOperation {
        connect(keepOpen = true) { db, passphrase -> Session(db, passphrase, table, lock) }
    } }

    internal fun <T> withDatabase(action: (SQLiteDatabase) -> T): T = synchronized(lock) { storageOperation {
        connect { db, _ -> action(db) }
    } }

    private fun <T> connect(keepOpen: Boolean = false, action: (SQLiteDatabase, ByteArray) -> T): T {
        if (!directory.isDirectory && !directory.mkdirs()) throw PhotoIndexUnavailable()
        System.loadLibrary("sqlcipher")
        val existing = database.exists()
        val passphrase = key.load(existing)
        return try {
            val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                (if (existing) 0 else SQLiteDatabase.CREATE_IF_NECESSARY)
            val db = SQLiteDatabase.openDatabase(database.path, passphrase, null, flags, { _, failure -> throw failure }, null)
            var succeeded = false
            try {
                configure(db)
                action(db, passphrase).also { succeeded = true }
            } finally {
                if (!keepOpen || !succeeded) db.close()
                if (!keepOpen) passphrase.fill(0)
            }
        } catch (failure: Throwable) {
            passphrase.fill(0)
            throw failure
        }
    }

    private fun configure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA temp_store = MEMORY")
        db.rawQuery("PRAGMA journal_mode = DELETE", emptyArray<String>()).use {
            if (!it.moveToFirst() || it.getString(0) != "delete") throw PhotoIndexUnavailable()
        }
        db.rawQuery("PRAGMA secure_delete = ON", emptyArray<String>()).use {
            if (!it.moveToFirst() || it.getInt(0) != 1) throw PhotoIndexUnavailable()
        }
        val version = db.version
        if (version !in 0..SCHEMA_VERSION) throw PhotoIndexUnavailable()
        if (version < SCHEMA_VERSION) db.transaction {
            if (version == 0) createTable(db, TEXT.name, "TEXT")
            if (version < 2) createTable(db, EMBEDDINGS.name, "BLOB")
            if (version < 3) NamedSubjectStore.createTables(db)
            if (version < 4) NamedSubjectStore.createCollegeHistory(db)
            if (version < 5) IDENTITY.values.forEach { createTable(db, it.name, "BLOB") }
            if (version < 6) LocationCache.createTable(db)
            if (version < 7) GarmentEvidenceStore.createTable(db)
            if (version < 8) NamedSubjectStore.createContentProofs(db)
            if (version < 9) db.execSQL("ALTER TABLE person_garments ADD COLUMN pixel_revision TEXT")
            if (version < 10) db.execSQL("ALTER TABLE person_garments ADD COLUMN write_token TEXT")
            if (version < 11) NamedSubjectStore.createSelfIdentity(db)
            if (version < 12) db.execSQL("DROP TABLE IF EXISTS cloud_configuration")
            if (version < 13) NamedSubjectStore.createIdentityRejections(db)
            if (version < 14) NamedSubjectStore.createDatedAliases(db)
            if (version < 15) createTable(db, SUGGESTIONS.name, "BLOB", ifMissing = true)
            db.version = SCHEMA_VERSION
        }
    }

    private fun createTable(db: SQLiteDatabase, name: String, type: String, ifMissing: Boolean = false) {
        val guard = if (ifMissing) "IF NOT EXISTS " else ""
        db.execSQL(
            """CREATE TABLE $guard$name (
                uri TEXT PRIMARY KEY, source_revision TEXT, pixel_revision TEXT,
                model TEXT NOT NULL, model_version TEXT NOT NULL, model_edge INTEGER NOT NULL,
                output $type NOT NULL
            ) WITHOUT ROWID""".trimIndent(),
        )
    }

    private class Table<Output>(val name: String, val encode: (Output) -> Any, val decode: (Cursor, Int) -> Output)

    private class Session<Output>(
        private val db: SQLiteDatabase,
        private val passphrase: ByteArray,
        private val table: Table<Output>,
        private val lock: Any,
    ) : PhotoIndexSession<Output> {
        private val tableName = table.name

        override fun readOne(uri: Uri): IndexedPhoto<Output>? = access {
            db.rawQuery(
                "SELECT source_revision, pixel_revision, model, model_version, model_edge, output FROM $tableName WHERE uri = ?",
                arrayOf(uri.toString()),
            ).use { row ->
                if (row.moveToFirst()) IndexedPhoto(
                    row.getString(0), row.getString(1),
                    ModelRevision(row.getString(2), row.getString(3), row.getInt(4)), table.decode(row, 5),
                ) else null
            }
        }

        override fun current(accessible: Map<Uri, String?>, model: ModelRevision) =
            current(accessible, model, UnavailableSources.NONE)

        override fun current(accessible: Map<Uri, String?>, model: ModelRevision, unavailable: UnavailableSources): Set<Uri> = access {
            val stale = mutableListOf<Uri>()
            buildSet {
                db.rawQuery("SELECT uri, source_revision, model, model_version, model_edge, pixel_revision FROM $tableName", emptyArray<String>()).use { rows ->
                    while (rows.moveToNext()) {
                        val uri = Uri.parse(rows.getString(0))
                        val revision = accessible[uri]
                        if (uri !in accessible && uri !in unavailable ||
                            revision != null && revision != rows.getString(1) && rows.isNull(5)) stale += uri
                        if (revision != null && revision == rows.getString(1) &&
                            model == ModelRevision(rows.getString(2), rows.getString(3), rows.getInt(4))) add(uri)
                    }
                }
            }.also { if (stale.isNotEmpty()) db.transaction { stale.forEach(::delete) } }
        }

        override fun reconcile(accessible: Set<Uri>): Map<Uri, IndexedPhoto<Output>> = reconcile(accessible, accessible)

        override fun reconcile(accessible: Set<Uri>, read: Set<Uri>) = reconcile(accessible, read, UnavailableSources.NONE)

        override fun reconcile(accessible: Set<Uri>, read: Set<Uri>, unavailable: UnavailableSources): Map<Uri, IndexedPhoto<Output>> = access {
            val result = mutableMapOf<Uri, IndexedPhoto<Output>>()
            val gone = mutableListOf<Uri>()
            val available = mutableListOf<String>()
            db.rawQuery("SELECT uri FROM $tableName", emptyArray<String>()).use { rows ->
                while (rows.moveToNext()) {
                    val uri = Uri.parse(rows.getString(0))
                    if (uri !in accessible) {
                        if (uri !in unavailable) gone += uri
                    }
                    else if (uri in read) available += uri.toString()
                }
            }
            if (gone.isNotEmpty()) db.transaction { gone.forEach(::delete) }
            for (chunk in available.chunked(400)) {
                val placeholders = List(chunk.size) { "?" }.joinToString(",")
                db.rawQuery("SELECT uri, source_revision, pixel_revision, model, model_version, model_edge, output FROM $tableName WHERE uri IN ($placeholders)",
                    chunk.toTypedArray()).use { rows ->
                    while (rows.moveToNext()) {
                        result[Uri.parse(rows.getString(0))] = IndexedPhoto(
                            rows.getString(1), rows.getString(2), ModelRevision(rows.getString(3), rows.getString(4), rows.getInt(5)), table.decode(rows, 6))
                    }
                }
            }
            result
        }

        override fun save(uri: Uri, photo: IndexedPhoto<Output>) = access { write(uri, photo) }

        override fun saveBatch(records: List<Pair<Uri, IndexedPhoto<Output>>>) {
            if (records.isEmpty()) return
            access {
                db.transaction {
                    for ((uri, photo) in records) write(uri, photo)
                }
            }
        }

        private fun write(uri: Uri, photo: IndexedPhoto<Output>) {
            db.execSQL(
                "INSERT OR REPLACE INTO $tableName (uri, source_revision, pixel_revision, model, model_version, model_edge, output) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(uri.toString(), photo.sourceRevision, photo.pixelRevision, photo.model.name,
                    photo.model.version, photo.model.maxImageEdge, table.encode(photo.output)),
            )
        }

        override fun remove(uri: Uri) = access { delete(uri) }

        override fun remove(uri: Uri, expectedRevision: String?, expectedModel: ModelRevision): Boolean = access {
            db.compileStatement("""
                DELETE FROM $tableName WHERE uri = ? AND source_revision IS ?
                AND model = ? AND model_version = ? AND model_edge = ?
            """.trimIndent()).use {
                it.bindString(1, uri.toString())
                if (expectedRevision == null) it.bindNull(2) else it.bindString(2, expectedRevision)
                it.bindString(3, expectedModel.name)
                it.bindString(4, expectedModel.version)
                it.bindLong(5, expectedModel.maxImageEdge.toLong())
                it.executeUpdateDelete() == 1
            }
        }

        private fun delete(uri: Uri) {
            db.execSQL("DELETE FROM $tableName WHERE uri = ?", arrayOf(uri.toString()))
        }

        private fun <T> access(action: () -> T): T = synchronized(lock) { storageOperation(action) }

        override fun close() {
            try {
                access { db.close() }
            } finally {
                passphrase.fill(0)
            }
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 15
        val SUGGESTIONS = Table("suggested_regions", SuggestionRecordCodec::encode) { row, column ->
            SuggestionRecordCodec.decode(row.getBlob(column))
        }
        val TEXT = Table("photo_text", PhotoTextCodec::encode) { row, column -> PhotoTextCodec.decode(row.getString(column)) }
        val EMBEDDINGS = Table("image_embeddings", PhotoEmbedding::encodeForStorage) { row, column -> PhotoEmbedding.decode(row.getBlob(column)) }
        val IDENTITY = mapOf(
            SubjectKind.PERSON to identityTable("face_embeddings", SubjectKind.PERSON),
            SubjectKind.OBJECT to identityTable("object_embeddings", SubjectKind.OBJECT),
        )

        private fun identityTable(name: String, kind: SubjectKind) =
            Table(name, IdentityCacheCodec::encode) { row, column -> IdentityCacheCodec.decode(row.getBlob(column), kind) }
    }
}

internal inline fun <T> SQLiteDatabase.transaction(action: () -> T): T {
    beginTransaction()
    try {
        return action().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}

private inline fun <T> storageOperation(action: () -> T): T = try {
    action()
} catch (failure: SQLException) {
    throw PhotoIndexUnavailable(failure)
} catch (failure: GeneralSecurityException) {
    throw PhotoIndexUnavailable(failure)
} catch (failure: ProviderException) {
    throw PhotoIndexUnavailable(failure)
} catch (failure: IOException) {
    throw PhotoIndexUnavailable(failure)
} catch (failure: JSONException) {
    throw PhotoIndexUnavailable(failure)
} catch (failure: IllegalArgumentException) {
    throw PhotoIndexUnavailable(failure)
}
