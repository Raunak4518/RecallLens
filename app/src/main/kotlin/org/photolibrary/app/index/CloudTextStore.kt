package org.photolibrary.app.index

import android.net.Uri
import org.photolibrary.app.library.UnavailableSources
import org.photolibrary.app.privacy.ExportMode

internal data class CloudTextEvidence(
    val revision: String,
    val text: String,
    val worker: String,
    val artifact: String,
    val mode: ExportMode,
    val modelProvenance: String? = null,
)

internal class CloudTextStore(private val storage: EncryptedPhotoIndex) {
    fun references(): Set<Uri> = storage.withDatabase { db ->
        create(db)
        db.rawQuery("SELECT uri FROM cloud_text", emptyArray<String>()).use { rows ->
            buildSet { while (rows.moveToNext()) add(Uri.parse(rows.getString(0))) }
        }
    }
    fun save(uri: Uri, evidence: CloudTextEvidence, currentRevision: () -> String?): Boolean {
        require(evidence.text.length <= 16_384)
        require(evidence.worker.matches(Regex("[0-9a-f]{64}")))
        require(evidence.artifact.matches(Regex("[0-9a-f]{64}")))
        return storage.withDatabase { db ->
            create(db)
            db.transaction {
                if (currentRevision() != evidence.revision) return@transaction false
                db.execSQL("INSERT OR REPLACE INTO cloud_text (uri, revision, text, worker, artifact, mode, model_provenance) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(uri.toString(), evidence.revision, evidence.text, evidence.worker, evidence.artifact, evidence.mode.name, evidence.modelProvenance))
                if (currentRevision() == evidence.revision) true else {
                    db.execSQL("DELETE FROM cloud_text WHERE uri = ?", arrayOf(uri.toString()))
                    false
                }
            }
        }
    }

    fun reconcile(accessible: Map<Uri, String>, unavailable: UnavailableSources = UnavailableSources.NONE): Map<Uri, CloudTextEvidence> = storage.withDatabase { db ->
        create(db)
        db.transaction {
            val current = mutableMapOf<Uri, CloudTextEvidence>()
            val stale = mutableListOf<String>()
            db.rawQuery("SELECT uri, revision, text, worker, artifact, mode, model_provenance FROM cloud_text", emptyArray<String>()).use { rows ->
                while (rows.moveToNext()) {
                    val uri = Uri.parse(rows.getString(0))
                    if (accessible[uri] == rows.getString(1)) {
                        current[uri] = CloudTextEvidence(rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), ExportMode.valueOf(rows.getString(5)), rows.getString(6))
                    } else if (uri in accessible || uri !in unavailable) stale += uri.toString()
                }
            }
            stale.forEach { db.execSQL("DELETE FROM cloud_text WHERE uri = ?", arrayOf(it)) }
            current
        }
    }

    private fun create(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS cloud_text (
            uri TEXT PRIMARY KEY, revision TEXT NOT NULL, text TEXT NOT NULL, worker TEXT NOT NULL,
            artifact TEXT NOT NULL, mode TEXT NOT NULL
        ) WITHOUT ROWID""".trimIndent())
        val columns = db.rawQuery("PRAGMA table_info(cloud_text)", emptyArray<String>()).use { rows ->
            buildSet { while (rows.moveToNext()) add(rows.getString(1)) }
        }
        if ("model_provenance" !in columns) db.execSQL("ALTER TABLE cloud_text ADD COLUMN model_provenance TEXT")
    }
}
