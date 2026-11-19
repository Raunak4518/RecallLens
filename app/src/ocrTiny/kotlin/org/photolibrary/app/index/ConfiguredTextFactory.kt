package org.photolibrary.app.index

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.engine.OCREngine
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.util.OpenCVUtils
import kotlin.math.ceil
import kotlin.math.floor

private val revision = ModelRevision("paddle-ocr-v6-tiny",
    "193bab7a-9ef676d6:android-sdk-dab3fe35-bgr-v1:det20-box40-unclip14:lines256:characters16384", 1600)

internal fun configuredTextFactory(context: Context) = PhotoProcessorFactory(revision) {
    TinyPhotoText(context.applicationContext)
}

private class TinyPhotoText(private val context: Context) : PhotoProcessor<PhotoText> {
    override val model = revision
    private var engine: OCREngine? = null

    override fun process(image: Bitmap): PhotoText {
        val results = try {
            val active = engine ?: open().also { engine = it }
            active.run(image).results
        } catch (error: OCRError) {
            throw PhotoProcessingException(error)
        } catch (error: org.opencv.core.CvException) {
            throw PhotoProcessingException(error)
        }
        val lines = ArrayList<TextLine>(minOf(256, results.size))
        var remaining = 16_384
        var complete = true
        for (result in results) {
            if (lines.size == 256 || remaining == 0) {
                complete = false
                break
            }
            if (result.text.length > remaining) complete = false
            val value = result.text.take(remaining).trim()
            if (value.isEmpty()) continue
            val points = result.box.points
            val left = floor(points.minOf { it.x }).toInt().coerceIn(0, image.width)
            val top = floor(points.minOf { it.y }).toInt().coerceIn(0, image.height)
            val right = ceil(points.maxOf { it.x }).toInt().coerceIn(0, image.width)
            val bottom = ceil(points.maxOf { it.y }).toInt().coerceIn(0, image.height)
            val bounds = if (left < right && top < bottom) TextBounds(left, top, right, bottom) else null
            lines.add(TextLine(value, "und", bounds))
            remaining -= value.length
        }
        return PhotoText(lines, complete)
    }

    private fun open(): OCREngine {
        if (!OpenCVUtils.init(context)) throw PhotoProcessingException(IllegalStateException("OpenCV unavailable"))
        val options = PaddleOCRConfig(detLimitSideLen = 1600, detLimitType = "max", detMaxSideLimit = 1600,
            detThresh = .2f, detBoxThresh = .4f, detUnclipRatio = 1.4f)
        return OCREngine(context, options, EngineConfig(numThreads = 2), "paddle-mobile/tiny/det.onnx",
            "paddle-mobile/tiny/rec.onnx", "paddle-mobile/tiny/rec.yml")
    }

    override fun close() {
        engine?.release()
        engine = null
    }
}
