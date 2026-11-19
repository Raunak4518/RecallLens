package org.photolibrary.app.feasibility

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.MainActivity
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.R
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.library.LibraryViewModel
import org.photolibrary.app.library.LibraryState
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.library.emptySearchMessage
import org.photolibrary.app.subjects.NamedSearch

@RunWith(AndroidJUnit4::class)
class SearchGuidanceTest {
    @Test
    fun emptySearchReflectsTextCoverageEvenWhenAllPhotosAreVisuallySearchable() {
        val photos = (1..2).map { SelectedPhoto(Uri.parse("content://fixture/photos/$it"), null, PhotoOrigin.GALLERY) }
        val visualOnly = LibraryState(photos = photos, indexedPhotos = photos, query = "Falvey Hall",
            loading = false, indexingTotal = photos.size, indexingChecked = 1, textIndexed = 1)

        assertEquals(R.string.library_wait_for_text, emptySearchMessage(visualOnly))
        assertEquals(R.string.library_index_paused, emptySearchMessage(visualOnly.copy(indexingPaused = true)))
        assertEquals(R.string.index_waiting, emptySearchMessage(visualOnly.copy(indexingWaiting = true)))
        val settled = visualOnly.copy(indexingChecked = photos.size)
        assertEquals(R.string.library_index_retry_needed, emptySearchMessage(settled.copy(indexingFailed = 1)))
        assertEquals(R.string.library_no_indexed_matches, emptySearchMessage(settled))
        assertEquals(R.string.library_no_matches, emptySearchMessage(settled.copy(textIndexed = photos.size)))
        assertEquals(R.string.library_no_matches, emptySearchMessage(visualOnly.copy(captureYearOnly = true)))
        assertEquals(R.string.library_no_matches, emptySearchMessage(visualOnly.copy(namedSearch = NamedSearch.Matches(emptySet()))))
    }

    @Test
    fun onePromptShowsOnlyIndexedPhotosAndTheirCount() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val wasPaused = app.indexing.progress.value.checkpoint.paused
        app.indexing.pause(true)
        app.indexScheduler.cancel()
        val inserted = mutableListOf<Uri>()
        var activity: Activity? = null
        val automation = instrumentation.uiAutomation
        fun node(text: String) = automation.rootInActiveWindow?.findGuidanceNode {
            it.isVisibleToUser && (it.text?.toString() == text || it.hintText?.toString() == text)
        }
        fun prompt() = automation.rootInActiveWindow?.findGuidanceNode { it.isEditable && it.isVisibleToUser }
        try {
            val query = "guidance-" + UUID.randomUUID().toString().replace("-", "")
            val indexedUri = insertFixture(app.contentResolver, "$query-ready.png", inserted)
            val unindexedUri = insertFixture(app.contentResolver, "$query-queued.png", inserted)
            val inputs = app.library.list().photos.map { IndexPhoto(it.uri, it.sourceRevision) }
            app.textIndex.refresh(inputs, { _, _ -> }, processUris = setOf(indexedUri)) { _, _ -> }
            val opened = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = opened
            lateinit var model: LibraryViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(opened)[LibraryViewModel::class.java] }
            await { !model.state.value.loading && !model.state.value.restoringIndex && indexedUri in model.state.value.indexedUris }
            val idle = model.state.value
            assertTrue(idle.photos.any { it.uri == unindexedUri })
            assertTrue(idle.visiblePhotos.any { it.uri == indexedUri })
            assertTrue(idle.visiblePhotos.none { it.uri == unindexedUri })
            await {
                val current = model.state.value
                node(app.getString(R.string.library_indexed_count, current.indexedPhotos.size, current.photos.size)) != null
            }
            await { node(app.getString(R.string.library_prompt_label)) != null }
            await { automation.rootInActiveWindow?.countGuidanceNodes {
                it.isVisibleToUser && it.isEditable
            } == 1 }
            assertTrue(listOf("Photo contents", "Text or filename", "Named").none { node(it) != null })
            val example = app.getString(R.string.library_prompt_example)
            if (node(example) == null) await { prompt()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
            await { node(example) != null }
            val screenshot = checkNotNull(automation.takeScreenshot())
            try {
                app.filesDir.resolve("search-guidance-single.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            val input = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
            await { prompt()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input) == true }
            await {
                val results = model.state.value
                results.query == query && !results.searching && indexedUri in results.resultGroups.rememberedText
            }
            assertTrue(model.state.value.visiblePhotos.none { it.uri == unindexedUri })
            await {
                val clear = node(app.getString(R.string.library_clear_search))
                (clear?.takeIf { it.isClickable } ?: clear?.parent)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            await {
                val current = model.state.value
                current.query.isEmpty() && indexedUri in current.visiblePhotos.map { it.uri } &&
                    current.visiblePhotos.none { it.uri == unindexedUri } && node(app.getString(R.string.library_clear_search)) == null
            }
            instrumentation.runOnMainSync { model.open(indexedUri) }
            val nameAction = app.getString(R.string.subject_open)
            await {
                val item = node(nameAction)
                if (item == null) {
                    automation.rootInActiveWindow?.findGuidanceNode { it.isVisibleToUser && it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    false
                } else (item.takeIf { it.isClickable } ?: item.parent)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            await { node(app.getString(R.string.subject_editor_title)) != null }
            instrumentation.runOnMainSync { model.dismissSubjects(); model.close() }
            await { prompt() != null && model.state.value.opened == null }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            inserted.forEach { app.contentResolver.delete(it, null, null) }
            app.indexing.pause(wasPaused)
        }
    }

    private fun insertFixture(resolver: ContentResolver, name: String, inserted: MutableList<Uri>): Uri {
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        inserted += uri
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.LTGRAY)
            resolver.openOutputStream(uri)!!.use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition()) {
            assertTrue("Search guidance workflow timed out", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }
}

private fun AccessibilityNodeInfo.findGuidanceNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (predicate(next)) return next
        for (index in 0 until next.childCount) next.getChild(index)?.let(pending::add)
    }
    return null
}

private fun AccessibilityNodeInfo.countGuidanceNodes(predicate: (AccessibilityNodeInfo) -> Boolean): Int {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    var count = 0
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (predicate(next)) count++
        for (index in 0 until next.childCount) next.getChild(index)?.let(pending::add)
    }
    return count
}
