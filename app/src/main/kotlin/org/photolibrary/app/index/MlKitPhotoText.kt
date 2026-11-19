package org.photolibrary.app.index

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutionException

internal class MlKitPhotoText(
    override val model: ModelRevision,
    private val lineLimit: Int,
    private val characterLimit: Int,
    private val openRecognizer: () -> TextRecognizer = { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) },
) : PhotoProcessor<PhotoText> {
    private var recognizer: TextRecognizer? = null

    init {
        require(lineLimit > 0 && characterLimit > 0)
    }

    override fun process(image: Bitmap): PhotoText {
        val result = try {
            val client = recognizer ?: openRecognizer().also { recognizer = it }
            Tasks.await(client.process(InputImage.fromBitmap(image, 0)))
        } catch (failure: ExecutionException) {
            throw PhotoProcessingException(failure.cause ?: failure)
        } catch (failure: IllegalArgumentException) {
            throw PhotoProcessingException(failure)
        } catch (failure: IllegalStateException) {
            throw PhotoProcessingException(failure)
        }
        val lines = mutableListOf<TextLine>()
        var remaining = characterLimit
        var complete = true
        for (line in result.textBlocks.asSequence().flatMap { it.lines.asSequence() }) {
            if (lines.size >= lineLimit || line.text.length > remaining) complete = false
            if (lines.size >= lineLimit || remaining == 0) break
            val text = line.text.take(remaining).trim()
            if (text.isEmpty()) continue
            val bounds = line.boundingBox?.let { TextBounds(it.left, it.top, it.right, it.bottom) }
            lines += TextLine(text, line.recognizedLanguage, bounds)
            remaining -= text.length
        }
        return PhotoText(lines, complete)
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
    }
}
