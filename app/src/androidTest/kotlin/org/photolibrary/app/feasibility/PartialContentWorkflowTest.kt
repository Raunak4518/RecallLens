package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
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
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection

@RunWith(AndroidJUnit4::class)
class PartialContentWorkflowTest {
    @Test
    fun onePromptCombinesSceneDateAndConfirmedNamesOnIndexedPhotos() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val priorPause = app.indexing.progress.value.checkpoint.paused
        app.indexing.pause(true)
        app.indexScheduler.cancel()
        val resolver = app.contentResolver
        val inserted = mutableListOf<Uri>()
        val name = "Astronaut " + UUID.randomUUID().toString().take(8)
        var activity: MainActivity? = null
        try {
            val fixtures = listOf("face-reference.png", "face-same.jpg", "face-lookalike.jpg", "face-lookalike.jpg")
            for ((position, file) in fixtures.withIndex()) {
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "partial-${UUID.randomUUID()}-$file")
                        put(MediaStore.Images.Media.MIME_TYPE, if (position != 0 && file.endsWith("png")) "image/png" else "image/jpeg")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                inserted += uri
                resolver.openOutputStream(uri)!!.use { output ->
                    if (position != 0) instrumentation.context.assets.open("identity/$file").use { it.copyTo(output) }
                    else {
                        val dated = java.io.File.createTempFile("dated-search-", ".jpg", app.cacheDir)
                        try {
                            val bitmap = instrumentation.context.assets.open("identity/$file").use(BitmapFactory::decodeStream)
                            try {
                                dated.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                            } finally { bitmap.recycle() }
                            ExifInterface(dated.path).apply {
                                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2025:10:20 12:00:00")
                                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                                saveAttributes()
                            }
                            dated.inputStream().use { it.copyTo(output) }
                        } finally { dated.delete() }
                    }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            val photos = app.library.list().photos
            val anchor = photos.single { it.uri == inserted.first() }
            val unindexed = inserted.last()
            val indexed = inserted.dropLast(1).toSet()
            val unindexedName = checkNotNull(photos.single { it.uri == unindexed }.name)
            assertEquals(Instant.parse("2025-10-20T12:00:00Z").toEpochMilli(), anchor.capturedAtMillis)
            assertEquals(Confirmation.SAVED, app.photoSubjects.confirm(anchor,
                SubjectSelection.New(name, SubjectKind.PERSON, emptyList()), null))
            app.visualIndex.refresh(photos.map { IndexPhoto(it.uri, it.sourceRevision) }, { _, _ -> },
                processUris = indexed) { _, _ -> }
            val opened = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = opened
            lateinit var model: LibraryViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(opened)[LibraryViewModel::class.java] }
            await { !model.state.value.loading && !model.state.value.restoringIndex &&
                model.state.value.indexedUris.containsAll(indexed) && model.state.value.named.subjects.any { it.name == name } }
            val automation = instrumentation.uiAutomation
            fun clickText(text: String) {
                await { automation.rootInActiveWindow?.findPartialNode { it.text?.toString() == text }
                    ?.let { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        it.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } == true }
            }
            fun click(resource: Int) = clickText(app.getString(resource))
            fun search(query: String) {
                val input = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
                await { automation.rootInActiveWindow?.findPartialNode { it.isEditable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input) == true }
            }
            val initial = model.state.value
            assertTrue(unindexed in initial.photos.map { it.uri })
            assertTrue(unindexed !in initial.indexedUris)
            assertEquals(initial.indexedPhotos, initial.visiblePhotos)
            await {
                val current = model.state.value
                val count = app.getString(R.string.library_indexed_count, current.indexedPhotos.size, current.photos.size)
                automation.rootInActiveWindow?.findPartialNode { it.text?.toString() == count } != null
            }
            assertTrue(automation.rootInActiveWindow?.findPartialNode { it.text?.toString() == unindexedName } == null)
            val query = "$name behind a red car"
            search(query)
            await { model.state.value.query == query && !model.state.value.searching &&
                model.state.value.resultGroups.related == listOf(anchor.uri) }
            assertTrue(QueryRequirement.SPATIAL_RELATION in model.state.value.queryRequirements)
            assertTrue(model.state.value.resultGroups.confirmed.isEmpty())
            assertEquals(listOf(anchor.uri), model.state.value.visiblePhotos.map { it.uri })
            assertTrue(model.state.value.visualIndexed <= model.state.value.photos.size)
            assertTrue(model.state.value.textIndexed <= model.state.value.photos.size)
            val label = app.getString(R.string.results_partial)
            await { automation.rootInActiveWindow?.findPartialNode { it.text?.toString() == label } != null }
            val screenshot = checkNotNull(automation.takeScreenshot())
            try {
                app.filesDir.resolve("partial-content-search.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            search("a dog behind a car")
            await { model.state.value.query == "a dog behind a car" && !model.state.value.searching &&
                QueryRequirement.SPATIAL_RELATION in model.state.value.queryRequirements }
            await { automation.rootInActiveWindow?.findPartialNode { it.text?.toString() == label } != null }
            search(name)
            await { model.state.value.resultGroups.confirmed == listOf(anchor.uri) }
            assertTrue(model.state.value.resultGroups.related.isEmpty())
            assertTrue(inserted.drop(1).none { candidate -> model.state.value.visiblePhotos.any { it.uri == candidate } })
            search("$name after 2025")
            await { model.state.value.captureYearFilter?.year == 2025 && model.state.value.visiblePhotos.isEmpty() }
            search("$name in 2025")
            await { model.state.value.resultGroups.confirmed == listOf(anchor.uri) }
            clickText(checkNotNull(anchor.name))
            click(R.string.library_find_similar)
            await { model.state.value.visualSeed == anchor.uri && !model.state.value.searching &&
                model.state.value.resultGroups.related.containsAll(indexed - anchor.uri) }
            assertTrue(anchor.uri !in model.state.value.resultGroups.related)
            assertTrue(unindexed !in model.state.value.resultGroups.related)
            assertTrue(model.state.value.resultGroups.confirmed.isEmpty())
            await { automation.rootInActiveWindow?.findPartialNode {
                it.text?.toString() == app.getString(R.string.library_similar_title, anchor.name)
            } != null }
            click(R.string.library_similar_clear)
            await { model.state.value.visualSeed == null && model.state.value.query.isEmpty() }
            search(unindexedName)
            await { model.state.value.query == unindexedName && !model.state.value.searching }
            assertTrue(unindexed !in model.state.value.resultGroups.rememberedText)
            assertTrue(model.state.value.visiblePhotos.none { it.uri == unindexed })
            search("Diwali pics 2025")
            await { model.state.value.eventQuery?.year == 2025 && !model.state.value.searching &&
                anchor.uri in model.state.value.resultGroups.nearEventDate && model.state.value.resultGroups.related.isNotEmpty() }
            assertTrue(model.state.value.resultGroups.confirmed.isEmpty())
            await { automation.rootInActiveWindow?.findPartialNode {
                it.text?.toString() == app.getString(R.string.library_event_search_dates, "Diwali")
            } != null }
        } finally {
            activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
            inserted.forEach { resolver.delete(it, null, null) }
            val remaining = app.library.list().photos
            app.photoSubjects.refresh(remaining)
            app.visualIndex.currentPhotos(remaining.map { IndexPhoto(it.uri, it.sourceRevision) })
            app.indexing.pause(priorPause)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition()) {
            assertTrue("Partial content workflow timed out", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }
}

private fun AccessibilityNodeInfo.findPartialNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (predicate(next)) return next
        for (index in 0 until next.childCount) next.getChild(index)?.let(pending::add)
    }
    return null
}
