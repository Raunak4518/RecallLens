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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoSubjects
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.searchNamed

@RunWith(AndroidJUnit4::class)
class NamedSourceLifecycleTest {
    @Test
    fun confirmedRegionsSurviveRenameAndMoveButEditsAndLostScopeRemoveThem() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val resolver = context.contentResolver
        val alias = "named-lifecycle-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val fixtures = mutableListOf<Uri>()
        val library = PhotoLibrary(context)
        val store = NamedSubjectStore(EncryptedPhotoIndex(directory, alias))
        val subjects = PhotoSubjects(library, store)
        fun write(uri: Uri, color: Int) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
            val image = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(color)
                resolver.openOutputStream(uri, "wt")!!.use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        fun insert(): Uri {
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "named-" + UUID.randomUUID() + ".png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
            fixtures += uri
            write(uri, Color.GREEN)
            return uri
        }
        try {
            val confirmed = insert()
            val identicalUnconfirmed = insert()
            suspend fun photos() = library.list().photos.filter { it.uri in fixtures }
            val originalPhoto = photos().single { it.uri == confirmed }
            val region = PhotoRegion(.1f, .2f, .8f, .9f)
            assertEquals(Confirmation.SAVED, subjects.confirm(originalPhoto,
                SubjectSelection.New("My car", SubjectKind.OBJECT, emptyList()), region))
            assertEquals(Confirmation.SAVED, subjects.confirm(originalPhoto,
                SubjectSelection.New("Me", SubjectKind.PERSON, listOf("Myself")), region))
            val originalProof = store.contentProofs().getValue(confirmed)
            var named = subjects.refresh(photos())
            val carId = named.subjects.single { it.kind == SubjectKind.OBJECT }.id
            assertEquals(NamedSearch.Matches(setOf(confirmed)), searchNamed("my car and me", named, true))
            assertFalse(named.references.any { it.uri == identicalUnconfirmed })
            for (values in listOf(
                ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "renamed-" + UUID.randomUUID() + ".png") },
                ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Named-" + UUID.randomUUID() + "/") },
            )) {
                assertEquals(1, resolver.update(confirmed, values, null, null))
                named = PhotoSubjects(library, NamedSubjectStore(EncryptedPhotoIndex(directory, alias))).refresh(photos())
                assertEquals(NamedSearch.Matches(setOf(confirmed)), searchNamed("my car and me", named, true))
                assertEquals(2, named.references.size)
                assertTrue(named.references.all { it.region == region && it.revision == "source:" + library.currentRevision(confirmed) })
                assertEquals(originalProof.digest, store.contentProofs().getValue(confirmed).digest)
                assertFalse(originalProof.revision == store.contentProofs().getValue(confirmed).revision)
            }
            assertEquals(Confirmation.PHOTO_CHANGED, subjects.confirm(originalPhoto, SubjectSelection.Existing(carId), region))
            write(confirmed, Color.BLUE)
            named = subjects.refresh(photos())
            assertEquals(NamedSearch.Matches(emptySet<Uri>()), searchNamed("my car", named, true))
            assertTrue(store.contentProofs().isEmpty())
            assertEquals(Confirmation.SAVED, subjects.confirm(photos().single { it.uri == confirmed }, SubjectSelection.Existing(carId), region))
            val beforeScopeLoss = store.contentProofs().getValue(confirmed)
            subjects.refresh(photos().filter { it.uri != confirmed })
            assertTrue(store.contentProofs().isEmpty())
            named = subjects.refresh(photos())
            assertEquals(NamedSearch.Matches(emptySet<Uri>()), searchNamed("my car", named, true))
            assertFalse(store.rebindUnchanged(confirmed, beforeScopeLoss, "late-revision", beforeScopeLoss.digest))
        } finally {
            fixtures.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
