package org.photolibrary.app.library

import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class PhotoEvidenceValidation(
    private val galleryAccess: () -> GalleryAccess,
    private val galleryRevisions: suspend () -> Map<Uri, String?>,
    private val referenceRevision: suspend (Uri) -> String?,
) {
    constructor(library: PhotoLibrary) : this(library::galleryAccess, library::galleryRevisions, library::referenceRevision)

    suspend fun current(photos: List<SelectedPhoto>, expected: Map<Uri, String>): Set<Uri> {
        currentCoroutineContext().ensureActive()
        val access = galleryAccess()
        val gallery = if (photos.any { it.uri in expected && it.origin == PhotoOrigin.GALLERY }) galleryRevisions() else emptyMap()
        val verified = buildSet {
            for (photo in photos) {
                val revision = expected[photo.uri] ?: continue
                currentCoroutineContext().ensureActive()
                val actual = if (photo.origin == PhotoOrigin.GALLERY) {
                    if (access == GalleryAccess.NONE || photo.uri !in gallery) null
                    else gallery[photo.uri]?.let { "source:$it" } ?: referenceRevision(photo.uri)
                } else referenceRevision(photo.uri)
                if (actual == revision) add(photo.uri)
            }
        }
        currentCoroutineContext().ensureActive()
        return if (galleryAccess() == access) verified else emptySet()
    }
}
