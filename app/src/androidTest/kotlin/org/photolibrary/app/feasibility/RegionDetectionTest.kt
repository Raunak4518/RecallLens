package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.library.NameSuggestion
import org.photolibrary.app.library.PhotoDetectionScan
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoSubjects
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.searchNamed

@RunWith(AndroidJUnit4::class)
class RegionDetectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val models = OnnxRegionModels(context.assets)

    @Test
    fun realModelFindsCommonObjectTwoFaceRegionsAndNoBlankRegions() {
        assertTrue("Stage the pinned detection models before running this class", models.available)
        val dog = bitmap("clip-local/photo-01.jpg")
        val portrait = bitmap("detection/astronaut.png")
        val two = Bitmap.createBitmap(portrait.width * 2, portrait.height, Bitmap.Config.ARGB_8888)
        val blank = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            Canvas(two).apply {
                drawBitmap(portrait, 0f, 0f, null)
                drawBitmap(portrait, portrait.width.toFloat(), 0f, null)
            }
            models.open().use { detector ->
                assertTrue(detector.detect(dog).any { it.label == "dog" && it.kind == SubjectKind.OBJECT })
                val faces = detector.detect(two).filter { it.kind == SubjectKind.PERSON }
                assertEquals(2, faces.size)
                assertTrue(faces.any { it.region.right < .5f })
                assertTrue(faces.any { it.region.left > .5f })
                assertTrue(detector.detect(blank).isEmpty())
            }
        } finally {
            dog.recycle()
            portrait.recycle()
            two.recycle()
            blank.recycle()
        }
    }

    @Test
    fun scannedPhotosRemainUnconfirmedAndAnEditRejectsAStaleSuggestion() = runBlocking<Unit> {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val library = PhotoLibrary(context)
        val alias = "detection-fixture-${UUID.randomUUID()}"
        val directory = context.noBackupFilesDir.resolve(alias)
        val subjects = PhotoSubjects(library, NamedSubjectStore(EncryptedPhotoIndex(directory, alias)))
        val created = mutableListOf<Uri>()
        try {
            val first = insertDog(created)
            val second = insertDog(created)
            var photos = library.list().photos.filter { it.uri in created }
            val candidates = suggestions(library, photos).filter { it.detection.label == "dog" }
            assertEquals(created.toSet(), candidates.map { it.photo.uri }.toSet())
            val firstDog = candidates.first { it.photo.uri == first }
            val secondDog = candidates.first { it.photo.uri == second }
            val name = SubjectSelection.New("My dog", SubjectKind.OBJECT, emptyList())
            assertEquals(NamedSearch.Unknown, searchNamed("my dog", subjects.refresh(photos), true))
            assertEquals(Confirmation.SAVED, subjects.confirm(firstDog.photo, name, firstDog.detection.region, firstDog.revision))
            val confirmed = subjects.refresh(photos)
            assertTrue(firstDog.isConfirmed(confirmed))
            assertFalse(secondDog.isConfirmed(confirmed))
            assertEquals(NamedSearch.Matches(setOf(first)), searchNamed("my dog", confirmed, true))

            val blank = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            try {
                checkNotNull(resolver.openOutputStream(second, "wt")).use { check(blank.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                blank.recycle()
            }
            photos = library.list().photos.filter { it.uri in created }
            val edited = photos.first { it.uri == second }
            assertEquals(Confirmation.PHOTO_CHANGED, subjects.confirm(edited,
                SubjectSelection.Existing(confirmed.subjects.single().id), secondDog.detection.region, secondDog.revision))
            assertEquals(NamedSearch.Matches(setOf(first)), searchNamed("my dog", subjects.refresh(photos), true))
        } finally {
            created.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private suspend fun suggestions(library: PhotoLibrary, photos: List<SelectedPhoto>): List<NameSuggestion> {
        val detections = mutableListOf<NameSuggestion>()
        PhotoDetectionScan(library, models).scan(photos) { _, found -> detections += found }
        return detections
    }

    private fun bitmap(asset: String): Bitmap =
        instrumentation.context.assets.open(asset).use { checkNotNull(BitmapFactory.decodeStream(it)) }

    private fun insertDog(created: MutableList<Uri>): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "detection-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NameSuggestionTest")
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        created += uri
        checkNotNull(resolver.openOutputStream(uri)).use { target ->
            instrumentation.context.assets.open("clip-local/photo-01.jpg").use { it.copyTo(target) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
