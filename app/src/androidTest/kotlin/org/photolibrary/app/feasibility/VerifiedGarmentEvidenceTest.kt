package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.GarmentDependencies
import org.photolibrary.app.index.GarmentEvidenceStore
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.library.PhotoReferenceSource
import org.photolibrary.app.library.VerifiedGarmentEvidence

@RunWith(AndroidJUnit4::class)
class VerifiedGarmentEvidenceTest {
    @Test
    fun unversionedSourceCommitsRebindsAndRemovesResultsAfterLateAccessLoss() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "verified-garment-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val store = GarmentEvidenceStore(EncryptedPhotoIndex(directory, alias))
        val uri = Uri.parse("content://fixture/unversioned-photo")
        val model = ModelRevision("fixture", "one", 32)
        val dependencies = GarmentDependencies(model, model, model)
        var revision: String? = "pixels:first"
        var reads = 0
        var invalidate = false
        var cancelAfterCommit: Job? = null
        var replaceBeforeCancellation = false
        val source = object : PhotoReferenceSource {
            override suspend fun referenceRevision(uri: Uri): String? {
                reads++
                if (reads == 2) {
                    if (replaceBeforeCancellation) store.save(uri, checkNotNull(revision), dependencies, emptyList(), "replacement") { revision }
                    if (invalidate) revision = null
                    cancelAfterCommit?.cancel()
                }
                return revision
            }
            override suspend fun contentDigest(uri: Uri, expectedRevision: String): String? = error("Garment output uses input pixels")
        }
        val verified = VerifiedGarmentEvidence(source, store)
        try {
            assertTrue(verified.save(uri, "pixels:first", dependencies, emptyList(), "fingerprint"))
            revision = "pixels:second"
            reads = 0
            assertTrue(verified.rebind(uri, "pixels:second", dependencies, "fingerprint"))
            assertTrue(store.read(uri, "pixels:second", dependencies)?.isEmpty() == true)
            revision = "pixels:third"
            reads = 0
            invalidate = true
            assertFalse(verified.save(uri, "pixels:third", dependencies, emptyList(), "edited"))
            assertNull(store.pendingFingerprint(uri, dependencies))
            revision = "source:known"
            invalidate = false
            reads = 0
            cancelAfterCommit = null
            assertTrue(verified.save(uri, "source:known", dependencies, emptyList(), "known"))
            reads = 0
            replaceBeforeCancellation = true
            val older = launch(start = CoroutineStart.LAZY) {
                verified.save(uri, "source:known", dependencies, emptyList(), "old-write")
            }
            cancelAfterCommit = older
            older.start()
            older.join()
            assertTrue(older.isCancelled)
            org.junit.Assert.assertEquals("replacement", store.pendingFingerprint(uri, dependencies))
            revision = "pixels:cancel"
            replaceBeforeCancellation = false
            invalidate = false
            reads = 0
            val job = launch(start = CoroutineStart.LAZY) {
                verified.save(uri, "pixels:cancel", dependencies, emptyList(), "cancelled")
            }
            cancelAfterCommit = job
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            assertNull(store.pendingFingerprint(uri, dependencies))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
