package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.sourceContentDigest

@RunWith(AndroidJUnit4::class)
class PhotoContentFingerprintTest {
    private val uri = Uri.parse("content://fixture/content-proof")

    @Test
    fun hashesOriginalBytesAcrossMetadataChangesButRejectsConcurrentChanges() = runBlocking {
        var revision: String? = "first"
        var changedWhileReading = false
        var closed = false
        var bytes = byteArrayOf(4, 8, 15, 16, 23, 42)
        suspend fun digest(expected: String) = sourceContentDigest(uri, expected, { revision }) {
            object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (changedWhileReading) revision = null
                    return super.read(buffer, offset, length)
                }

                override fun close() { closed = true; super.close() }
            }
        }
        val original = digest("first")
        assertTrue(original?.startsWith("sha256-v1:") == true)
        assertTrue(closed)
        revision = "renamed"
        assertEquals(original, digest("renamed"))
        bytes = bytes + 7.toByte()
        assertNotEquals(original, digest("renamed"))
        changedWhileReading = true
        closed = false
        assertNull(digest("renamed"))
        assertTrue(closed)
        assertNull(sourceContentDigest(uri, "renamed", { revision }) { error("Revoked source opened") })
    }

    @Test
    fun cancellationAndReadFailureCloseStreamsWithoutReturningProof() = runBlocking {
        var closed = false
        var published = false
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            sourceContentDigest(uri, "first", { "first" }) {
                object : ByteArrayInputStream(ByteArray(128 * 1024)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        job.cancel()
                        return super.read(buffer, offset, length)
                    }

                    override fun close() { closed = true; super.close() }
                }
            }
            published = true
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(closed)
        assertEquals(false, published)
        closed = false
        assertNull(sourceContentDigest(uri, "first", { "first" }) {
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("fixture read failure")
                override fun close() { closed = true; super.close() }
            }
        })
        assertTrue(closed)
    }
}
