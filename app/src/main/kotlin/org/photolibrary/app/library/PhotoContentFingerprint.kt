package org.photolibrary.app.library

import android.net.Uri
import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun sourceContentDigest(
    uri: Uri,
    expectedRevision: String,
    readRevision: suspend (Uri) -> String?,
    openSource: (Uri) -> InputStream?,
): String? {
    if (readRevision(uri) != expectedRevision) return null
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        openSource(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        } ?: return null
        currentCoroutineContext().ensureActive()
        if (readRevision(uri) != expectedRevision) null
        else "sha256-v1:" + Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
