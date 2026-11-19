package org.photolibrary.app.feasibility

import android.Manifest
import android.database.MatrixCursor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.library.GallerySource
import org.photolibrary.app.library.UnavailableSources

@RunWith(AndroidJUnit4::class)
class GalleryAvailabilityTest {
    @Test
    fun detachDuringListingHidesPhotosAndLimitedPermissionDoesNotRetainVolumes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val primary = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val removable = "1111-2222"
        var mounted = setOf(primary, removable)
        var disconnect = true
        var complete = true
        var providerFailure = false
        val source = GallerySource(context,
            attachedVolumes = { mounted },
            recentVolumes = { setOf(primary, removable) },
            queryPhotos = { projection ->
                if (providerFailure) throw IllegalArgumentException("Fixture volume detached")
                if (disconnect) mounted = setOf(primary)
                if (!complete) null else MatrixCursor(projection).apply {
                    addRow(arrayOf<Any?>(1L, "primary.jpg", primary, 10L, 100L, 200L, 1_672_531_200_000L))
                    addRow(arrayOf<Any?>(2L, "removable.jpg", removable, 11L, 100L, 200L, null))
                }
            },
            readVolumeVersion = { "fixture-version" },
        )
        val detached = source.snapshot(GalleryAccess.ALL)
        assertEquals(listOf("primary.jpg"), detached.photos.map { it.name })
        assertEquals(1_672_531_200_000L, detached.photos.single().capturedAtMillis)
        assertEquals(setOf(removable), detached.unavailableVolumes)
        val hidden = UnavailableSources.from(emptyList(), detached.unavailableVolumes)
        assertTrue(android.net.Uri.parse("content://media/1111-2222/images/media/2") in hidden)
        assertFalse(android.net.Uri.parse("content://media/external_primary/images/media/1") in hidden)
        assertFalse(android.net.Uri.parse("content://other/1111-2222/images/media/2") in hidden)
        assertTrue(source.snapshot(GalleryAccess.SELECTED).unavailableVolumes.isEmpty())
        assertTrue(source.snapshot(GalleryAccess.NONE).unavailableVolumes.isEmpty())
        disconnect = false
        mounted = setOf(primary, removable)
        val available = source.snapshot(GalleryAccess.ALL).photos
        assertEquals(2, available.size)
        assertEquals(null, available.single { it.name == "removable.jpg" }.capturedAtMillis)
        assertTrue(source.snapshot(GalleryAccess.ALL).unavailableVolumes.isEmpty())
        complete = false
        assertEquals(mounted, source.snapshot(GalleryAccess.ALL).unavailableVolumes)
        providerFailure = true
        assertEquals(mounted, source.snapshot(GalleryAccess.ALL).unavailableVolumes)
    }
}
