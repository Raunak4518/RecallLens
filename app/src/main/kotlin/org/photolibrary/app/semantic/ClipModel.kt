package org.photolibrary.app.semantic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import org.json.JSONException
import org.json.JSONObject
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoEvidenceUpgrade
import org.photolibrary.app.index.PhotoProcessingException
import org.photolibrary.app.index.PhotoProcessor
import org.photolibrary.app.index.tracePhotoStage

internal class ClipModel(private val assets: AssetManager) : ImageTextEncoder {
    private val detailViews = org.photolibrary.app.BuildConfig.SCENE_DETAIL_VIEWS
    private val cropPolicy = if (detailViews) "center-and-edges-v1" else "center-bilinear-v1"
    private val previousRevision = ModelRevision("clip-vit-b32", "6b7c523703ae26e39be4b03774966f8571c7f523:android-$cropPolicy", 896)
    override val revision = previousRevision.copy(version = "${previousRevision.version}:copy-signature-v1")
    override val evidenceUpgrade = PhotoEvidenceUpgrade<PhotoEmbedding>(previousRevision, ::measureCopySignature)

    private fun measureCopySignature(image: Bitmap, embedding: PhotoEmbedding): PhotoEmbedding =
        tracePhotoStage("photos.index.copySignature") { embedding.withCopySignature(PhotoCopySignature.measure(image)) }
    private val textModelFile = org.photolibrary.app.BuildConfig.CLIP_TEXT_MODEL_FILE
    override val available by lazy { assets.list(FOLDER).orEmpty().toSet().containsAll(listOf(VISION, textModelFile, TOKENIZER, PREPROCESSOR)) }
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val inputs by lazy { ImageInputs(readSpec(PREPROCESSOR)) }
    private val tokenizer by lazy { ClipTokenizer(readSpec(TOKENIZER)) }
    private val textLock = Any()
    private var textSession: OrtSession? = null

    override fun encodeQuery(query: String): PhotoEmbedding = synchronized(textLock) {
        inference {
            val tokens = tracePhotoStage("photos.query.tokenize") { tokenizer.encode(query) }
            val session = textSession ?: session(textModelFile).also { textSession = it }
            OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())).use {
                tracePhotoStage("photos.query.textEncoder") { embed(session, "input_ids", it) }
            }
        }
    }

    override fun prepareTextSearch() = synchronized(textLock) {
        if (textSession == null) encodeQuery("photo")
    }

    override fun releaseTextSession() = synchronized(textLock) {
        textSession?.close()
        textSession = null
    }

    override fun imageProcessor(): PhotoProcessor<PhotoEmbedding> = object : PhotoProcessor<PhotoEmbedding> {
        override val model = revision
        private var vision: OrtSession? = null

        override fun process(image: Bitmap): PhotoEmbedding = inference {
            val session = vision ?: session(VISION).also { vision = it }
            val side = inputs.side.toLong()
            fun encodeAt(position: Float): PhotoEmbedding {
                val pixels = tracePhotoStage("photos.index.clipPixels") { inputs.preprocess(image, position) }
                return OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), longArrayOf(1, 3, side, side)).use {
                    embed(session, "pixel_values", it)
                }
            }
            val scene = measureCopySignature(image, encodeAt(.5f))
            val aspect = maxOf(image.width, image.height).toFloat() / minOf(image.width, image.height)
            if (detailViews && aspect >= 1.8f) scene.withDetails(listOf(encodeAt(0f), encodeAt(1f))) else scene
        }

        override fun close() {
            vision?.close()
        }
    }

    private fun session(name: String): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        assets.openFd("$FOLDER/$name").use { asset ->
            asset.createInputStream().use { stream ->
                val model = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length)
                tracePhotoStage("photos.model.openSession") { environment.createSession(model, options) }
            }
        }
    }

    private fun readSpec(name: String) = assets.open("$FOLDER/$name").bufferedReader().use { JSONObject(it.readText()) }

    private fun embed(session: OrtSession, inputName: String, input: OnnxTensor): PhotoEmbedding =
        session.run(mapOf(inputName to input)).use { result ->
            PhotoEmbedding.normalized((result[0].value as Array<*>).single() as FloatArray)
        }

    private inline fun <T> inference(action: () -> T): T = try {
        action()
    } catch (failure: OrtException) {
        throw PhotoProcessingException(failure)
    } catch (failure: IOException) {
        throw PhotoProcessingException(failure)
    } catch (failure: JSONException) {
        throw PhotoProcessingException(failure)
    } catch (failure: IllegalArgumentException) {
        throw PhotoProcessingException(failure)
    }

    private class ImageInputs(spec: JSONObject) {
        val side = spec.getJSONObject("crop_size").getInt("width")
        private val rescale = spec.getDouble("rescale_factor").toFloat()
        private val means = spec.getJSONArray("image_mean").let { FloatArray(3) { i -> it.getDouble(i).toFloat() } }
        private val deviations = spec.getJSONArray("image_std").let { FloatArray(3) { i -> it.getDouble(i).toFloat() } }

        private val normalized = Array(3) { channel ->
            FloatArray(256) { value -> (value * rescale - means[channel]) / deviations[channel] }
        }

        fun preprocess(image: Bitmap, position: Float): FloatArray {
            val edge = minOf(image.width, image.height)
            val cropped = Bitmap.createBitmap(image, ((image.width - edge) * position).toInt(), ((image.height - edge) * position).toInt(), edge, edge)
            val scaled = Bitmap.createScaledBitmap(cropped, side, side, true)
            val pixels = IntArray(side * side)
            try {
                scaled.getPixels(pixels, 0, side, 0, 0, side, side)
            } finally {
                if (scaled !== cropped) scaled.recycle()
                if (cropped !== image) cropped.recycle()
            }
            val input = FloatArray(3 * pixels.size)
            for ((i, pixel) in pixels.withIndex()) {
                input[i] = normalized[0][Color.red(pixel)]
                input[i + pixels.size] = normalized[1][Color.green(pixel)]
                input[i + 2 * pixels.size] = normalized[2][Color.blue(pixel)]
            }
            return input
        }
    }

    private companion object {
        const val FOLDER = "clip"
        const val VISION = "vision_model_quantized.onnx"
        const val TOKENIZER = "tokenizer.json"
        const val PREPROCESSOR = "preprocessor_config.json"
    }
}
