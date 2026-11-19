package org.photolibrary.app.library

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PhotoFolder(val uri: Uri, val name: String, val available: Boolean = true)

internal class FolderSource(private val queryDocuments: (Uri, Array<String>) -> Cursor?) {
    constructor(resolver: ContentResolver) : this({ uri, projection -> resolver.query(uri, projection, null, null, null) })
    fun folder(uri: Uri): PhotoFolder {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, id)
        val name = query(document, arrayOf(Document.COLUMN_DISPLAY_NAME)) { if (it.moveToFirst()) it.getString(0) else null }
        return PhotoFolder(uri, name ?: id.substringAfterLast('/').substringAfterLast(':'))
    }

    suspend fun photos(tree: Uri): List<SelectedPhoto>? = try {
        enumerate(tree)
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: android.os.OperationCanceledException) {
        currentCoroutineContext().ensureActive()
        null
    }

    private suspend fun enumerate(tree: Uri): List<SelectedPhoto>? {
        val coroutine = currentCoroutineContext()
        val pending = ArrayDeque<String>()
        val visited = HashSet<String>()
        val photos = mutableListOf<SelectedPhoto>()
        pending.add(DocumentsContract.getTreeDocumentId(tree))
        while (pending.isNotEmpty()) {
            coroutine.ensureActive()
            val parent = pending.removeLast()
            if (!visited.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val complete = queryDocuments(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE))?.use { cursor ->
                if (!cursor.isComplete()) return@use false
                while (cursor.moveToNext()) {
                    coroutine.ensureActive()
                    val id = cursor.getString(0)
                    val mime = cursor.getString(2)
                    if (mime == Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else if (mime?.startsWith("image/") == true) {
                        photos.add(SelectedPhoto(DocumentsContract.buildDocumentUriUsingTree(tree, id), cursor.getString(1),
                            PhotoOrigin.FOLDER, revision(cursor, 3, 4), captureDateHint =
                                if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it > 0 }
                                    ?.let { PhotoDateHint(it, PhotoDateHintSource.FILE_MODIFIED) }))
                    }
                }
                true
            }
            if (complete != true) return null
        }
        return photos
    }

    fun revision(uri: Uri): String? = query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)) {
        if (it.isComplete() && it.moveToFirst()) revision(it, 0, 1) else null
    }

    private fun Cursor.isComplete(): Boolean =
        !extras.getBoolean(DocumentsContract.EXTRA_LOADING) && extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()

    private fun revision(cursor: Cursor, modified: Int, size: Int): String? {
        if (cursor.isNull(modified) || cursor.isNull(size)) return null
        val time = cursor.getLong(modified)
        val bytes = cursor.getLong(size)
        return if (time > 0 && bytes > 0) "document:$time:$bytes" else null
    }

    private fun <T> query(uri: Uri, projection: Array<String>, read: (Cursor) -> T): T? = try {
        queryDocuments(uri, projection)?.use(read)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
