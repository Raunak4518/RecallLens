package org.photolibrary.app.library

import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.photolibrary.app.detection.PersonGarmentEvidence
import org.photolibrary.app.index.GarmentDependencies
import org.photolibrary.app.index.GarmentEvidenceStore

internal class VerifiedGarmentEvidence(
    private val source: PhotoReferenceSource,
    private val store: GarmentEvidenceStore,
) {
    suspend fun save(
        uri: Uri, revision: String, dependencies: GarmentDependencies,
        evidence: List<PersonGarmentEvidence>, fingerprint: String,
    ): Boolean = commit(uri, revision) { token ->
        store.save(uri, revision, dependencies, evidence, fingerprint, token) { revision }
    }

    suspend fun rebind(uri: Uri, revision: String, dependencies: GarmentDependencies, fingerprint: String): Boolean =
        commit(uri, revision) { token -> store.rebind(uri, revision, dependencies, fingerprint, token) { revision } }

    private suspend fun commit(uri: Uri, revision: String, action: (String) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (source.referenceRevision(uri) != revision) return false
        currentCoroutineContext().ensureActive()
        val token = java.util.UUID.randomUUID().toString()
        val written = action(token)
        var valid = false
        try {
            currentCoroutineContext().ensureActive()
            val unchanged = source.referenceRevision(uri) == revision
            currentCoroutineContext().ensureActive()
            valid = unchanged
            return written && valid
        } finally {
            if (written && !valid) store.removeWrite(uri, token)
        }
    }
}
