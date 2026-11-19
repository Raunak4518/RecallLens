package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.content.Intent
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
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.library.LibraryViewModel
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.SelfIdentity
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection

@RunWith(AndroidJUnit4::class)
class SelfIdentityWorkflowTest {
    @Test
    fun chooseReplaceAndClearSelfThroughDialogPreservesConfirmedPhotoLinks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val storage = EncryptedPhotoIndex(app.noBackupFilesDir.resolve("photo-text-index"), "photo-library.ocr-wrap.v1")
        val previous = storage.withDatabase { db ->
            db.rawQuery("SELECT subject_id FROM self_identity WHERE id = 1", emptyArray<String>()).use {
                if (it.moveToFirst()) SelfIdentity(if (it.isNull(0)) null else it.getLong(0)) else null
            }
        }
        val priorPause = app.indexing.progress.value.checkpoint.paused
        app.indexing.pause(true)
        app.indexScheduler.cancel()
        val original = app.photoSubjects.refresh(app.library.list().photos)
        val resolver = app.contentResolver
        val inserted = mutableListOf<Uri>()
        val subjectIds = mutableListOf<Long>()
        val suffix = UUID.randomUUID().toString().take(8)
        val names = listOf("001 Self Person $suffix", "002 Self Person $suffix")
        var activity: MainActivity? = null
        try {
            for (asset in listOf("face-reference.png", "face-lookalike.jpg")) {
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "self-" + UUID.randomUUID() + "-" + asset)
                        put(MediaStore.Images.Media.MIME_TYPE, if (asset.endsWith("png")) "image/png" else "image/jpeg")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                inserted += uri
                resolver.openOutputStream(uri)!!.use { output ->
                    instrumentation.context.assets.open("identity/" + asset).use { it.copyTo(output) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            val photos = app.library.list().photos
            for ((index, uri) in inserted.withIndex()) {
                assertEquals(Confirmation.SAVED, app.photoSubjects.confirm(photos.single { it.uri == uri },
                    SubjectSelection.New(names[index], SubjectKind.PERSON, emptyList()), null))
                subjectIds += app.photoSubjects.refresh(photos).subjects.single { it.name == names[index] }.id
            }
            val before = app.photoSubjects.refresh(photos)
            app.visualIndex.refresh(photos.map { IndexPhoto(it.uri, it.sourceRevision) }, { _, _ -> },
                processUris = inserted.toSet()) { _, _ -> }
            fun open(): LibraryViewModel {
                val opened = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                activity = opened
                lateinit var result: LibraryViewModel
                instrumentation.runOnMainSync { result = ViewModelProvider(opened)[LibraryViewModel::class.java] }
                await { !result.state.value.loading && result.state.value.indexedUris.containsAll(inserted) &&
                    result.state.value.named.subjects.any { it.id == subjectIds.first() } }
                return result
            }
            var model = open()
            val automation = instrumentation.uiAutomation
            fun clickText(text: String) {
                await {
                    automation.rootInActiveWindow?.selfNode { it.text?.toString() == text }
                        ?.selfClick() == true
                }
            }
            fun click(resource: Int) = clickText(app.getString(resource))
            fun openDialog() {
                click(R.string.library_sources)
                click(R.string.self_identity_title)
                await { automation.rootInActiveWindow?.selfNode {
                    it.text?.toString() == app.getString(R.string.self_identity_explanation)
                } != null }
            }
            fun search(query: String) {
                val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
                await { automation.rootInActiveWindow?.selfNode { it.isEditable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true }
            }
            for (index in names.indices) {
                openDialog()
                clickText(names[index])
                click(R.string.self_identity_action)
                await { model.state.value.named.selfSubjectId == subjectIds[index] }
                search("my photos")
                await { model.state.value.query == "my photos" &&
                    model.state.value.visiblePhotos.map { it.uri } == listOf(inserted[index]) }
                assertEquals(NamedSearch.Matches(setOf(inserted[index])), model.state.value.namedSearch)
                assertTrue(model.state.value.resultGroups.related.isEmpty())
                assertEquals(before.references.toSet(), model.state.value.named.references.toSet())
            }
            openDialog()
            click(R.string.self_identity_clear)
            await { model.state.value.named.selfIdentity == SelfIdentity(null) && model.state.value.named.selfSubjectId == null }
            search("my photos")
            await { QueryRequirement.SELF_IDENTITY in model.state.value.queryRequirements }
            assertTrue(model.state.value.resultGroups.confirmed.isEmpty())
            assertEquals(before.references.toSet(), model.state.value.named.references.toSet())
            val opened = checkNotNull(activity)
            instrumentation.runOnMainSync { opened.finish() }
            activity = null
            instrumentation.waitForIdleSync()
            model = open()
            assertEquals(SelfIdentity(null), model.state.value.named.selfIdentity)
            assertEquals(null, model.state.value.named.selfSubjectId)
            search("my photos")
            await { QueryRequirement.SELF_IDENTITY in model.state.value.queryRequirements }
            assertTrue(model.state.value.resultGroups.confirmed.isEmpty())
            assertEquals(before.references.toSet(), model.state.value.named.references.toSet())
        } finally {
            try {
                activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
                inserted.forEach { resolver.delete(it, null, null) }
                val remaining = app.library.list().photos
                app.photoSubjects.refresh(remaining)
                app.visualIndex.currentPhotos(remaining.map { IndexPhoto(it.uri, it.sourceRevision) })
                storage.withDatabase { db ->
                    db.beginTransaction()
                    try {
                        for (id in subjectIds) {
                            db.execSQL("DELETE FROM named_references WHERE subject_id = ?", arrayOf(id))
                            db.execSQL("DELETE FROM named_labels WHERE subject_id = ?", arrayOf(id))
                            db.execSQL("DELETE FROM named_subjects WHERE id = ?", arrayOf(id))
                        }
                        if (previous == null) db.execSQL("DELETE FROM self_identity WHERE id = 1")
                        else db.execSQL("INSERT OR REPLACE INTO self_identity (id, subject_id) VALUES (1, ?)", arrayOf<Any?>(previous.subjectId))
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                val restored = app.photoSubjects.refresh(app.library.list().photos)
                assertEquals(previous, restored.selfIdentity)
                assertEquals(original.subjects.toSet(), restored.subjects.toSet())
                assertEquals(original.references.toSet(), restored.references.toSet())
            } finally { app.indexing.pause(priorPause) }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition()) {
            assertTrue("Self identity workflow timed out", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }
}

private fun AccessibilityNodeInfo.selfClick(): Boolean {
    var current: AccessibilityNodeInfo? = this
    while (current != null) {
        if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        current = current.parent
    }
    return false
}

private fun AccessibilityNodeInfo.selfNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        if (predicate(node)) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
    }
    return null
}
