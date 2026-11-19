package org.photolibrary.app.library

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

internal class UnavailableSources private constructor(private val trees: Set<Uri>, private val volumes: Set<String>) {
    operator fun contains(uri: Uri): Boolean {
        if (uri.authority == MediaStore.AUTHORITY) {
            return uri.pathSegments.firstOrNull() in volumes
        }
        if (trees.isEmpty() || !DocumentsContract.isTreeUri(uri)) return false
        val authority = uri.authority ?: return false
        val tree = DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getTreeDocumentId(uri))
        return tree in trees
    }

    companion object {
        val NONE = UnavailableSources(emptySet(), emptySet())

        fun from(folders: List<PhotoFolder>, volumes: Set<String> = emptySet()) = UnavailableSources(
            folders.filterNot { it.available }.mapTo(hashSetOf()) { it.uri },
            volumes.toSet(),
        )
    }
}
