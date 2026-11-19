package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.CloudTextEvidence
import org.photolibrary.app.index.CloudTextStore
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.privacy.ExportMode

@RunWith(AndroidJUnit4::class)
class CloudTextStoreTest {
    @Test
    fun persistsPrivateProvenanceRejectsLateResultAndPurgesRevokedText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "cloud-text-${UUID.randomUUID()}"
        val directory = context.noBackupFilesDir.resolve(alias)
        val uri = Uri.parse("content://fixture/cloud")
        fun store() = CloudTextStore(EncryptedPhotoIndex(directory, alias))
        val evidence = CloudTextEvidence("source:one", "CLOUD PRIVATE MARKER 8517", "a".repeat(64), "b".repeat(64), ExportMode.CLOUD_REDACTED)
        try {
            assertTrue(store().save(uri, evidence) { "source:one" })
            assertEquals(evidence, store().reconcile(mapOf(uri to "source:one"))[uri])
            assertFalse(directory.resolve("catalog.db").readBytes().toString(Charsets.ISO_8859_1).contains(evidence.text))
            var calls = 0
            assertFalse(store().save(uri, evidence) { if (++calls == 1) "source:one" else "source:two" })
            assertTrue(store().reconcile(mapOf(uri to "source:two")).isEmpty())
            assertTrue(store().save(uri, evidence) { "source:one" })
            assertTrue(store().reconcile(emptyMap()).isEmpty())
            assertTrue(store().references().isEmpty())
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
