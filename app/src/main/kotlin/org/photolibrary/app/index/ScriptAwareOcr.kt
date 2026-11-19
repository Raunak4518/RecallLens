package org.photolibrary.app.index

import android.graphics.Bitmap
import com.google.mlkit.vision.text.TextRecognizer
import org.photolibrary.app.BuildConfig

internal data class ScriptOcrPack(
    val name: String,
    val scripts: Set<Character.UnicodeScript>,
    val recognizer: () -> TextRecognizer,
)

internal fun scriptAwareFactory(primary: PhotoProcessorFactory<PhotoText>): PhotoProcessorFactory<PhotoText> {
    val pack = selectedScriptOcrPack ?: return primary
    val policy = BuildConfig.OCR_SCRIPT_POLICY
    val model = primary.model.copy(
        version = "${primary.model.version}:mlkit-${pack.name}:${BuildConfig.ML_KIT_LATIN_VERSION}:$policy-v1",
    )
    return PhotoProcessorFactory(model) {
        ScriptAwareOcr(model, primary.open(), MlKitPhotoText(model, 256, 16_384, pack.recognizer), pack.scripts, policy == "sparse")
    }
}

internal class ScriptAwareOcr(
    override val model: ModelRevision,
    private val primary: PhotoProcessor<PhotoText>,
    private val additional: PhotoProcessor<PhotoText>,
    private val scripts: Set<Character.UnicodeScript>,
    private val sparseOnly: Boolean,
) : PhotoProcessor<PhotoText> {
    override fun process(image: Bitmap): PhotoText {
        val first = primary.process(image)
        if (sparseOnly && first.lines.count { line -> line.text.count(Char::isLetterOrDigit) >= 3 } >= 3) {
            return first
        }
        val second = try {
            tracePhotoStage("photos.index.scriptOcr") { additional.process(image) }
        } catch (_: PhotoProcessingException) {
            return PhotoText(first.lines, complete = false)
        }
        return mergeScriptEvidence(first, second, scripts)
    }

    override fun close() {
        try {
            additional.close()
        } finally {
            primary.close()
        }
    }
}

internal fun mergeScriptEvidence(
    primary: PhotoText,
    additional: PhotoText,
    scripts: Set<Character.UnicodeScript>,
): PhotoText {
    val result = primary.lines.toMutableList()
    val seen = primary.lines.mapTo(hashSetOf()) { normalizePhotoText(it.text) to it.bounds }
    var characters = result.sumOf { it.text.length }
    var complete = primary.complete && additional.complete
    for (line in additional.lines) {
        if (line.text.codePoints().noneMatch { Character.UnicodeScript.of(it) in scripts }) continue
        if (!seen.add(normalizePhotoText(line.text) to line.bounds)) continue
        if (result.size >= 256 || characters + line.text.length > 16_384) {
            complete = false
            continue
        }
        result.add(line)
        characters += line.text.length
    }
    return PhotoText(result, complete)
}
