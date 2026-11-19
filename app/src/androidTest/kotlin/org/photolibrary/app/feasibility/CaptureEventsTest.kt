package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.CaptureEvents
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference

@RunWith(AndroidJUnit4::class)
class CaptureEventsTest {
    @Test
    fun datesAndVisualSuggestionsComposeWithoutAssigningImagesAnEventYear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = context.assets.open("search/events.json").bufferedReader().use { CaptureEvents.read(it.readText()) }
        val selected = checkNotNull(catalog.resolve("Deepavali pics from 2025"))
        assertTrue(selected.hasCalendarDates)
        val near = photo("near", "2025-10-22T16:00:00Z")
        val outside = photo("outside", "2025-10-22T20:00:00Z")
        val forwarded = photo("forwarded", null)
        val next = photo("next", "2026-11-08T12:00:00Z")
        val gallery = listOf(near, outside, forwarded, next)

        val results = selected.groups(gallery, listOf(forwarded.uri, near.uri, outside.uri))
        assertEquals(listOf(near.uri), results.nearEventDate)
        assertEquals(listOf(forwarded.uri, outside.uri), results.related)
        assertTrue(results.confirmed.isEmpty())
        val allYears = checkNotNull(catalog.resolve("Diwali pics"))
        assertEquals(setOf(near.uri, next.uri), allYears.groups(gallery).nearEventDate.toSet())
        assertFalse(checkNotNull(catalog.resolve("Diwali 2011")).hasCalendarDates)
        assertNull(catalog.resolve("dog wearing a Diwali costume"))
        val named = NamedLibrary(subjects = listOf(NamedSubject(1, "My car", SubjectKind.OBJECT, emptyList())),
            references = listOf(SubjectReference(1, near.uri, "reference", null)))
        val personal = checkNotNull(catalog.resolve("my car at Diwali 2025", named))
        val scoped = personal.groups(gallery, listOf(forwarded.uri, near.uri, outside.uri))
        assertEquals(listOf(near.uri), scoped.nearEventDate)
        assertTrue(scoped.related.isEmpty())
    }

    private fun photo(name: String, date: String?) = SelectedPhoto(Uri.parse("content://test/$name"), name,
        PhotoOrigin.GALLERY, capturedAtMillis = date?.let { Instant.parse(it).toEpochMilli() })
}
