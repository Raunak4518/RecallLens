package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.SystemClock
import android.location.Location
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoLocations
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.LocationCache
import org.photolibrary.app.index.PhotoCoordinates

@RunWith(AndroidJUnit4::class)
class PhotoLocationsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver

    @Test
    fun compareFiveThousandCachedLocationsWithIndividualRevisionReads() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("locationScale") == "true")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
        val alias = "location-scale-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, alias)
        val storage = EncryptedPhotoIndex(directory, alias)
        val cache = LocationCache(storage)
        val inserted = mutableListOf<Uri>()
        try {
            repeat(5_000) { position ->
                insertPhoto(null, inserted)
                if ((position + 1) % 500 == 0) Log.i("LocationScale", "Prepared ${position + 1} photos")
            }
            val library = PhotoLibrary(context)
            val scope = inserted.toHashSet()
            val photos = library.list().photos.filter { it.uri in scope }
            assertEquals(5_000, photos.size)
            val anchor = photos.first().uri
            storage.withDatabase { db ->
                db.beginTransaction()
                try {
                    for ((index, photo) in photos.withIndex()) {
                        val latitude = if (index % 2 == 0) 21.1702 else 22.1702
                        db.execSQL("INSERT INTO photo_locations VALUES (?, ?, ?, ?)",
                            arrayOf<Any?>(photo.uri.toString(), photo.sourceRevision, latitude, 72.8311))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            val locations = PhotoLocations(context, library, cache, { true }) { error("Cached query read EXIF") }
            val query = NamedSearch.Nearby(null, setOf(anchor))
            val expected = photos.filterIndexed { index, _ -> index % 2 == 0 }.mapTo(hashSetOf()) { it.uri }
            val before = mutableListOf<Long>()
            val after = mutableListOf<Long>()
            repeat(4) { run ->
                fun baseline(): Long {
                    val start = SystemClock.elapsedRealtime()
                    val records = cache.reconcile(photos.associate { it.uri to it.sourceRevision })
                    val result = photos.filter { photo ->
                        val current = library.currentRevision(photo.uri)
                        val point = checkNotNull(records[photo.uri]).coordinates
                        val distance = FloatArray(1)
                        Location.distanceBetween(21.1702, 72.8311, point!!.latitude, point.longitude, distance)
                        current == photo.sourceRevision && distance[0] <= 30_000
                    }.mapTo(hashSetOf()) { it.uri }
                    assertEquals(expected, result)
                    return SystemClock.elapsedRealtime() - start
                }
                suspend fun batched(): Long {
                    val start = SystemClock.elapsedRealtime()
                    assertEquals(NamedSearch.NearbyMatches(expected), locations.search(query, photos))
                    return SystemClock.elapsedRealtime() - start
                }
                val measured = if (run % 2 == 0) baseline() to batched() else {
                    val revised = batched()
                    baseline() to revised
                }
                if (run > 0) {
                    before += measured.first
                    after += measured.second
                }
            }
            val report = org.json.JSONObject().put("photos", photos.size).put("before_ms", org.json.JSONArray(before))
                .put("after_ms", org.json.JSONArray(after)).put("matches", expected.size).put("exif_reads", 0)
            context.filesDir.resolve("location-scale-results.json").writeText(report.toString())
        } finally {
            inserted.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    @Test
    fun encryptedLocationsSurviveReopenAndPurgeAfterScopeAndPermissionChanges() = runBlocking<Unit> {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
        val alias = "location-cache-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, alias)
        val cache = LocationCache(EncryptedPhotoIndex(directory, alias))
        val inserted = mutableListOf<Uri>()
        var reads = 0
        var locationAllowed = true
        try {
            val anchor = insertPhoto(21.1702 to 72.8311, inserted)
            val second = insertPhoto(null, inserted)
            val library = PhotoLibrary(context)
            val photos = library.list().photos.filter { it.uri in inserted }
            val query = NamedSearch.Nearby(null, setOf(anchor))
            fun locations() = PhotoLocations(context, library, cache, { locationAllowed }) { photo ->
                reads++
                if (photo.uri == anchor) PhotoCoordinates(21.1702, 72.8311) else null
            }
            assertEquals(NamedSearch.NearbyMatches(setOf(anchor)), locations().search(query, photos))
            assertEquals(2, reads)
            assertEquals(NamedSearch.NearbyMatches(setOf(anchor)), locations().search(query, photos))
            assertEquals(2, reads)
            var expectedReads = 2
            for (change in listOf(
                ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "renamed-" + UUID.randomUUID() + ".jpg") },
                ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Location-" + UUID.randomUUID() + "/") },
            )) {
                assertEquals(1, resolver.update(anchor, change, null, null))
                val moved = library.list().photos.filter { it.uri in inserted }
                assertEquals(NamedSearch.NearbyMatches(setOf(anchor)), locations().search(query, moved))
                assertEquals(++expectedReads, reads)
                assertEquals(NamedSearch.NearbyMatches(setOf(anchor)), locations().search(query, moved))
                assertEquals(expectedReads, reads)
            }
            resolver.update(anchor, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
            resolver.update(anchor, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val revised = library.list().photos.filter { it.uri in inserted }
            assertEquals(NamedSearch.NearbyMatches(setOf(anchor)), locations().search(query, revised))
            assertEquals(expectedReads + 1, reads)
            val permitted = revised.filter { it.uri != second }
            locations().reconcile(permitted)
            assertFalse(second in cache.reconcile(revised.associate { it.uri to it.sourceRevision }))
            locationAllowed = false
            assertEquals(NamedSearch.LocationPermission, locations().search(query, permitted))
            assertEquals(emptyMap<Uri, Any>(), cache.reconcile(permitted.associate { it.uri to it.sourceRevision }))
        } finally {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
            inserted.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    @Test
    fun readsOriginalGpsAndFiltersByRadiusAndConfirmedNames() = runBlocking<Unit> {
        val galleryPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, galleryPermission)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
        val inserted = mutableListOf<Uri>()
        try {
            val surat = insertPhoto(21.1702 to 72.8311, inserted)
            val nearby = insertPhoto(21.2367 to 72.7532, inserted)
            val distant = insertPhoto(21.7051 to 72.9959, inserted)
            val unknown = insertPhoto(null, inserted)
            val library = PhotoLibrary(context)
            val photos = library.list().photos.filter { it.uri in inserted }
            assertEquals(4, photos.size)
            val locations = PhotoLocations(context, library)
            assertEquals(NamedSearch.NearbyMatches(setOf(surat, nearby)),
                locations.search(NamedSearch.Nearby(null, setOf(surat)), photos))
            val distanceOrdered = locations.search(NamedSearch.Nearby(null, setOf(surat)), photos.reversed())
                as NamedSearch.NearbyMatches
            assertEquals(listOf(surat, nearby), distanceOrdered.photos.toList())
            assertEquals(NamedSearch.NearbyMatches(setOf(nearby)),
                locations.search(NamedSearch.Nearby(setOf(nearby, distant, unknown), setOf(surat)), photos))
            assertEquals(NamedSearch.NoPlaceCoordinates, locations.search(NamedSearch.Nearby(null, setOf(unknown)), photos))
        } finally {
            inserted.forEach { resolver.delete(it, null, null) }
        }
    }

    @Test
    fun exifOnlyEditInvalidatesCachedCoordinatesEvenWhenPixelsAreUnchanged() = runBlocking<Unit> {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
        val alias = "gps-edit-" + UUID.randomUUID()
        val directory = context.noBackupFilesDir.resolve(alias)
        val inserted = mutableListOf<Uri>()
        val temporary = File.createTempFile("gps-edit-", ".jpg", context.cacheDir)
        try {
            val anchor = insertPhoto(21.1702 to 72.8311, inserted)
            val candidate = insertPhoto(21.2367 to 72.7532, inserted)
            val library = PhotoLibrary(context)
            val cache = LocationCache(EncryptedPhotoIndex(directory, alias))
            val query = NamedSearch.Nearby(null, setOf(anchor))
            suspend fun photos() = library.list().photos.filter { it.uri in inserted }
            fun locations() = PhotoLocations(context, library, cache)
            assertEquals(NamedSearch.NearbyMatches(setOf(anchor, candidate)), locations().search(query, photos()))
            val before = library.loadPixels(candidate, 1600)!!.let { image ->
                try { org.photolibrary.app.index.pixelRevision(image) } finally { image.recycle() }
            }
            resolver.openInputStream(MediaStore.setRequireOriginal(candidate))!!.use { input ->
                temporary.outputStream().use { input.copyTo(it) }
            }
            ExifInterface(temporary.path).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, exifDegrees(25.0))
                saveAttributes()
            }
            resolver.update(candidate, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
            resolver.openOutputStream(candidate, "wt")!!.use { output -> temporary.inputStream().use { it.copyTo(output) } }
            resolver.update(candidate, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val after = library.loadPixels(candidate, 1600)!!.let { image ->
                try { org.photolibrary.app.index.pixelRevision(image) } finally { image.recycle() }
            }
            assertEquals(before, after)
            assertEquals(NamedSearch.NearbyMatches(setOf(anchor)), locations().search(query, photos()))
        } finally {
            inserted.forEach { resolver.delete(it, null, null) }
            temporary.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private fun insertPhoto(point: Pair<Double, Double>?, inserted: MutableList<Uri>): Uri {
        val file = File.createTempFile("named-location-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                bitmap.recycle()
            }
            if (point != null) ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, exifDegrees(point.first))
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (point.first >= 0) "N" else "S")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, exifDegrees(point.second))
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (point.second >= 0) "E" else "W")
                saveAttributes()
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NamedSearchTest")
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
            inserted += uri
            checkNotNull(resolver.openOutputStream(uri)).use { target -> file.inputStream().use { it.copyTo(target) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } finally {
            file.delete()
        }
    }

    private fun exifDegrees(value: Double) = "${(abs(value) * 1_000_000).roundToInt()}/1000000,0/1,0/1"
}
