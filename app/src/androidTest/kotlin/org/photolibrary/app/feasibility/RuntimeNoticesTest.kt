package org.photolibrary.app.feasibility

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.MainActivity
import org.photolibrary.app.R
import org.photolibrary.app.library.RuntimeNotices

@RunWith(AndroidJUnit4::class)
class RuntimeNoticesTest {
    @Test
    fun packagedIndexIsSanitizedAndTextsLoadLazilyWithExactHashes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val opened = mutableListOf<String>()
        val loader = RuntimeNotices { path -> opened += path; context.assets.open(path) }
        val index = loader.index()
        assertEquals(listOf("third-party-runtime/index.json"), opened)
        assertEquals(144, index.componentCount)
        assertEquals(22, index.documents.size)
        assertTrue(index.gaps.any { it.contains("ONNX Runtime 1.26.0") && it.contains("unresolved") })
        val metadata = context.assets.open("third-party-runtime/index.json").bufferedReader().use { it.readText() }
        for (privatePath in listOf("D:\\", "C:\\", "Gradle/caches", "source_archive", "RAUNAK")) {
            assertFalse(privatePath, metadata.contains(privatePath))
        }
        for (document in index.documents) {
            val chunks = loader.text(document)
            assertTrue(chunks.all { it.length <= 4_000 })
            val text = chunks.joinToString("").encodeToByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(text).joinToString("") { "%02x".format(it) }
            assertEquals(document.sha256, hash)
            assertEquals(document.bytes, text.size)
            assertTrue(document.mappings.all { it.sourceUrl.startsWith("https://") && it.artifactHashes.isNotEmpty() })
        }
        val sqlcipher = index.documents.single { it.title.startsWith("SQLCipher") }
        val emptyNotice = index.documents.single { it.bytes == 0 }
        assertTrue(emptyNotice.mappings.isNotEmpty())
        assertTrue(loader.text(emptyNotice).isEmpty())
        assertEquals("09e4af560ce2e3c9c2aa6b564e35947b03db7d1ae345f22a32793ed46542cc14", sqlcipher.sha256)
        assertTrue(loader.text(sqlcipher).joinToString("").contains("Copyright (c) 2008-2023, ZETETIC LLC"))
        assertThrows(IOException::class.java) { loader.text(sqlcipher.copy(sha256 = "0".repeat(64))) }
        assertThrows(IOException::class.java) { RuntimeNotices { throw IOException("Missing fixture") }.text(sqlcipher) }
    }

    @Test
    fun sqlcipherNoticeIsReadableFromSourcesWithoutReplacingModelNotices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val automation = instrumentation.uiAutomation
        fun find(text: String): AccessibilityNodeInfo? {
            val root = automation.rootInActiveWindow ?: return null
            val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.text?.toString()?.contains(text) == true) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
            }
            return null
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (!condition()) {
                assertTrue("Runtime notice workflow timed out", SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(100)
            }
        }
        fun click(text: String) = await {
            find(text)?.let { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                it.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } == true
        }
        try {
            click(context.getString(R.string.library_sources))
            click(context.getString(R.string.runtime_notices_title))
            await { find(context.getString(R.string.runtime_notices_incomplete)) != null }
            click("SQLCipher Android 4.20.0")
            await { find("Copyright (c) 2008-2023, ZETETIC LLC") != null }
            await { find("net.zetetic:sqlcipher-android:4.20.0") != null }
            click(context.getString(R.string.runtime_notices_back))
            click("Tink Java 1.24.0")
            await { find("com.google.crypto.tink:tink-android:1.24.0") != null ||
                find("com.google.crypto.tink:tink:1.24.0") != null }
            assertTrue(find("Copyright (c) 2008-2023, ZETETIC LLC") == null)
            await { find("Apache License") != null }
            click(context.getString(R.string.library_done))
            click(context.getString(R.string.library_sources))
            await { find(context.getString(R.string.model_notices_title)) != null }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
