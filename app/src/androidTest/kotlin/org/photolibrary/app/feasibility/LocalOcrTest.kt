package org.photolibrary.app.feasibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.normalizePhotoText

@RunWith(AndroidJUnit4::class)
class LocalOcrTest {
    @Test
    fun bundledOcrReadsSyntheticTextAndLeavesBlankImageEmpty() {
        val bitmap = Bitmap.createBitmap(960, 320, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText(
            "LIBRARY 4821",
            48f,
            160f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 84f
            },
        )
        try {
            LocalTextIndex.recognizer().use { recognizer ->
                val start = SystemClock.elapsedRealtime()
                val recognized = recognizer.process(bitmap)
                Log.i("PhotoLibraryG0", "Bundled OCR synthetic first result: ${SystemClock.elapsedRealtime() - start} ms")
                assertEquals("LIBRARY 4821", recognized.lines.single().text)
                assertTrue(recognized.lines.single().bounds != null)
                assertTrue(recognized.matches(normalizePhotoText("library 4821")))
                bitmap.eraseColor(Color.WHITE)
                assertTrue(recognizer.process(bitmap).lines.isEmpty())
            }
        } finally {
            bitmap.recycle()
        }
    }
}
