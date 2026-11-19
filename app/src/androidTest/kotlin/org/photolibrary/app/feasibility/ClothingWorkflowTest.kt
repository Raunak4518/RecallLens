package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.MainActivity
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.R
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.GarmentEvidenceStore
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.library.LibraryViewModel
import org.photolibrary.app.library.decodePhoto
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection

@RunWith(AndroidJUnit4::class)
class ClothingWorkflowTest {
    @Test
    fun realConfirmedPersonClothingAnalysisAndNamedQueryReuseEncryptedEvidence() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("clothingWorkflow") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val source = File(app.cacheDir, "clothing-workflow-" + UUID.randomUUID() + ".jpg")
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "clothing-workflow-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        val stores = mutableListOf<ViewModelStore>()
        val previousPause = app.indexing.progress.value.checkpoint.paused
        val name = "Person ${UUID.randomUUID()}"
        try {
            instrumentation.context.assets.open("selective-ocr/444183da0584478c.jpg").use { input ->
                source.outputStream().use { target -> input.copyTo(target) }
            }
            resolver.openOutputStream(uri)!!.use { target -> source.inputStream().use { it.copyTo(target) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            app.indexing.pause(true)
            app.indexScheduler.cancel()
            val photo = app.library.list().photos.single { it.uri == uri }
            val bitmap = decodePhoto(ImageDecoder.createSource(source), 1600, software = true)
            val region = try {
                app.regionModels.open().use { it.detect(bitmap, SubjectKind.PERSON).single().region }
            } finally {
                bitmap.recycle()
            }
            assertEquals(Confirmation.SAVED, app.photoSubjects.confirm(photo,
                SubjectSelection.New(name, SubjectKind.PERSON, emptyList()), region))
            suspend fun indexFixture() {
                val inputs = app.library.list().photos.map { IndexPhoto(it.uri, it.sourceRevision) }
                app.textIndex.refresh(inputs, { _, _ -> }, processUris = setOf(uri)) { _, _ -> }
            }
            fun viewModel(): LibraryViewModel {
                lateinit var result: LibraryViewModel
                instrumentation.runOnMainSync {
                    val store = ViewModelStore().also(stores::add)
                    result = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[LibraryViewModel::class.java]
                    result.refresh()
                }
                await { !result.state.value.loading && uri in result.state.value.indexedUris &&
                    result.state.value.named.subjects.any { it.name == name } }
                return result
            }
            indexFixture()
            val model = viewModel()
            instrumentation.runOnMainSync { model.analyzeClothing() }
            await { !model.state.value.clothingAnalyzing && model.state.value.clothingTotal > 0 }
            val revision = checkNotNull(app.library.referenceRevision(uri))
            val reopened = GarmentEvidenceStore(EncryptedPhotoIndex(app.noBackupFilesDir.resolve("photo-text-index"), "photo-library.ocr-wrap.v1"))
            assertTrue(reopened.read(uri, revision, app.garmentAnalysis.dependencies).orEmpty().isNotEmpty())
            val repeated = app.garmentAnalysis.scan(listOf(photo)) { _, _ -> }
            assertEquals(1, repeated.cachedPhotos)
            assertEquals(0, repeated.processedPhotos)
            for (change in listOf(
                ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "renamed-" + UUID.randomUUID() + ".jpg") },
                ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Clothing-" + UUID.randomUUID() + "/") },
            )) {
                assertEquals(1, resolver.update(uri, change, null, null))
                val current = app.library.list().photos.single { it.uri == uri }
                val named = app.photoSubjects.refresh(listOf(current))
                assertTrue(named.references.any { it.uri == uri && it.region == region })
                val revisions = mapOf(uri to checkNotNull(app.library.referenceRevision(uri)))
                reopened.reconcile(revisions)
                assertTrue(reopened.read(uri, revisions.getValue(uri), app.garmentAnalysis.dependencies) == null)
                val reused = app.garmentAnalysis.scan(listOf(current)) { _, _ -> }
                assertEquals(1, reused.cachedPhotos)
                assertEquals(0, reused.processedPhotos)
                assertEquals(0, reused.unavailablePhotos)
                assertTrue(reopened.read(uri, revisions.getValue(uri), app.garmentAnalysis.dependencies).orEmpty().isNotEmpty())
            }
            val stale = app.garmentAnalysis.scan(listOf(photo)) { _, _ -> }
            assertEquals(1, stale.unavailablePhotos)
            assertEquals(0, stale.processedPhotos)
            indexFixture()
            instrumentation.runOnMainSync { model.refresh() }
            await { !model.state.value.loading && uri in model.state.value.indexedUris }
            for (controller in listOf(model, viewModel())) {
                instrumentation.runOnMainSync { controller.search("$name wearing black") }
                await { controller.state.value.resultGroups.estimatedColor == listOf(uri) }
                assertEquals(listOf(uri), controller.state.value.resultGroups.estimatedColor)
                assertTrue(controller.state.value.resultGroups.confirmed.isEmpty())
                assertTrue(controller.state.value.resultGroups.related.isEmpty())
                instrumentation.runOnMainSync { controller.search("$name wearing red") }
                await { controller.state.value.namedSearch is NamedSearch.Matches && !controller.state.value.searching }
                assertTrue(controller.state.value.resultGroups.estimatedColor.isEmpty())
                assertTrue(controller.state.value.resultGroups.confirmed.isEmpty())
            }
            val activity = instrumentation.startActivitySync(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            try {
                val automation = instrumentation.uiAutomation
                val prompt = app.getString(R.string.library_prompt_label)
                await {
                    automation.rootInActiveWindow?.firstNode { it.text?.toString() == prompt ||
                        it.hintText?.toString() == prompt } != null
                }
                val search = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$name wearing black")
                }
                await {
                    automation.rootInActiveWindow?.firstNode { it.isEditable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, search) == true
                }
                val estimate = app.getString(R.string.results_estimated_color)
                await { automation.rootInActiveWindow?.firstNode { it.text?.toString() == estimate } != null }
                val confirmed = app.getString(R.string.results_confirmed)
                assertTrue(automation.rootInActiveWindow?.firstNode { it.text?.toString() == confirmed } == null)
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
            val edited = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                edited.eraseColor(android.graphics.Color.RED)
                resolver.openOutputStream(uri, "wt")!!.use {
                    assertTrue(edited.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it))
                }
            } finally { edited.recycle() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val changed = app.library.list().photos.single { it.uri == uri }
            val withoutStaleName = app.photoSubjects.refresh(listOf(changed))
            assertTrue(withoutStaleName.references.none { it.uri == uri })
            val refreshed = app.garmentAnalysis.scan(listOf(changed)) { _, _ -> }
            assertEquals(0, refreshed.cachedPhotos)
            assertEquals(1, refreshed.processedPhotos)
            assertTrue(refreshed.evidence.getValue(uri).isEmpty())
        } finally {
            source.delete()
            instrumentation.runOnMainSync { stores.forEach(ViewModelStore::clear) }
            resolver.delete(uri, null, null)
            app.photoSubjects.refresh(app.library.list().photos)
            app.garmentEvidence.reconcile(app.library.list().photos.mapNotNull { remaining ->
                remaining.sourceRevision?.let { remaining.uri to "source:$it" }
            }.toMap())
            app.indexing.pause(previousPause)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition()) {
            assertTrue("Clothing workflow timed out", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }
}

private fun AccessibilityNodeInfo.firstNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (predicate(next)) return next
        for (index in 0 until next.childCount) next.getChild(index)?.let(pending::add)
    }
    return null
}
