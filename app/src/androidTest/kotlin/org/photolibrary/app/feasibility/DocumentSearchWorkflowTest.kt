package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.library.LibraryViewModel
import org.photolibrary.app.library.UnavailableSources
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.SelfIdentity
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection

@RunWith(AndroidJUnit4::class)
class DocumentSearchWorkflowTest {
    @Test
    fun onePromptFindsClassTenDocumentFromOnDeviceOcrAndSavedSelf() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val checkpoint = app.indexing.progress.value.checkpoint.paused
        val storage = EncryptedPhotoIndex(app.noBackupFilesDir.resolve("photo-text-index"), "photo-library.ocr-wrap.v1")
        val previous = storage.withDatabase { db ->
            db.rawQuery("SELECT subject_id FROM self_identity WHERE id = 1", emptyArray<String>()).use {
                if (it.moveToFirst()) SelfIdentity(if (it.isNull(0)) null else it.getLong(0)) else null
            }
        }
        val resolver = app.contentResolver
        val fixtures = linkedMapOf<Uri, String>()
        val suffix = UUID.randomUUID().toString().take(8)
        val name = "Asha Verma"
        var activity: MainActivity? = null
        var enrolled: Long? = null
        fun insert(lines: List<String>): Uri {
            val imageName = "scan-$suffix-${fixtures.size + 1}.png"
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, imageName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
            fixtures[uri] = imageName
            val bitmap = documentImage(lines)
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally { bitmap.recycle() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        try {
            app.indexing.pause(true)
            app.indexScheduler.cancel()
            val classTen = listOf("SECONDARY SCHOOL EXAMINATION", "STATEMENT OF MARKS", "CLASS X",
                "CANDIDATE NAME: ASHA VERMA", "TOTAL MARKS: 452 / 500")
            val target = insert(classTen)
            val classTwelve = insert(listOf("SENIOR SECONDARY EXAMINATION", "MARKSHEET", "CLASS 12",
                "CANDIDATE NAME: ASHA VERMA", "TOTAL MARKS: 475 / 500"))
            val anotherStudent = insert(listOf("GRADE SHEET", "CLASS 10", "STUDENT NAME: NOOR PATEL",
                "TOTAL MARKS: 411 / 500"))
            val unknownOwner = insert(listOf("MARKSHEET", "CLASS 10", "TOTAL MARKS: 431 / 500"))
            val reference = insert(listOf("CLASS 10 STUDY NOTES", "PHYSICS PRACTICE", "Asha Verma", "CHAPTER ONE"))
            val unprocessed = insert(classTen)
            val searchable = fixtures.keys - unprocessed
            val gallery = app.library.list().photos
            val source = gallery.single { it.uri == reference }
            assertEquals(Confirmation.SAVED, app.photoSubjects.confirm(source,
                SubjectSelection.New(name, SubjectKind.PERSON, emptyList()), null))
            val owner = app.photoSubjects.refresh(gallery).subjects.single { it.name == name }.id
            enrolled = owner
            assertTrue(app.photoSubjects.setSelf(owner))

            val indexed = gallery.map { IndexPhoto(it.uri, it.sourceRevision) }
            val recognition = linkedMapOf<Uri, PhotoText>()
            app.textIndex.refresh(indexed, { _, _ -> }, processUris = searchable) { uri, text ->
                if (text != null) recognition[uri] = text
            }
            assertEquals(searchable, recognition.keys)
            assertTrue(recognition.getValue(target).matches("statement of marks"))
            assertTrue(recognition.getValue(target).matches("class x"))
            assertTrue(recognition.getValue(target).matches("candidate name asha verma"))
            assertTrue(recognition.getValue(classTwelve).matches("class 12"))
            assertTrue(recognition.getValue(anotherStudent).matches("student name noor patel"))

            val opened = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = opened
            lateinit var model: LibraryViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(opened)[LibraryViewModel::class.java] }
            await("recognition available in search") {
                val current = model.state.value
                !current.loading && !current.restoringIndex && current.named.selfSubjectId == owner &&
                    current.indexedUris.containsAll(searchable)
            }
            val initial = model.state.value
            assertTrue(initial.photos.any { it.uri == unprocessed })
            assertTrue(unprocessed !in initial.indexedUris)
            assertTrue(initial.visiblePhotos.none { it.uri == unprocessed })
            fun search(prompt: String) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
                }
                await("unified search accepts input") {
                    automation.rootInActiveWindow?.documentNodes()?.singleOrNull { it.isVisibleToUser && it.isEditable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true
                }
            }
            fun AccessibilityNodeInfo.fixtureName(): String? = text?.lineSequence()?.firstOrNull(fixtures.values::contains)
            fun firstVisibleFixture(): String? {
                val nodes = automation.rootInActiveWindow?.documentNodes().orEmpty()
                return nodes.asSequence().filter { it.isVisibleToUser }
                    .mapNotNull { node -> node.fixtureName()?.let { it to Rect().apply { node.getBoundsInScreen(this) } } }
                    .minWithOrNull(compareBy<Pair<String, Rect>> { it.second.top }.thenBy { it.second.left })?.first
            }
            fun isVisible(uri: Uri): Boolean = automation.rootInActiveWindow?.documentNodes().orEmpty()
                .any { it.isVisibleToUser && it.fixtureName() == fixtures.getValue(uri) }
            fun hasVisibleLabel(label: String): Boolean = automation.rootInActiveWindow?.documentNodes().orEmpty()
                .any { it.isVisibleToUser && it.text?.lineSequence()?.any { line -> line == label } == true }

            assertTrue(!isVisible(unprocessed))
            val documentLabel = app.getString(R.string.results_document)
            val possibleLabel = app.getString(R.string.results_document_possible)
            val ownedPrompt = "I want my 10th marksheet"
            search(ownedPrompt)
            await("the cross-line Class X marksheet ranks first through the prompt") {
                val current = model.state.value
                current.query == ownedPrompt && !current.searching && current.visiblePhotos.firstOrNull()?.uri == target &&
                    current.resultGroups.documents.firstOrNull() == target && unknownOwner in current.resultGroups.possibleDocuments &&
                    firstVisibleFixture() == fixtures.getValue(target) && hasVisibleLabel(documentLabel)
            }
            assertTrue(model.state.value.resultGroups.confirmed.isEmpty())
            assertTrue(model.state.value.visiblePhotos.none { it.uri in setOf(classTwelve, anotherStudent, reference, unprocessed) })
            assertTrue(!isVisible(classTwelve) && !isVisible(anotherStudent) && !isVisible(unprocessed))

            val linesPrompt = "Class X statement of marks"
            search(linesPrompt)
            await("the class and document title combine across OCR lines") {
                val current = model.state.value
                current.query == linesPrompt && !current.searching &&
                    current.resultGroups.documents.containsAll(listOf(target, anotherStudent)) && hasVisibleLabel(documentLabel)
            }
            assertTrue(model.state.value.visiblePhotos.none { it.uri in setOf(classTwelve, reference, unprocessed) })

            instrumentation.runOnMainSync { model.selectSelf(null) }
            await("self setting cleared") { !model.state.value.loading && model.state.value.named.selfSubjectId == null }
            search(ownedPrompt)
            await("Class 10 candidates appear as possible matches without saved ownership") {
                val current = model.state.value
                current.query == ownedPrompt && !current.searching &&
                    current.resultGroups.possibleDocuments.containsAll(listOf(target, anotherStudent)) &&
                    current.resultGroups.documents.isEmpty() && hasVisibleLabel(possibleLabel) && !hasVisibleLabel(documentLabel)
            }
            assertTrue(model.state.value.resultGroups.confirmed.isEmpty())
            assertTrue(model.state.value.visiblePhotos.none { it.uri in setOf(classTwelve, reference, unprocessed) })
        } finally {
            try {
                activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
                fixtures.keys.forEach { resolver.delete(it, null, null) }
                val remaining = app.library.list()
                val unavailable = UnavailableSources.from(remaining.folders, remaining.unavailableVolumes)
                app.photoSubjects.refresh(remaining.photos, unavailable)
                app.textIndex.currentPhotos(remaining.photos.map { IndexPhoto(it.uri, it.sourceRevision) }, unavailable)
                storage.withDatabase { db ->
                    db.beginTransaction()
                    try {
                        if (previous == null) db.execSQL("DELETE FROM self_identity WHERE id = 1")
                        else db.execSQL("INSERT OR REPLACE INTO self_identity (id, subject_id) VALUES (1, ?)", arrayOf<Any?>(previous.subjectId))
                        enrolled?.let { id ->
                            db.execSQL("DELETE FROM named_references WHERE subject_id = ?", arrayOf(id))
                            db.execSQL("DELETE FROM named_labels WHERE subject_id = ?", arrayOf(id))
                            db.execSQL("DELETE FROM named_subjects WHERE id = ?", arrayOf(id))
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            } finally { app.indexing.pause(checkpoint) }
        }
    }

    private fun documentImage(lines: List<String>): Bitmap = Bitmap.createBitmap(1180, 660, Bitmap.Config.ARGB_8888).apply {
        val page = Canvas(this)
        page.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 48f
        }
        lines.forEachIndexed { index, text -> page.drawText(text, 60f, 130f + index * 94f, paint) }
    }

    private fun await(name: String, condition: () -> Boolean) {
        val timeout = SystemClock.elapsedRealtime() + 90_000L
        while (!condition()) {
            assertTrue("Timed out: $name", SystemClock.elapsedRealtime() < timeout)
            SystemClock.sleep(100L)
        }
    }
}

private fun AccessibilityNodeInfo.documentNodes(): List<AccessibilityNodeInfo> = buildList {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this@documentNodes)
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
    }
}
