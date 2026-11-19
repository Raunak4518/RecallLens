package org.photolibrary.app.detection

import android.content.res.AssetManager
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoProcessingException
import org.photolibrary.app.models.ModelFileStore
import org.tensorflow.lite.Interpreter

internal class GarmentModels(private val modelFiles: ModelFileStore) {
    constructor(assets: AssetManager) : this(ModelFileStore.bundled(assets))

    val available get() = modelFiles.available(UPPER_CLOTHES_MODEL)
    val revision get() = GarmentModelRevisions.upperClothes(modelFiles.sha256(UPPER_CLOTHES_MODEL))
    val personDetectorRevision get() = GarmentModelRevisions.personDetector(modelFiles.sha256(PERSON_MODEL))

    fun open(): GarmentParser = try {
        LitertGarmentParser(modelFiles.mapVerified(UPPER_CLOTHES_MODEL), revision)
    } catch (error: java.io.IOException) {
        throw PhotoProcessingException(error)
    }

    fun openPeople(): OnnxPersonDetector = try {
        OnnxPersonDetector(modelFiles.mapVerified(PERSON_MODEL), personDetectorRevision)
    } catch (error: java.io.IOException) {
        throw PhotoProcessingException(error)
    }

    private companion object {
        const val UPPER_CLOTHES_MODEL = "garment/selfie_multiclass_256x256.tflite"
        const val PERSON_MODEL = "detection/yolox_nano.onnx"
    }
}

internal class LitertGarmentParser(bytes: ByteBuffer, override val model: ModelRevision) : GarmentParser {
    private val input = ByteBuffer.allocateDirect(256 * 256 * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(256 * 256 * 6 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val interpreter: Interpreter

    init {
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
}
