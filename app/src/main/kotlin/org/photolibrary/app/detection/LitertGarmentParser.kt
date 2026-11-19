package org.photolibrary.app.detection

import android.content.res.AssetManager
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoProcessingException
import org.tensorflow.lite.Interpreter

internal class GarmentModels(private val assets: AssetManager) {
    val available get() = assets.list("garment").orEmpty().contains("selfie_multiclass_256x256.tflite")
    val revision get() = LitertGarmentParser.MODEL

    fun open(): GarmentParser = try {
        assets.openFd("garment/selfie_multiclass_256x256.tflite").use { asset ->
            asset.createInputStream().use { stream ->
                val bytes = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length)
                LitertGarmentParser(bytes)
            }
        }
    } catch (error: java.io.IOException) {
        throw PhotoProcessingException(error)
    }

    fun openPeople(): OnnxPersonDetector =
        assets.openFd("detection/yolox_nano.onnx").use { asset ->
            asset.createInputStream().use { stream ->
                OnnxPersonDetector(stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length))
            }
        }
}

internal class LitertGarmentParser(bytes: ByteBuffer) : GarmentParser {
    override val model = MODEL
    private val input = ByteBuffer.allocateDirect(256 * 256 * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(256 * 256 * 6 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val interpreter: Interpreter

    init {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes.duplicate())
        require(digest.digest().joinToString("") { "%02x".format(it) } == SHA256)
        interpreter = Interpreter(bytes, Interpreter.Options().setNumThreads(2))
        try {
            require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 256, 256, 3)))
            require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 256, 256, 6)))
        } catch (failure: Exception) {
            interpreter.close()
            throw failure
        }
    }

    override fun upperClothes(image: Bitmap): GarmentMask = try {
        val resized = Bitmap.createScaledBitmap(image, 256, 256, true)
        try {
            val pixels = IntArray(256 * 256)
            resized.getPixels(pixels, 0, 256, 0, 0, 256, 256)
            input.rewind()
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16 and 255) - 127.5f) / 127.5f)
                input.putFloat(((pixel shr 8 and 255) - 127.5f) / 127.5f)
                input.putFloat(((pixel and 255) - 127.5f) / 127.5f)
            }
        } finally {
            if (resized !== image) resized.recycle()
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val probabilities = output.asFloatBuffer()
        val mask = BooleanArray(256 * 256) { index ->
            var best = 0
            for (label in 1 until 6) {
                if (probabilities.get(index * 6 + label) > probabilities.get(index * 6 + best)) best = label
            }
            index / 256 < 154 && best == 4 && probabilities.get(index * 6 + 4) >= .6f
        }
        GarmentMask(256, 256, mask)
    } catch (failure: IllegalArgumentException) {
        throw PhotoProcessingException(failure)
    } catch (failure: IllegalStateException) {
        throw PhotoProcessingException(failure)
    }

    override fun close() = interpreter.close()

    companion object {
        const val SHA256 = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0"
        val MODEL = ModelRevision(
            "mediapipe-clothes-upper-region",
            "$SHA256:litert1.4.2:rgb-minus1to1-bilinear256:argmax0.60:upper154of256",
            256,
        )
    }
}
