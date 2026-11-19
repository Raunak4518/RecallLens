package org.photolibrary.app.feasibility

import android.graphics.ImageDecoder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.detection.GarmentModels
import org.photolibrary.app.detection.GarmentOverlapPolicy
import org.photolibrary.app.detection.LitertGarmentParser
import org.photolibrary.app.detection.PersonGarmentEvidence
import org.photolibrary.app.detection.area
import org.photolibrary.app.detection.describePersonGarment
import org.photolibrary.app.detection.intersection
import org.photolibrary.app.library.PHOTO_REFERENCE_EDGE
import org.photolibrary.app.library.decodePhoto
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind

@RunWith(AndroidJUnit4::class)
class GarmentModelEvaluationTest {
    @Test
    fun evaluateRealPersonGarmentPipeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("garmentEvaluation") == "true")
        val context = instrumentation.targetContext
        val app = context.applicationContext as PhotoLibraryApplication
        val fixture = arguments.getString("garmentFixture") ?: "garment-evaluation"
        require(fixture.matches(Regex("[a-zA-Z0-9-]+")))
        val overlapPolicy = GarmentOverlapPolicy.valueOf(arguments.getString("garmentOverlapPolicy") ?: "WITHHOLD")
        val root = File(context.filesDir, fixture)
        val manifestBytes = File(root, "manifest.json").readBytes()
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        assertEquals(LitertGarmentParser.SHA256, manifest.getString("parser_sha256"))
        assertEquals(PHOTO_REFERENCE_EDGE, manifest.getInt("max_image_edge"))
        val reports = JSONArray()
        val setupStarted = SystemClock.elapsedRealtime()
        GarmentModels(context.assets).open().use { parser ->
            GarmentModels(context.assets).openPeople().use { peopleModel ->
                app.regionModels.open().use { facesModel ->
                    val setupMs = SystemClock.elapsedRealtime() - setupStarted
                    val photos = manifest.getJSONArray("images")
                    assertTrue(photos.length() > 0)
                    for (index in 0 until photos.length()) {
                        val photo = photos.getJSONObject(index)
                        val key = photo.getString("image_id")
                        val source = File(root, "$key.jpg")
                        assertEquals(photo.getString("sha256"), digest(source.readBytes()))
                        val decoded = SystemClock.elapsedRealtime()
                        val bitmap = decodePhoto(ImageDecoder.createSource(source), PHOTO_REFERENCE_EDGE, software = true)
                        val decodeMs = SystemClock.elapsedRealtime() - decoded
                        try {
                            val started = SystemClock.elapsedRealtime()
                            val people = peopleModel.detect(bitmap)
                            val faces = facesModel.detect(bitmap, SubjectKind.PERSON).map { it.region }
                            val evidence = faces.map { face -> describePersonGarment(bitmap, face, faces, people, parser, overlapPolicy) }
                            val elapsed = SystemClock.elapsedRealtime() - started
                            val annotations = photo.getJSONArray("annotations")
                            val expected = (0 until annotations.length()).map { annotations.getJSONObject(it) }
                            val assignments = assignFaces(expected.map { it.getJSONArray("face").region() }, faces)
                            val outcomes = JSONArray()
                            for ((position, annotation) in expected.withIndex()) {
                                val candidateIndex = assignments[position]
                                val candidate = candidateIndex?.let(evidence::get)
                                val colors = annotation.optJSONArray("colors") ?: JSONArray(listOf(annotation.getString("color")))
                                val labels = (0 until colors.length()).map { colors.getString(it) }
                                val result = JSONObject().put("annotation", position).put("expected_colors", colors)
                                    .put("face", annotation.getJSONArray("face")).put("detected_face", candidateIndex ?: JSONObject.NULL)
                                when (candidate) {
                                    is PersonGarmentEvidence.Found -> {
                                        val dominant = candidate.colorFractions.maxByOrNull { it.value }
                                        result.put("state", "found").put("dominant_color", dominant?.key?.name)
                                            .put("correct", dominant?.key?.name in labels)
                                            .put("colors", JSONObject(candidate.colorFractions.mapKeys { it.key.name }))
                                            .put("sampled_pixels", candidate.sampledPixels)
                                    }
                                    is PersonGarmentEvidence.Missing -> result.put("state", "missing").put("reason", candidate.reason.name)
                                    null -> result.put("state", "missing").put("reason", "NO_FACE")
                                }
                                outcomes.put(result)
                            }
                            reports.put(JSONObject().put("image_id", key).put("source_sha256", photo.getString("sha256"))
                                .put("scenario", photo.optString("scenario", "smoke")).put("negative_control", photo.optBoolean("negative_control"))
                                .put("decode_ms", decodeMs).put("elapsed_ms", elapsed).put("people", people.size).put("faces", faces.size)
                                .put("outcomes", outcomes).put("unassigned_faces", JSONArray(faces.indices.filter { it !in assignments.values }))
                                .put("detected_evidence", JSONArray(evidence.map(::record))))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    val modelHashes = JSONObject()
                    for (path in listOf("detection/yunet.onnx", "detection/yolox_nano.onnx", "garment/selfie_multiclass_256x256.tflite")) {
                        modelHashes.put(path, context.assets.open(path).use { stream ->
                            val hash = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(65_536)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                hash.update(buffer, 0, read)
                            }
                            hash.digest().hex()
                        })
                    }
                    val dependencies = app.garmentAnalysis.dependencies.let {
                        if (overlapPolicy == GarmentOverlapPolicy.WITHHOLD) it else it.copy(
                            colorPolicy = "hsv-v1:ownership0.9:upper0.55:exclude-other-box-pixels-v1:minimum16-area0.01")
                    }
                    val report = JSONObject().put("manifest_sha256", digest(manifestBytes)).put("parser_model", parser.model.toString())
                        .put("overlap_policy", overlapPolicy.name)
                        .put("dependencies", dependencies.signature).put("model_sha256", modelHashes)
                        .put("setup_ms", setupMs).put("max_image_edge", PHOTO_REFERENCE_EDGE)
                        .put("scope", "Per-image face/person/clothing pipeline with provisional annotations; no cross-photo identity claim")
                        .put("images", reports)
                    File(context.filesDir, "$fixture-results.json").writeText(report.toString())
                }
            }
        }
    }
}

private fun assignFaces(expected: List<PhotoRegion>, detected: List<PhotoRegion>): Map<Int, Int> {
    val pairs = expected.indices.flatMap { one -> detected.indices.map { two -> Triple(one, two, iou(expected[one], detected[two])) } }
        .filter { it.third >= .25f }.sortedByDescending { it.third }
    return buildMap {
        val assigned = hashSetOf<Int>()
        for ((one, two) in pairs) {
            if (one !in keys && two !in assigned) {
                put(one, two)
                assigned.add(two)
            }
        }
    }
}

private fun record(evidence: PersonGarmentEvidence): JSONObject = when (evidence) {
    is PersonGarmentEvidence.Found -> JSONObject().put("face", evidence.face.json()).put("person", evidence.person.json())
        .put("state", "found").put("colors", JSONObject(evidence.colorFractions.mapKeys { it.key.name }))
    is PersonGarmentEvidence.Missing -> JSONObject().put("face", evidence.face.json()).put("state", "missing").put("reason", evidence.reason.name)
}

private fun JSONArray.region() = PhotoRegion(getDouble(0).toFloat(), getDouble(1).toFloat(), getDouble(2).toFloat(), getDouble(3).toFloat())
private fun PhotoRegion.json() = JSONArray(listOf(left, top, right, bottom))
private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun iou(first: PhotoRegion, second: PhotoRegion): Float {
    val common = intersection(first, second)
    return common / (first.area() + second.area() - common)
}
