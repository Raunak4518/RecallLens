package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.detection.GarmentModels
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.GarmentEvidenceStore
import org.photolibrary.app.library.CachedGarmentAnalysis
import org.photolibrary.app.library.PhotoLibrary

@RunWith(AndroidJUnit4::class)
class GarmentSummaryRaceTest {
    @Test
    fun editingEarlierPhotoDuringLaterProgressCannotPublishItsCachedEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val resolver = context.contentResolver
        val alias = "garment-summary-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val owned = mutableListOf<Uri>()
        fun write(uri: Uri, color: Int) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
            val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(color)
                resolver.openOutputStream(uri, "wt")!!.use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        try {
            repeat(2) {
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "summary-" + UUID.randomUUID() + ".png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                owned += uri
                write(uri, Color.GREEN)
            }
            val library = PhotoLibrary(context)
            val storage = GarmentEvidenceStore(EncryptedPhotoIndex(directory, alias))
            val analysis = CachedGarmentAnalysis(library, storage, GarmentModels(context.assets), OnnxRegionModels(context.assets))
            val photos = library.list().photos.filter { it.uri in owned }.sortedBy { owned.indexOf(it.uri) }
            for (photo in photos) {
                val revision = checkNotNull(library.referenceRevision(photo.uri))
                assertTrue(storage.save(photo.uri, revision, analysis.dependencies, emptyList()) { revision })
            }
            val summary = analysis.scan(photos) { done, total ->
                if (done == total) write(photos.first().uri, Color.RED)
            }
            assertEquals(1, summary.cachedPhotos)
            assertEquals(0, summary.processedPhotos)
            assertEquals(1, summary.unavailablePhotos)
            assertEquals(setOf(photos.last().uri), summary.evidence.keys)
        } finally {
            owned.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
