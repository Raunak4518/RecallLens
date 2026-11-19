package org.photolibrary.app.index

import android.net.Uri
import org.photolibrary.app.library.UnavailableSources

internal data class IndexPhoto(val uri: Uri, val sourceRevision: String? = null)

internal data class IndexedPhoto<Output>(
    val sourceRevision: String?,
    val pixelRevision: String?,
    val model: ModelRevision,
    val output: Output,
)

internal interface PhotoIndexSession<Output> : AutoCloseable {
    fun reconcile(accessible: Set<Uri>): Map<Uri, IndexedPhoto<Output>>
    fun reconcile(accessible: Set<Uri>, read: Set<Uri>): Map<Uri, IndexedPhoto<Output>> =
        reconcile(accessible).filterKeys { it in read }
    fun reconcile(accessible: Set<Uri>, read: Set<Uri>, unavailable: UnavailableSources): Map<Uri, IndexedPhoto<Output>> =
        reconcile(accessible, read)
    fun current(accessible: Map<Uri, String?>, model: ModelRevision): Set<Uri> =
        reconcile(accessible.keys).filter { (uri, record) ->
            accessible[uri] != null && record.sourceRevision == accessible[uri] && record.model == model
        }.keys
    fun current(accessible: Map<Uri, String?>, model: ModelRevision, unavailable: UnavailableSources): Set<Uri> =
        current(accessible, model)
    fun save(uri: Uri, photo: IndexedPhoto<Output>)
    fun remove(uri: Uri)
    fun remove(uri: Uri, expectedRevision: String?, expectedModel: ModelRevision): Boolean = false
}

internal class PhotoIndexUnavailable(cause: Throwable? = null) : Exception(cause)
