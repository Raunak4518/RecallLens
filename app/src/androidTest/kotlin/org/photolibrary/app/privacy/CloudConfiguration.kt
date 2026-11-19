package org.photolibrary.app.privacy

import android.util.Base64
import java.net.URI
import org.photolibrary.app.BuildConfig
import org.photolibrary.app.index.EncryptedPhotoIndex

internal class CloudConnection private constructor(
    val url: String,
    val token: String,
    val publicKey: String,
    val localDevelopment: Boolean,
    val worker: WorkerKey,
) {
    companion object {
        fun create(url: String, token: String, publicKey: String, localDevelopment: Boolean): CloudConnection {
            val uri = URI(url.trim())
            require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.host != null)
            require(uri.port == -1 || uri.port in 1..65535)
            val local = BuildConfig.DEBUG && localDevelopment && uri.scheme == "http" &&
                uri.host == "10.0.2.2"
            require(uri.scheme == "https" || local)
            require(token.length in 32..512 && token.all { it.code in 33..126 })
            val raw = Base64.decode(publicKey.trim(), Base64.DEFAULT)
            require(raw.size == 32)
            val normalizedKey = Base64.encodeToString(raw, Base64.NO_WRAP)
            return CloudConnection(uri.toASCIIString().trimEnd('/'), token, normalizedKey, local,
                WorkerKey(sha256(raw), raw))
        }
    }
}

internal class CloudConfiguration(private val storage: EncryptedPhotoIndex) {
    fun load(): CloudConnection? = storage.withDatabase { db ->
        create(db)
        db.rawQuery("SELECT url, token, public_key, local_development FROM cloud_configuration WHERE id = 1", emptyArray<String>()).use {
            if (!it.moveToFirst()) null else CloudConnection.create(it.getString(0), it.getString(1), it.getString(2), it.getInt(3) == 1)
        }
    }

    fun save(connection: CloudConnection) = storage.withDatabase { db ->
        create(db)
        db.execSQL("INSERT OR REPLACE INTO cloud_configuration VALUES (1, ?, ?, ?, ?)",
            arrayOf<Any?>(connection.url, connection.token, connection.publicKey, if (connection.localDevelopment) 1 else 0))
    }

    fun clear() = storage.withDatabase { db ->
        create(db)
        db.execSQL("DELETE FROM cloud_configuration")
    }

    private fun create(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_configuration "
            + "(id INTEGER PRIMARY KEY CHECK(id = 1), url TEXT NOT NULL, token TEXT NOT NULL, public_key TEXT NOT NULL, local_development INTEGER NOT NULL)")
    }
}
