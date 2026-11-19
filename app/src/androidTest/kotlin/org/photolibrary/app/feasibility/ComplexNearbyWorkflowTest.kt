package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection

@RunWith(AndroidJUnit4::class)
class ComplexNearbyWorkflowTest {
    @Test
    fun partialSceneSearchUsesConfirmedEntitiesAndGpsBeforeRanking() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
        val priorPause = app.indexing.progress.value.checkpoint.paused
        app.indexing.pause(true)
        app.indexScheduler.cancel()
        val original = app.photoSubjects.refresh(app.library.list().photos)
        val originalFolders = app.library.list().folders
        val resolver = app.contentResolver
        val inserted = mutableListOf<Uri>()
        val subjectIds = mutableSetOf<Long>()
        val suffix = UUID.randomUUID().toString().take(8)
        val person = "Person $suffix"
        val vehicle = "Vehicle $suffix"
        val place = "Surat $suffix"
        val storage = EncryptedPhotoIndex(app.noBackupFilesDir.resolve("photo-text-index"), "photo-library.ocr-wrap.v1")
        var activity: MainActivity? = null
        try {
            val points = listOf(21.1702 to 72.8311, 21.18 to 72.8311, 22.17 to 72.8311, 21.18 to 72.8311, null)
            for ((index, point) in points.withIndex()) {
                val file = File.createTempFile("complex-nearby-", ".jpg", app.cacheDir)
                try {
                    instrumentation.context.assets.open("complex-content/53e5999176303196.jpg").use { source ->
                        file.outputStream().use { source.copyTo(it) }
                    }
                    ExifInterface(file.path).apply {
                        for (tag in listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF)) {
                            setAttribute(tag, null)
                        }
                        if (point != null) {
                            setAttribute(ExifInterface.TAG_GPS_LATITUDE, degrees(point.first))
                            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (point.first >= 0) "N" else "S")
                            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, degrees(point.second))
                            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (point.second >= 0) "E" else "W")
                        }
                        saveAttributes()
                    }
                    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "complex-nearby-$suffix-$index.jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }))
                    inserted += uri
                    checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } finally { file.delete() }
            }
            val photos = app.library.list().photos
            val anchor = inserted[0]
            val near = inserted[1]
            val far = inserted[2]
            val unconfirmed = inserted[3]
            val missing = inserted[4]
            suspend fun confirm(uri: Uri, name: String, kind: SubjectKind) {
                val saved = app.photoSubjects.refresh(photos).subjects.firstOrNull { it.name == name }
                val selection = if (saved == null) SubjectSelection.New(name, kind, emptyList()) else SubjectSelection.Existing(saved.id)
                assertEquals(Confirmation.SAVED, app.photoSubjects.confirm(photos.single { it.uri == uri }, selection, null))
                subjectIds += app.photoSubjects.refresh(photos).subjects.single { it.name == name }.id
            }
            confirm(anchor, place, SubjectKind.PLACE)
            for (uri in listOf(near, far, missing)) {
                confirm(uri, person, SubjectKind.PERSON)
                confirm(uri, vehicle, SubjectKind.OBJECT)
            }
            val before = app.photoSubjects.refresh(photos)
            app.visualIndex.refresh(photos.map { IndexPhoto(it.uri, it.sourceRevision) }, { _, _ -> },
                processUris = inserted.toSet()) { _, _ -> }
            val opened = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = opened
            lateinit var model: LibraryViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(opened)[LibraryViewModel::class.java] }
            await { !model.state.value.loading && !model.state.value.restoringIndex &&
                model.state.value.visualIndexed >= inserted.size && model.state.value.named.subjects.any { it.name == person } }
            val automation = instrumentation.uiAutomation
            fun hasText(text: String) = automation.rootInActiveWindow?.nearbyNode { it.text?.toString() == text } != null
            fun search(query: String) {
                val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
                await { automation.rootInActiveWindow?.nearbyNode { it.isEditable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true }
            }
            val complex = "$person behind $vehicle around $place"
            search(complex)
            await { model.state.value.query == complex && !model.state.value.searching &&
                model.state.value.queryLocation == NamedSearch.NearbyMatches(setOf(near)) &&
                model.state.value.resultGroups.related == listOf(near) }
            assertEquals(listOf(near), model.state.value.visiblePhotos.map { it.uri })
            assertTrue(model.state.value.resultGroups.confirmed.isEmpty())
            assertTrue(QueryRequirement.SPATIAL_RELATION in model.state.value.queryRequirements)
            assertFalse(QueryRequirement.LOCATION_VERIFICATION in model.state.value.queryRequirements)
            assertTrue(listOf(anchor, far, unconfirmed, missing).none { excluded -> model.state.value.visiblePhotos.any { it.uri == excluded } })
            await { hasText(app.getString(R.string.subject_location_radius, 30)) && hasText(app.getString(R.string.results_partial)) }
            val screenshot = checkNotNull(automation.takeScreenshot())
            try {
                app.filesDir.resolve("complex-nearby-search.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            search("$person and $vehicle near $place")
            await { !model.state.value.searching && model.state.value.namedSearch == NamedSearch.NearbyMatches(setOf(near)) &&
                model.state.value.resultGroups.confirmed == listOf(near) }
            assertEquals(listOf(near), model.state.value.visiblePhotos.map { it.uri })
            assertTrue(model.state.value.resultGroups.related.isEmpty())
            assertTrue(model.state.value.queryRequirements.isEmpty())
            assertEquals(before.references.toSet(), model.state.value.named.references.toSet())
            assertEquals(original.selfIdentity, model.state.value.named.selfIdentity)
        } finally {
            try {
                activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
                inserted.forEach { resolver.delete(it, null, null) }
                val remaining = app.library.list().photos
                app.photoSubjects.refresh(remaining)
                app.visualIndex.currentPhotos(remaining.map { IndexPhoto(it.uri, it.sourceRevision) })
                app.photoLocations.reconcile(remaining)
                storage.withDatabase { db ->
                    db.beginTransaction()
                    try {
                        for (id in subjectIds) {
                            db.execSQL("DELETE FROM named_references WHERE subject_id = ?", arrayOf(id))
                            db.execSQL("DELETE FROM named_labels WHERE subject_id = ?", arrayOf(id))
                            db.execSQL("DELETE FROM named_subjects WHERE id = ?", arrayOf(id))
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                val restored = app.photoSubjects.refresh(remaining)
                assertEquals(original.selfIdentity, restored.selfIdentity)
                assertEquals(original.subjects.toSet(), restored.subjects.toSet())
                assertEquals(original.references.toSet(), restored.references.toSet())
                assertEquals(originalFolders, app.library.list().folders)
            } finally { app.indexing.pause(priorPause) }
        }
    }

    private fun degrees(value: Double) = "${(abs(value) * 1_000_000).roundToInt()}/1000000,0/1,0/1"

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition()) {
            assertTrue("Complex nearby workflow timed out", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }
}

private fun AccessibilityNodeInfo.nearbyNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        if (predicate(node)) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
    }
    return null
}
