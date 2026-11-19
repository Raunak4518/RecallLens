package org.photolibrary.app.library

import android.net.Uri
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.DatedSubjectAlias
import org.photolibrary.app.subjects.IdentityRejection
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.SubjectGroupChange
import org.photolibrary.app.subjects.SubjectGroupEdit
import org.photolibrary.app.subjects.SubjectGroupResult

internal class PhotoSubjects(private val library: PhotoReferenceSource, private val store: NamedSubjectStore) {
    suspend fun refresh(photos: List<SelectedPhoto>, unavailable: UnavailableSources = UnavailableSources.NONE): NamedLibrary {
        val referenced = store.revisions()
        val proofs = store.contentProofs()
        val accessible = buildMap {
            for (photo in photos) {
                if (photo.uri !in referenced) continue
                val revision = library.referenceRevision(photo.uri)
                if (revision != null) {
                    val previous = proofs[photo.uri]
                    if (previous != null && (previous.revision != revision || !revision.startsWith("source:"))) {
                        val digest = library.contentDigest(photo.uri, revision)
                        if (digest == null || digest != previous.digest) continue
                        if (previous.revision != revision) store.rebindUnchanged(photo.uri, previous, revision, digest)
                        if (library.referenceRevision(photo.uri) != revision) continue
                    }
                    put(photo.uri, revision)
                }
            }
        }
        store.pruneIdentityRejections(photos, unavailable)
        return store.reconcile(accessible, unavailable)
    }

    suspend fun confirm(
        photo: SelectedPhoto,
        choice: SubjectSelection,
        region: PhotoRegion?,
        requiredRevision: String? = null,
    ): Confirmation {
        val current = library.referenceRevision(photo.uri) ?: return Confirmation.PHOTO_CHANGED
        if (requiredRevision != null && current != requiredRevision) return Confirmation.PHOTO_CHANGED
        if (photo.sourceRevision != null && current != "source:${photo.sourceRevision}") return Confirmation.PHOTO_CHANGED
        val digest = library.contentDigest(photo.uri, current) ?: return Confirmation.PHOTO_CHANGED
        return store.confirm(photo.uri, current, choice, region, digest)
    }

    suspend fun editGroups(current: NamedLibrary, sourceId: Long, change: SubjectGroupChange): SubjectGroupResult {
        val groupIds = when (change) {
            is SubjectGroupChange.Split -> setOf(sourceId)
            is SubjectGroupChange.Merge -> setOf(sourceId, change.destination)
        }
        for (reference in current.references.filter { it.subjectId in groupIds }.distinctBy { it.uri }) {
            if (library.referenceRevision(reference.uri) != reference.revision) return SubjectGroupResult.Rejected.CHANGED
        }
        return store.editGroups(current, sourceId, change)
    }

    suspend fun undoGroupEdit(edit: SubjectGroupEdit): Boolean {
        val prior = edit.before.values.filterNotNull().flatMap { it.references }
        for (reference in prior.distinctBy { it.uri }) {
            if (library.referenceRevision(reference.uri) != reference.revision) return false
        }
        return store.undoGroupEdit(edit)
    }

    fun identityRejections(subjectId: Long) = store.identityRejections(subjectId)

    suspend fun rejectIdentity(subjectId: Long, rejection: IdentityRejection): Boolean =
        library.referenceRevision(rejection.uri) == rejection.revision && store.rejectIdentity(subjectId, rejection)

    fun undoIdentityRejection(subjectId: Long, rejection: IdentityRejection) = store.undoIdentityRejection(subjectId, rejection)

    fun removeDatedAlias(alias: DatedSubjectAlias) = store.removeDatedAlias(alias)

    fun remove(uri: Uri, subjectId: Long) = store.remove(uri, subjectId)

    fun setSelf(subjectId: Long?) = store.setSelf(subjectId)

}
