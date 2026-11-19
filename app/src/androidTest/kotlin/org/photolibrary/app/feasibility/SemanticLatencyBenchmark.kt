package org.photolibrary.app.feasibility

import android.Manifest
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.MainActivity
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.R
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.library.LibraryViewModel
import org.photolibrary.app.library.SearchMode

@RunWith(AndroidJUnit4::class)
class SemanticLatencyBenchmark {
    @Test
    fun measureRestoredGallery() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val app = instrumentation.targetContext.applicationContext as PhotoLibraryApplication
        check(app.packageName.endsWith(".feasibility"))
        val run = checkNotNull(arguments.getString("latencyRun"))
        check(run.matches(Regex("[a-f0-9]{12}")))
        val phase = arguments.getString("latencyPhase")
        val marker = app.filesDir.resolve("semantic-latency-$run.json")
        when (phase) {
            "seed" -> seed(instrumentation, app, marker, run)
            "measure" -> {
                val sample = checkNotNull(arguments.getString("latencySample"))
                val thinkMs = checkNotNull(arguments.getString("latencyThinkMs")).toLong()
                val queryOrder = checkNotNull(arguments.getString("latencyQueryOrder")).toInt()
                check(sample.matches(Regex("[a-z0-9-]{1,30}")) && thinkMs in setOf(0L, 5_000L) && queryOrder in 0..1)
                measure(instrumentation, app, marker, app.filesDir.resolve("semantic-latency-$run-$sample.json"), thinkMs, queryOrder)
            }
            "cleanup" -> cleanup(app, marker, run)
            else -> error("Expected explicit benchmark phase")
        }
    }

    private suspend fun seed(instrumentation: Instrumentation, app: PhotoLibraryApplication, marker: File, run: String) {
        check(!marker.exists())
        check(app.clip.available)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val priorPause = app.indexing.progress.value.checkpoint.paused
        val rows = JSONArray()
        val setup = JSONObject().put("prior_pause", priorPause).put("fixtures", rows)
        marker.writeText(setup.toString())
        app.indexing.pause(true)
        app.indexScheduler.cancel()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = app.contentResolver
        for (number in 1..5) {
            val asset = "clip-local/photo-${number.toString().padStart(2, '0')}.jpg"
            val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
            val name = "s$run-$number.jpg"
            val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoLibraryLatency/$run")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            rows.put(JSONObject().put("uri", uri.toString()).put("name", name).put("asset", asset)
                .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
            marker.writeText(setup.toString())
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        val selected = fixtureRows(setup).mapTo(linkedSetOf()) { Uri.parse(it.getString("uri")) }
        val inputs = app.library.list().photos.map { IndexPhoto(it.uri, it.sourceRevision) }
        check(inputs.count { it.uri in selected && it.sourceRevision != null } == selected.size)
        app.visualIndex.refresh(inputs, { _, _ -> }, processUris = selected) { _, _ -> }
        assertEquals(selected, app.visualIndex.readCached(inputs, selected).keys)
        marker.writeText(setup.put("complete", true).toString())
    }

    private fun measure(
        instrumentation: Instrumentation,
        app: PhotoLibraryApplication,
        marker: File,
        output: File,
        thinkMs: Long,
        queryOrder: Int,
    ) {
        val setup = JSONObject(marker.readText())
        check(setup.getBoolean("complete"))
        val rows = fixtureRows(setup)
        val selected = rows.map { Uri.parse(it.getString("uri")) }
        val start = now()
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val automation = instrumentation.uiAutomation
            lateinit var model: LibraryViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[LibraryViewModel::class.java] }
            await("editable Content search") { model.state.value.searchMode == SearchMode.CONTENT &&
                automation.rootInActiveWindow?.latencyNode { it.isEditable } != null }
            val editable = now()
            val fixtures = selected.toSet()
            val queries = listOf("a pug wearing a red scarf" to selected.first(), "airplanes parked at an airport" to selected[2])
                .let { if (queryOrder == 0) it else it.reversed() }
            if (thinkMs > 0) SystemClock.sleep(thinkMs)
            fun timeQuery(query: String, expected: Uri): JSONObject {
                val input = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
                val field = checkNotNull(automation.rootInActiveWindow?.latencyNode { it.isEditable })
                val submitted = now()
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
                val indexed = await("search result group") {
                    val current = model.state.value
                    current.query == query && !current.searching && current.resultGroups.related.any { it in fixtures }
                }
                val current = model.state.value
                assertEquals(expected, current.resultGroups.related.first { it in fixtures })
                val firstRelated = current.resultGroups.related.first()
                val first = checkNotNull(current.visiblePhotos.first { it.uri == firstRelated }.name)
                val label = if (current.queryRequirements.isEmpty()) R.string.results_related else R.string.results_partial
                val relatedLabel = app.getString(label)
                val visible = await("visible result label and first card") {
                    val root = automation.rootInActiveWindow ?: return@await false
                    root.latencyNode { node ->
                        node.isVisibleToUser && node.text?.toString()?.contains(first) == true
                    } != null && root.latencyNode { node ->
                        node.isVisibleToUser && node.text?.toString()?.contains(relatedLabel) == true
                    } != null
                }
                return JSONObject().put("query", query).put("submit_to_related_state_ms", indexed - submitted)
                    .put("submit_to_visible_result_ms", visible - submitted).put("activity_start_to_result_ms", visible - start)
                    .put("visual_indexed_at_result", current.visualIndexed).put("related_matches", current.resultGroups.related.size)
            }
            val first = timeQuery(queries[0].first, queries[0].second)
            val second = timeQuery(queries[1].first, queries[1].second)
            check(!output.exists())
            output.writeText(JSONObject().put("pid", Process.myPid()).put("think_ms", thinkMs).put("query_order", queryOrder)
                .put("activity_start_to_editable_ms", editable - start).put("fixture_count", selected.size)
                .put("library_photos", model.state.value.photos.size).put("queries", JSONArray().put(first).put(second))
                .put("fixture_sha256", JSONArray(rows.map { it.getString("sha256") })).toString())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private suspend fun cleanup(app: PhotoLibraryApplication, marker: File, run: String) {
        if (!marker.exists()) return
        val setup = JSONObject(marker.readText())
        for (row in fixtureRows(setup)) {
            val expected = row.getString("name")
            check(expected.matches(Regex("s$run-[1-5]\\.jpg")))
            val uri = Uri.parse(row.getString("uri"))
            check(uri.authority == "media")
            val display = MediaStore.Images.Media.DISPLAY_NAME
            val name = app.contentResolver.query(uri, arrayOf(display), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (name == expected) app.contentResolver.delete(uri, null, null)
        }
        val remaining = app.library.list().photos.map { IndexPhoto(it.uri, it.sourceRevision) }
        app.visualIndex.currentPhotos(remaining)
        app.indexing.pause(setup.getBoolean("prior_pause"))
        check(marker.delete())
    }

    private fun fixtureRows(setup: JSONObject): List<JSONObject> = setup.getJSONArray("fixtures").let { rows ->
        List(rows.length()) { rows.getJSONObject(it) }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun await(label: String, condition: () -> Boolean): Long {
        val deadline = now() + 30_000
        while (!condition()) {
            assertTrue("Timed out while waiting for $label", now() < deadline)
            SystemClock.sleep(30)
        }
        return now()
    }
}

private fun AccessibilityNodeInfo.latencyNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(this@latencyNode) }
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (predicate(current)) return current
        for (index in 0 until current.childCount) current.getChild(index)?.let(pending::add)
    }
    return null
}
