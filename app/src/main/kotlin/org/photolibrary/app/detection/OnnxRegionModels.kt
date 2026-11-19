package org.photolibrary.app.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.graphics.Bitmap
import java.io.IOException
import java.nio.FloatBuffer
import org.photolibrary.app.index.PhotoProcessingException
import org.photolibrary.app.models.ModelFileStore
import org.photolibrary.app.subjects.SubjectKind

internal class OnnxRegionModels(private val modelFiles: ModelFileStore) {
    constructor(assets: AssetManager) : this(ModelFileStore.bundled(assets))

    val available get() = modelFiles.list(FOLDER).containsAll(listOf(OBJECTS, FACES, LABELS))
    val revision get() = listOf(FACES, OBJECTS, LABELS).joinToString(":") { modelFiles.sha256("$FOLDER/$it") }
    val faceRevision get() = GarmentModelRevisions.faceDetector(modelFiles.sha256("$FOLDER/$FACES"))
    private val environment by lazy { OrtEnvironment.getEnvironment() }

    fun open(): RegionDetector = inference {
        val labels = modelFiles.open("$FOLDER/$LABELS").bufferedReader().use { it.readLines() }
        require(labels.size == 80)
        val objects = session(OBJECTS)
        try {
            ModelPair(labels, objects, session(FACES))
        } catch (error: Exception) {
            objects.close()
            throw error
        }
    }

    private inner class ModelPair(
        private val labels: List<String>,
        private val objects: OrtSession,
        private val faces: OrtSession,
    ) : RegionDetector {
        override fun detect(image: Bitmap, kind: SubjectKind?): List<DetectedRegion> = inference {
            val objectRegions = if (kind == SubjectKind.PERSON) emptyList() else run(objects, DetectorInput(image, 416, 114f)) { result, input ->
                decodeObjects(result.rows("output"), input, labels)
            }
            val faceRegions = if (kind != null && kind != SubjectKind.PERSON) emptyList()
                else run(faces, DetectorInput(image, 640, 0f), FACE_OUTPUTS, ::decodeFaces)
            faceRegions + objectRegions
        }

        override fun close() {
            try {
                faces.close()
            } finally {
                objects.close()
            }
        }
    }

    private fun <T> run(
        session: OrtSession,
        input: DetectorInput,
        outputs: Set<String> = session.outputNames,
        decode: (OrtSession.Result, DetectorInput) -> T,
    ): T {
        val side = input.side.toLong()
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.channels), longArrayOf(1, 3, side, side)).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor), outputs).use { decode(it, input) }
        }
    }

    private fun session(name: String): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(modelFiles.mapVerified("$FOLDER/$name"), options)
    }

    private companion object {
        const val FOLDER = "detection"
        const val OBJECTS = "yolox_nano.onnx"
        const val FACES = "yunet.onnx"
        const val LABELS = "coco-labels.txt"
        val FACE_OUTPUTS = STRIDES.flatMap { listOf("cls_$it", "obj_$it", "bbox_$it", "kps_$it") }.toSet()
    }
}

private val STRIDES = listOf(8, 16, 32)

private fun decodeObjects(
    rows: Array<FloatArray>,
    input: DetectorInput,
    labels: List<String>,
): List<DetectedRegion> {
    var start = 0
    val detections = buildList {
        for (stride in STRIDES) {
            val columns = input.side / stride
            for (cell in 0 until columns * columns) {
                val row = rows[start + cell]
                if (row[4] < .35f) continue
                val category = labels.indices.maxByOrNull { row[5 + it] } ?: continue
                val confidence = row[4] * row[5 + category]
                val label = labels[category]
                if (!(confidence >= .35f) || label == "person") continue
                val region = input.box(cell, stride, row) ?: continue
                val kind = if (label == "tie") SubjectKind.CLOTHING else SubjectKind.OBJECT
                add(DetectedRegion(label, kind, region, confidence))
            }
            start += columns * columns
        }
    }
    return suppressOverlaps(detections, overlap = .45f, limit = 8)
}

private fun decodeFaces(result: OrtSession.Result, input: DetectorInput): List<DetectedRegion> {
    val faces = buildList {
        for (stride in STRIDES) {
            val classes = result.rows("cls_$stride")
            val objects = result.rows("obj_$stride")
            val boxes = result.rows("bbox_$stride")
            val landmarks = result.rows("kps_$stride")
            for (cell in boxes.indices) {
                val confidence = kotlin.math.sqrt(classes[cell][0].coerceIn(0f, 1f) * objects[cell][0].coerceIn(0f, 1f))
                if (!(confidence >= .85f)) continue
                val region = input.box(cell, stride, boxes[cell]) ?: continue
                add(DetectedRegion("face", SubjectKind.PERSON, region, confidence, input.landmarks(cell, stride, landmarks[cell])))
            }
        }
    }
    return suppressOverlaps(faces, overlap = .30f, limit = 12)
}

@Suppress("UNCHECKED_CAST")
private fun OrtSession.Result.rows(output: String): Array<FloatArray> =
    (get(output).get().value as Array<*>).single() as Array<FloatArray>

private inline fun <T> inference(action: () -> T): T = try {
    action()
} catch (error: OrtException) {
    throw PhotoProcessingException(error)
} catch (error: IOException) {
    throw PhotoProcessingException(error)
} catch (error: IllegalArgumentException) {
    throw PhotoProcessingException(error)
}
