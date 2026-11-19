package org.photolibrary.app.semantic

import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoEvidenceUpgrade
import org.photolibrary.app.index.PhotoProcessor

internal interface ImageTextEncoder {
    val revision: ModelRevision
    val available: Boolean
    val evidenceUpgrade: PhotoEvidenceUpgrade<PhotoEmbedding>?

    fun encodeQuery(query: String): PhotoEmbedding

    fun prepareTextSearch()

    fun releaseTextSession()

    fun imageProcessor(): PhotoProcessor<PhotoEmbedding>
}
