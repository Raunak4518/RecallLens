package org.photolibrary.app.index

import android.net.Uri
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.photolibrary.app.library.UnavailableSources

internal data class PhotoCoordinates(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

internal data class CachedLocation(val revision: String, val coordinates: PhotoCoordinates?)

internal class LocationCache(private val index: EncryptedPhotoIndex) {
    fun reconcile(
        accessible: Map<Uri, String?>, unavailable: UnavailableSources = UnavailableSources.NONE,
    ): Map<Uri, CachedLocation> = index.withDatabase { db ->
        db.transaction {
            val current = mutableMapOf<Uri, CachedLocation>()
            val stale = mutableListOf<String>()
            db.rawQuery("SELECT uri, revision, latitude, longitude FROM photo_locations", emptyArray<String>()).use { rows ->
                while (rows.moveToNext()) {
                    val uri = Uri.parse(rows.getString(0))
                    val revision = rows.getString(1)
                    if (accessible[uri] == revision) {
                        val point = if (rows.isNull(2)) null else PhotoCoordinates(rows.getDouble(2), rows.getDouble(3))
                        current[uri] = CachedLocation(revision, point)
                    } else if (uri in accessible || uri !in unavailable) stale += uri.toString()
                }
            }
            stale.forEach { db.execSQL("DELETE FROM photo_locations WHERE uri = ?", arrayOf(it)) }
            current
        }
    }

    fun save(uri: Uri, entry: CachedLocation) = index.withDatabase { db ->
        db.execSQL("INSERT OR REPLACE INTO photo_locations VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(uri.toString(), entry.revision, entry.coordinates?.latitude, entry.coordinates?.longitude))
    }

    fun clear() = index.withDatabase { db -> db.execSQL("DELETE FROM photo_locations") }

    companion object {
        fun createTable(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS photo_locations (
                uri TEXT PRIMARY KEY, revision TEXT NOT NULL, latitude REAL, longitude REAL,
                CHECK ((latitude IS NULL AND longitude IS NULL) OR
                    (latitude IS NOT NULL AND longitude IS NOT NULL AND
                     latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
            ) WITHOUT ROWID""".trimIndent())
        }
    }
}
