package org.photolibrary.app.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.index.ModelRevision

internal class OnnxPersonDetector(modelBytes: ByteBuffer) : AutoCloseable {
    val model get() = MODEL
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(modelBytes, options)
    }

    fun detect(image: Bitmap): List<PhotoRegion> {
        val input = DetectorInput(image, 416, 114f)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.channels), longArrayOf(1, 3, 416, 416)).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val rows = (result[0].value as Array<Array<FloatArray>>).single()
                var start = 0
                val detections = buildList {
                    for (stride in listOf(8, 16, 32)) {
                        val columns = input.side / stride
                        for (cell in 0 until columns * columns) {
                            val row = rows[start + cell]
                            val category = (5 until row.size).maxBy { row[it] }
                            val confidence = row[4] * row[5]
                            if (category != 5 || !(confidence >= .35f)) continue
                            val region = input.box(cell, stride, row) ?: continue
                            add(DetectedRegion("person", SubjectKind.PERSON, region, confidence))
                        }
                        start += columns * columns
                    }
                }
                return suppressOverlaps(detections, .45f, 12).map { it.region }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        val MODEL = ModelRevision("yolox-nano-person",
            "c789161ed43c8269fcd4e67c67eeeb4e80c622da2eb296a20bc6007bd18a0b7d:416-pad114:score0.35:nms0.45", 416)
    }
}
