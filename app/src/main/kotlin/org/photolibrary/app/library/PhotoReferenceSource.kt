package org.photolibrary.app.library

import android.net.Uri

internal interface PhotoReferenceSource {
    suspend fun referenceRevision(uri: Uri): String?

    suspend fun contentDigest(uri: Uri, expectedRevision: String): String?
}
