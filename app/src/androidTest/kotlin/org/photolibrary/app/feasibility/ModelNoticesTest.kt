package org.photolibrary.app.feasibility

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.MainActivity
import org.photolibrary.app.R

@RunWith(AndroidJUnit4::class)
class ModelNoticesTest {
    @Test
    fun bundledUpstreamLicenseIsExactAndReadableFromSources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bytes = context.assets.open("third-party-models/OpenAI-CLIP-MIT.txt").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("987e63b32f6c89ff5160e429458a872ff048e6860b590a3912e938f9da8f14db", hash)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val automation = instrumentation.uiAutomation
        fun find(text: String): AccessibilityNodeInfo? {
            val root = automation.rootInActiveWindow ?: return null
            val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (pending.isNotEmpty()) {
                val item = pending.removeFirst()
                if (item.text?.toString()?.contains(text) == true) return item
                for (index in 0 until item.childCount) item.getChild(index)?.let(pending::add)
            }
            return null
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (!condition()) {
                assertTrue("Model notice workflow timed out", SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(100)
            }
        }
        fun click(text: String) = await {
            find(text)?.let { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                it.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } == true
        }
        try {
            click(context.getString(R.string.library_sources))
            click(context.getString(R.string.model_notices_title))
            click("OpenAI-CLIP-MIT.txt")
            await { find("Copyright (c) 2021 OpenAI") != null }
            click(context.getString(R.string.model_notices_back))
            await { find("Apache-2.0.txt") != null }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
