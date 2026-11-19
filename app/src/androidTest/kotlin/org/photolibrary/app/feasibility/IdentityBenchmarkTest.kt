package org.photolibrary.app.feasibility

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.identity.IdentityModels
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.library.IdentityMatcher
import org.photolibrary.app.library.IdentityCacheUsage
import org.photolibrary.app.library.IdentityRanking
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoSubjects
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.library.identityOrder
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.searchNamed

@RunWith(AndroidJUnit4::class)
class IdentityBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver

    @Test
    fun pubFig83Retrieval() = runBlocking<Unit> { benchmark("face", SubjectKind.PERSON) }

    @Test
    fun objectronRetrieval() = runBlocking<Unit> { benchmark("object", SubjectKind.OBJECT) }

    private suspend fun benchmark(domain: String, kind: SubjectKind) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("identityBenchmark") == "true")
        val assets = instrumentation.context.assets
        val folder = "identity-benchmark/$domain"
        val manifest = assets.open("$folder/manifest.json").bufferedReader().use { JSONArray(it.readText()) }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val library = PhotoLibrary(context)
        val matcher = IdentityMatcher(library, OnnxRegionModels(context.assets), IdentityModels(context.assets))
        assertTrue(matcher.available(kind))
        val alias = "identity-benchmark-${UUID.randomUUID()}"
        val directory = context.noBackupFilesDir.resolve(alias)
        val inserted = mutableListOf<Uri>()
        try {
            val dataset = (0 until manifest.length()).map { index ->
                val item = manifest.getJSONObject(index)
                val file = item.getString("file")
                val bytes = assets.open("$folder/$file").use { it.readBytes() }
                assertEquals(item.getString("sha256"), sha(bytes))
                BenchmarkImage(item.getString("id"), item.getString("group"), item.getString("role") == "anchor",
                    insert(file, bytes, inserted))
            }
            val photos = library.list().photos.filter { it.uri in inserted }.associateBy { it.uri }
            assertEquals(dataset.size, photos.size)
            val queries = dataset.filter { it.anchor }
            val gallery = dataset.filterNot { it.anchor }
            assertEquals(48, queries.size)
            assertEquals(if (kind == SubjectKind.PERSON) 131 else 96, gallery.size)
            val galleryPhotos = gallery.map { photos.getValue(it.uri) }
            if (InstrumentationRegistry.getArguments().getString("identityCacheBenchmark") == "true") {
                val anchor = photos.getValue(queries.first().uri)
                benchmarkCache(domain, kind, library, matcher, EncryptedPhotoIndex(directory, alias), directory, anchor, galleryPhotos)
                return
            }
            val started = SystemClock.elapsedRealtimeNanos()
            var scanMs = 0.0
            val results = matcher.open(kind).use { scan ->
                val vectors = gallery.mapNotNull { item ->
                    val vector = scan.describe(photos.getValue(item.uri)) ?: return@mapNotNull null
                    item to vector
                }
                scanMs = elapsed(started)
                Log.i("IdentityBenchmark", "$domain gallery scanned ${gallery.size} in $scanMs ms")
                queries.map { query ->
                    val photo = photos.getValue(query.uri)
                    val revision = checkNotNull(library.referenceRevision(photo.uri))
                    val prepare = SystemClock.elapsedRealtimeNanos()
                    val reference = scan.reference(photo, SubjectReference(0, photo.uri, revision, null))
                    val referenceMs = elapsed(prepare)
                    val scoring = SystemClock.elapsedRealtimeNanos()
                    val anchors = listOfNotNull(reference)
                    val ranked = vectors.mapNotNull { (item, vector) -> vector.match(anchors)?.let { item to it } }
                        .sortedWith { first, second -> identityOrder.compare(first.second, second.second) }
                    val target = ranked.indexOfFirst { it.first.id == query.id } + 1
                    val hard = ranked.filter { it.first.group == query.group }.indexOfFirst { it.first.id == query.id } + 1
                    JSONObject().put("id", query.id).put("group", query.group)
                        .put("rank", target).put("same_group_rank", hard).put("reference_available", reference != null)
                        .put("rankable_gallery", ranked.size).put("reference_ms", referenceMs).put("ranking_ms", elapsed(scoring))
                        .put("top5_ids", JSONArray(ranked.take(5).map { it.first.id }))
                }
            }
            val timedQueries = if (kind == SubjectKind.OBJECT) queries.groupBy { it.group }.values.map { it.first() }
                else listOf(queries[0], queries[16], queries[32])
            val live = timedQueries.map { query ->
                val photo = photos.getValue(query.uri)
                val reference = SubjectReference(0, photo.uri, checkNotNull(library.referenceRevision(photo.uri)), null)
                val start = SystemClock.elapsedRealtimeNanos()
                val result = matcher.rank(kind, listOf(reference), listOf(photo) + galleryPhotos) { _, _ -> }
                val scanTime = elapsed(start)
                Log.i("IdentityBenchmark", "$domain app scan ${query.id} ${gallery.size} in $scanTime ms")
                if (result is IdentityRanking.Candidates) {
                    val offline = results.single { it.getString("id") == query.id }.getJSONArray("top5_ids")
                    val expected = (0 until offline.length()).map { offline.getString(it) }
                    val labels = gallery.associateBy { it.uri }
                    assertEquals(expected, result.photos.take(5).map { labels.getValue(it.photo.uri).id })
                }
                JSONObject().put("id", query.id).put("gallery_photos", gallery.size).put("scan_ms", scanTime)
                    .put("completed", result is IdentityRanking.Candidates)
            }
            val check = results.first { it.getInt("rank") in 1..5 }.getString("id")
            val anchor = queries.first { it.id == check }
            val anchorPhoto = photos.getValue(anchor.uri)
            val names = PhotoSubjects(library, NamedSubjectStore(EncryptedPhotoIndex(directory, alias)))
            val name = "Benchmark subject"
            assertEquals(Confirmation.SAVED, names.confirm(anchorPhoto, SubjectSelection.New(name, kind, emptyList()), null))
            val active = listOf(anchorPhoto) + galleryPhotos
            val before = names.refresh(active)
            val database = directory.resolve("catalog.db")
            val contents = sha(database.readBytes())
            val ranked = matcher.rank(kind, before.references, active) { _, _ -> } as IdentityRanking.Candidates
            assertTrue(ranked.photos.isNotEmpty())
            assertEquals(contents, sha(database.readBytes()))
            val reopened = PhotoSubjects(library, NamedSubjectStore(EncryptedPhotoIndex(directory, alias)))
            assertEquals(NamedSearch.Matches(setOf(anchor.uri)), searchNamed(name, reopened.refresh(active), true))
            val report = JSONObject().put("domain", domain).put("gallery_count", gallery.size)
                .put("query_count", queries.size).put("gallery_scan_ms", scanMs).put("queries", JSONArray(results))
                .put("production_scans", JSONArray(live)).put("named_confirmed_only", true)
            context.cacheDir.resolve("identity-benchmark-$domain.json").writeText(report.toString(2))
        } finally {
            inserted.forEach { resolver.delete(it, null, null) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private suspend fun benchmarkCache(
        domain: String,
        kind: SubjectKind,
        library: PhotoLibrary,
        baseline: IdentityMatcher,
        storage: EncryptedPhotoIndex,
        directory: java.io.File,
        anchor: SelectedPhoto,
        gallery: List<SelectedPhoto>,
    ) {
        val name = "Cache benchmark subject"
        val subjects = PhotoSubjects(library, NamedSubjectStore(storage))
        assertEquals(Confirmation.SAVED, subjects.confirm(anchor, SubjectSelection.New(name, kind, emptyList()), null))
        val photos = listOf(anchor) + gallery
        val named = subjects.refresh(photos)
        val catalog = directory.resolve("catalog.db")
        val cacheSizeBefore = catalog.length()
        val reportFile = context.cacheDir.resolve("identity-cache-benchmark-$domain.json")
        val prior = if (InstrumentationRegistry.getArguments().getString("repeatCacheTiming") == "true") {
            JSONObject(reportFile.readText()).also {
                assertEquals(domain, it.getString("domain"))
                assertEquals(gallery.size, it.getInt("gallery_count"))
                assertEquals(3, it.getJSONArray("baseline_ms").length())
            }
        } else null
        fun cachedMatcher() = IdentityMatcher(library, OnnxRegionModels(context.assets), IdentityModels(context.assets), storage)
        suspend fun timed(matcher: IdentityMatcher): Pair<Double, IdentityRanking.Candidates> {
            val start = SystemClock.elapsedRealtimeNanos()
            val ranked = matcher.rank(kind, named.references, photos) { _, _ -> } as IdentityRanking.Candidates
            return elapsed(start) to ranked
        }
        val original = (1..(if (prior == null) 3 else 1)).map {
            timed(baseline).also { (time, _) -> Log.i("IdentityCacheBenchmark", "$domain uncached run $it: $time ms") }
        }
        val cached = cachedMatcher()
        val (coldMs, cold) = timed(cached)
        Log.i("IdentityCacheBenchmark", "$domain encrypted cache build: $coldMs ms")
        assertEquals(IdentityCacheUsage(0, gallery.size, 0, 1), cold.cacheUsage)
        val cacheSizeAfter = catalog.length()
        val encrypted = sha(catalog.readBytes())
        val warm = (1..3).map {
            timed(cached).also { (time, _) -> Log.i("IdentityCacheBenchmark", "$domain repeat run $it: $time ms") }
        }
        val (reopenMs, reopened) = timed(cachedMatcher())
        val expected = original.first().second.photos
        for (candidate in original.map { it.second } + cold + warm.map { it.second } + reopened) {
            assertEquals(expected, candidate.photos)
        }
        for (candidate in warm.map { it.second } + reopened) {
            assertEquals(IdentityCacheUsage(gallery.size, 0, 1, 0), candidate.cacheUsage)
        }
        assertEquals(encrypted, sha(catalog.readBytes()))
        val restored = subjects.refresh(photos)
        assertEquals(named, restored)
        assertEquals(NamedSearch.Matches(setOf(anchor.uri)), searchNamed(name, restored, true))
        assertTrue(expected.isNotEmpty())
        val report = JSONObject().put("domain", domain).put("gallery_count", gallery.size)
            .put("baseline_ms", prior?.getJSONArray("baseline_ms") ?: JSONArray(original.map { it.first }))
            .put("cold_cache_ms", coldMs).put("warm_cache_ms", JSONArray(warm.map { it.first }))
            .put("reopened_matcher_ms", reopenMs)
            .put("reused_gallery", gallery.size).put("reused_references", 1)
            .put("added_encrypted_catalog_bytes", cacheSizeAfter - cacheSizeBefore)
            .put("same_rankings", true).put("named_confirmed_only", true)
        if (prior != null) report.put("validation_uncached_ms", original.single().first)
            .put("previous_warm_cache_ms", prior.getJSONArray("warm_cache_ms"))
        reportFile.writeText(report.toString(2))
    }

    private fun insert(file: String, bytes: ByteArray, inserted: MutableList<Uri>): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${UUID.randomUUID()}-$file")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IdentityBenchmarkTest")
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        inserted += uri
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class BenchmarkImage(val id: String, val group: String, val anchor: Boolean, val uri: Uri)
}
