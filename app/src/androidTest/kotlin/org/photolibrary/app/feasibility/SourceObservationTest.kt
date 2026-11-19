package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.library.LibraryViewModel

@RunWith(AndroidJUnit4::class)
class SourceObservationTest {
    @Test
    fun activeViewModelTracksInsertRenameTrashRestoreAndDelete() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val previousPause = app.indexing.progress.value.checkpoint.paused
        app.indexing.pause(true)
        app.indexScheduler.cancel()
        val resolver = app.contentResolver
        val store = ViewModelStore()
        lateinit var model: LibraryViewModel
        var uri: Uri? = null
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[LibraryViewModel::class.java]
                model.startObserving()
                model.refresh()
            }
            await { !model.state.value.loading }
            val name = "observed-" + UUID.randomUUID() + ".png"
            uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
            val photo = uri
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.GREEN)
                resolver.openOutputStream(photo)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            resolver.update(photo, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            await { !model.state.value.loading && model.state.value.photos.any { it.uri == photo } }
            fun digest() = resolver.openInputStream(photo)!!.use { input ->
                MessageDigest.getInstance("SHA-256").digest(input.readBytes()).toList()
            }
            val original = digest()
            val renamed = "renamed-" + name
            resolver.update(photo, ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, renamed) }, null, null)
            await { !model.state.value.loading && model.state.value.photos.any { it.uri == photo && it.name == renamed } }
            resolver.update(photo, ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 1) }, null, null)
            await { !model.state.value.loading && model.state.value.photos.none { it.uri == photo } }
            resolver.update(photo, ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 0) }, null, null)
            await { !model.state.value.loading && model.state.value.photos.any { it.uri == photo } }
            assertEquals(original, digest())
            resolver.delete(photo, null, null)
            uri = null
            await { !model.state.value.loading && model.state.value.photos.none { it.uri == photo } }
            instrumentation.runOnMainSync { model.stopObserving() }
            val revision = model.state.value.imageRevision
            resolver.notifyChange(MediaStore.AUTHORITY_URI, null)
            SystemClock.sleep(800)
            assertEquals(revision, model.state.value.imageRevision)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            uri?.let { resolver.delete(it, null, null) }
            app.indexing.pause(previousPause)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition()) {
            assertTrue("Observed source change timed out", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }
}
