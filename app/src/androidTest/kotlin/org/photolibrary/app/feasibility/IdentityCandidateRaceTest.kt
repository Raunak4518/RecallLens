package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Log
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
import org.photolibrary.app.identity.IdentityEncoder
import org.photolibrary.app.identity.IdentityModelFactory
import org.photolibrary.app.identity.IdentityModels
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.index.IndexedPhoto
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.identity.IdentityCacheEntry
import org.photolibrary.app.library.IdentityCacheUsage
import org.photolibrary.app.library.IdentityMatcher
import org.photolibrary.app.library.IdentityRanking
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoSubjects
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.searchNamed

@RunWith(AndroidJUnit4::class)
class IdentityCandidateRaceTest {
    @Test
    fun conditionalCacheRemovalPreservesNewerSourceAndReplacementModelRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "identity-conditional-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val storage = EncryptedPhotoIndex(directory, alias)
        val uri = Uri.parse("content://fixture/conditional-identity")
        val model = ModelRevision("identity-fixture", "one", 1600)
        try {
            storage.openIdentity(SubjectKind.OBJECT).use { stale ->
                stale.save(uri, IndexedPhoto("source:one", "pixels:one", model, IdentityCacheEntry()))
                storage.openIdentity(SubjectKind.OBJECT).use { newer ->
                    newer.save(uri, IndexedPhoto("source:two", "pixels:two", model, IdentityCacheEntry()))
                }
                assertFalse(stale.remove(uri, "source:one", model))
                assertEquals("source:two", stale.reconcile(setOf(uri)).getValue(uri).sourceRevision)
                val replacement = model.copy(version = "two")
                storage.openIdentity(SubjectKind.OBJECT).use { newer ->
                    newer.save(uri, IndexedPhoto("source:two", "pixels:two", replacement, IdentityCacheEntry()))
                }
                assertFalse(stale.remove(uri, "source:two", model))
                assertEquals(replacement, stale.reconcile(setOf(uri)).getValue(uri).model)
                assertTrue(stale.remove(uri, "source:two", replacement))
                assertTrue(stale.reconcile(setOf(uri)).isEmpty())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    @Test
    fun deletingCandidateDuringWarmScanSuppressesResultAndPurgesItsCache() = runBlocking { verify(Race.DELETE) }

    @Test
    fun editingCandidateDuringWarmScanSuppressesResultAndPurgesItsCache() = runBlocking { verify(Race.EDIT) }

    @Test
    fun trashingCandidateDuringWarmScanSuppressesResultAndPurgesItsCache() = runBlocking { verify(Race.TRASH) }

    @Test
    fun revokingGalleryDuringWarmScanSuppressesAllResultsAndPurgesItsCache() = runBlocking { verify(Race.REVOKE) }

    private enum class Race { DELETE, EDIT, TRASH, REVOKE }

    private suspend fun verify(race: Race) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val permission = Manifest.permission.READ_MEDIA_IMAGES
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        val operations = listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VISUAL_USER_SELECTED")
        val previousModes = operations.associateWith { operation ->
            appOps.unsafeCheckOpNoThrow("android:" + operation.lowercase(), android.os.Process.myUid(), context.packageName)
        }
        fun setUidMode(operation: String, mode: String) {
            val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd appops set --uid ${context.packageName} $operation $mode")).use { it.readBytes().decodeToString() }
            assertFalse("AppOps command rejected: $output", output.contains("Error", ignoreCase = true) || output.contains("Exception"))
        }
        val alias = "identity-race-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val storage = EncryptedPhotoIndex(directory, alias)
        val library = PhotoLibrary(context)
        val subjects = PhotoSubjects(library, NamedSubjectStore(storage))
        val models = IdentityModels(context.assets)
        val owned = mutableListOf<Uri>()
        val initial = mutableListOf<Uri>()
        fun copy(uri: Uri, asset: String) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
            resolver.openOutputStream(uri, "wt")!!.use { output ->
                instrumentation.context.assets.open("identity/" + asset).use { it.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        try {
            for (asset in listOf("object-reference.png", "object-same.png", "object-lookalike.png")) {
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, UUID.randomUUID().toString() + "-" + asset)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                owned += uri
                initial += uri
                copy(uri, asset)
            }
            val photos = library.list().photos.filter { it.uri in owned }
            val anchor = initial.first()
            val candidate = initial[1]
            val kind = SubjectKind.OBJECT
            assertEquals(Confirmation.SAVED, subjects.confirm(photos.single { it.uri == anchor },
                SubjectSelection.New("My car", kind, emptyList()), null))
            val named = subjects.refresh(photos)
            val matcher = IdentityMatcher(library, OnnxRegionModels(context.assets), models, storage)
            val cold = matcher.rank(kind, named.references, photos) { _, _ -> } as IdentityRanking.Candidates
            assertTrue(cold.photos.any { it.photo.uri == candidate })
            val cacheOnly = object : IdentityModelFactory by models {
                override fun open(kind: SubjectKind): IdentityEncoder = error("Warm race opened an identity encoder")
            }
            val cached = IdentityMatcher(library, OnnxRegionModels(context.assets), cacheOnly, storage)
            val warmStart = SystemClock.elapsedRealtime()
            val warm = cached.rank(kind, named.references, photos) { _, _ -> } as IdentityRanking.Candidates
            val warmMs = SystemClock.elapsedRealtime() - warmStart
            assertEquals(IdentityCacheUsage(2, 0, 1, 0), warm.cacheUsage)
            var changed = false
            val raceStart = SystemClock.elapsedRealtime()
            val result = cached.rank(kind, named.references, photos) { done, _ ->
                if (done == 0 && !changed) {
                    changed = true
                    when (race) {
                        Race.DELETE -> {
                            assertEquals(1, resolver.delete(candidate, null, null))
                            owned.remove(candidate)
                        }
                        Race.EDIT -> copy(candidate, "object-other.png")
                        Race.TRASH -> assertEquals(1, resolver.update(candidate,
                            ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 1) }, null, null))
                        Race.REVOKE -> {
                            for (operation in operations) setUidMode(operation, "ignore")
                            assertEquals(android.app.AppOpsManager.MODE_IGNORED, appOps.unsafeCheckOpNoThrow(
                                checkNotNull(android.app.AppOpsManager.permissionToOp(permission)), android.os.Process.myUid(), context.packageName))
                            assertEquals(GalleryAccess.NONE, library.galleryAccess())
                        }
                    }
                }
            }
            val raceMs = SystemClock.elapsedRealtime() - raceStart
            assertTrue(changed)
            if (race == Race.REVOKE) {
                assertEquals(IdentityRanking.NoReference, result)
                storage.openIdentity(kind).use { assertTrue(it.reconcile(initial.toSet()).isEmpty()) }
            } else {
                val candidates = result as IdentityRanking.Candidates
                assertFalse(candidates.photos.any { it.photo.uri == candidate })
                assertEquals(IdentityCacheUsage(2, 0, 1, 0), candidates.cacheUsage)
                storage.openIdentity(kind).use { assertFalse(candidate in it.reconcile(initial.toSet())) }
                val current = library.list().photos.filter { it.uri in owned }
                assertEquals(NamedSearch.Matches(setOf(anchor)), searchNamed("my car", subjects.refresh(current), true))
            }
            Log.i("IdentityCandidateRace", "$race: warm_ms=$warmMs race_ms=$raceMs stale result suppressed; no encoder opened")
        } finally {
            if (race == Race.REVOKE) for ((operation, mode) in previousModes) {
                val value = when (mode) {
                    android.app.AppOpsManager.MODE_ALLOWED -> "allow"
                    android.app.AppOpsManager.MODE_IGNORED -> "ignore"
                    android.app.AppOpsManager.MODE_ERRORED -> "deny"
                    android.app.AppOpsManager.MODE_FOREGROUND -> "foreground"
                    else -> "default"
                }
                setUidMode(operation, value)
            }
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            owned.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }
}
