package org.photolibrary.app.index

import android.net.Uri
import org.photolibrary.app.library.UnavailableSources
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.photolibrary.app.detection.GarmentColor
import org.photolibrary.app.detection.GarmentEvidenceMissing
import org.photolibrary.app.detection.PersonGarmentEvidence
import org.photolibrary.app.detection.area
import org.photolibrary.app.detection.intersection
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind

internal data class GarmentDependencies(
    val faceDetector: ModelRevision,
    val personDetector: ModelRevision,
    val parser: ModelRevision,
    val colorPolicy: String = "hsv-v1:ownership0.9:upper0.55:overlap0.15:minimum16-area0.01",
) {
    val signature: String get() = JSONObject().put("face", faceDetector.json())
        .put("person", personDetector.json()).put("parser", parser.json()).put("color", colorPolicy).toString()
}

internal class GarmentEvidenceStore(private val index: EncryptedPhotoIndex) {
    fun removeWrite(uri: Uri, token: String) = index.withDatabase { db ->
        db.execSQL("DELETE FROM person_garments WHERE uri = ? AND write_token = ?", arrayOf(uri.toString(), token))
    }

    fun pendingFingerprint(uri: Uri, dependencies: GarmentDependencies): String? = index.withDatabase { db ->
        db.rawQuery("SELECT pixel_revision FROM person_garments WHERE uri = ? AND dependencies = ?",
            arrayOf(uri.toString(), dependencies.signature)).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun rebind(
        uri: Uri, revision: String, dependencies: GarmentDependencies, fingerprint: String,
        writeToken: String = java.util.UUID.randomUUID().toString(),
        currentRevision: () -> String?,
    ): Boolean = index.withDatabase { db -> db.transaction {
        if (currentRevision() != revision) return@transaction false
        val changed = db.compileStatement("""
            UPDATE person_garments SET source_revision = ?, write_token = ?
            WHERE uri = ? AND dependencies = ? AND pixel_revision = ?
        """.trimIndent()).use {
            it.bindString(1, revision)
            it.bindString(2, writeToken)
            it.bindString(3, uri.toString())
            it.bindString(4, dependencies.signature)
            it.bindString(5, fingerprint)
            it.executeUpdateDelete()
        }
        if (currentRevision() != revision) {
            db.execSQL("DELETE FROM person_garments WHERE uri = ?", arrayOf(uri.toString()))
            false
        } else changed == 1
    } }

    fun read(uri: Uri, revision: String, dependencies: GarmentDependencies): List<PersonGarmentEvidence>? =
        index.withDatabase { db ->
            createTable(db)
            db.rawQuery(
                "SELECT output FROM person_garments WHERE uri = ? AND source_revision = ? AND dependencies = ?",
                arrayOf(uri.toString(), revision, dependencies.signature),
            ).use { rows -> if (rows.moveToFirst()) decode(rows.getString(0), dependencies.parser) else null }
        }

    fun save(
        uri: Uri,
        revision: String,
        dependencies: GarmentDependencies,
        evidence: List<PersonGarmentEvidence>,
        pixelRevision: String? = null,
        writeToken: String = java.util.UUID.randomUUID().toString(),
        currentRevision: () -> String?,
    ): Boolean {
        require(revision.isNotBlank())
        require(evidence.size <= 12)
        require(evidence.filterIsInstance<PersonGarmentEvidence.Found>().all { it.model == dependencies.parser })
        val payload = encode(evidence)
        require(payload.length <= MAX_PAYLOAD)
        return index.withDatabase { db ->
            createTable(db)
            db.transaction {
                if (currentRevision() != revision) return@transaction false
                db.execSQL(
                    "INSERT OR REPLACE INTO person_garments (uri, source_revision, dependencies, output, pixel_revision, write_token) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(uri.toString(), revision, dependencies.signature, payload, pixelRevision, writeToken),
                )
                if (currentRevision() != revision) {
                    db.execSQL("DELETE FROM person_garments WHERE uri = ?", arrayOf(uri.toString()))
                    false
                } else true
            }
        }
    }

    fun reconcile(accessible: Map<Uri, String>, unavailable: UnavailableSources = UnavailableSources.NONE) = index.withDatabase { db ->
        createTable(db)
        db.transaction {
            val stale = db.rawQuery("SELECT uri, source_revision, pixel_revision FROM person_garments", emptyArray<String>()).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val uri = rows.getString(0)
                        val document = Uri.parse(uri)
                        if (document !in accessible && document !in unavailable ||
                            document in accessible && accessible[document] != rows.getString(1) && rows.isNull(2)) add(uri)
                    }
                }
            }
            stale.forEach { db.execSQL("DELETE FROM person_garments WHERE uri = ?", arrayOf(it)) }
        }
    }

    fun confirmedColorMatches(
        subjectId: Long,
        color: GarmentColor,
        library: NamedLibrary,
        accessible: Map<Uri, String>,
        dependencies: GarmentDependencies,
        minimumFraction: Float = .5f,
    ): Set<Uri> {
        require(minimumFraction > 0f && minimumFraction <= 1f)
        return colorFractions(subjectId, color, library, accessible, dependencies)
            .filterValues { it >= minimumFraction }.keys
    }

    fun colorFractions(
        subjectId: Long, color: GarmentColor, library: NamedLibrary,
        accessible: Map<Uri, String>, dependencies: GarmentDependencies,
    ): Map<Uri, Float> {
        if (library.kindOf(subjectId) != SubjectKind.PERSON) return emptyMap()
        val references = library.references.filter {
            it.subjectId == subjectId && it.region != null && accessible[it.uri] == it.revision
        }.groupBy { it.uri }
        if (references.isEmpty()) return emptyMap()
        return index.withDatabase { db ->
            createTable(db)
            buildMap {
                for (chunk in references.keys.chunked(400)) {
                    val placeholders = List(chunk.size) { "?" }.joinToString(",")
                    db.rawQuery("SELECT uri, source_revision, output FROM person_garments WHERE dependencies = ? AND uri IN ($placeholders)",
                        (listOf(dependencies.signature) + chunk.map(Uri::toString)).toTypedArray()).use { rows ->
                        while (rows.moveToNext()) {
                            val uri = Uri.parse(rows.getString(0))
                            if (accessible[uri] != rows.getString(1)) continue
                            val records = decode(rows.getString(2), dependencies.parser).filterIsInstance<PersonGarmentEvidence.Found>()
                            val fractions = references.getValue(uri).mapNotNull { reference ->
                                val face = checkNotNull(reference.region)
                                val matches = records.filter { record ->
                                    val overlap = intersection(face, record.face)
                                    overlap / (face.area() + record.face.area() - overlap) >= .6f
                                }
                                matches.singleOrNull()?.colorFractions?.getOrDefault(color, 0f)
                            }
                            fractions.maxOrNull()?.let { put(uri, it) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun createTable(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS person_garments (
                    uri TEXT PRIMARY KEY, source_revision TEXT NOT NULL,
                    dependencies TEXT NOT NULL, output TEXT NOT NULL
                ) WITHOUT ROWID""".trimIndent(),
            )
        }
    }
}

private const val MAX_PAYLOAD = 65_536

private fun ModelRevision.json() = JSONArray(listOf(name, version, maxImageEdge))
private fun PhotoRegion.json() = JSONArray(listOf(left, top, right, bottom))
private fun JSONArray.region() = PhotoRegion(getDouble(0).toFloat(), getDouble(1).toFloat(), getDouble(2).toFloat(), getDouble(3).toFloat())

private fun encode(evidence: List<PersonGarmentEvidence>): String = JSONArray(evidence.map { record ->
    when (record) {
        is PersonGarmentEvidence.Missing -> JSONObject().put("face", record.face.json()).put("missing", record.reason.name)
        is PersonGarmentEvidence.Found -> {
            require(record.sampledPixels in 1..262_144 && record.colorFractions.isNotEmpty())
            require(record.colorFractions.values.all { it.isFinite() && it in 0f..1f })
            require(record.colorFractions.values.sum() <= 1.001f)
            JSONObject().put("face", record.face.json()).put("person", record.person.json())
                .put("pixels", record.sampledPixels)
                .put("colors", JSONObject(record.colorFractions.mapKeys { it.key.name }))
        }
    }
}).toString()

private fun decode(payload: String, model: ModelRevision): List<PersonGarmentEvidence> {
    require(payload.length <= MAX_PAYLOAD)
    val array = JSONArray(payload)
    require(array.length() <= 12)
    return List(array.length()) { index ->
        val record = array.getJSONObject(index)
        val face = record.getJSONArray("face").region()
        if (record.has("missing")) PersonGarmentEvidence.Missing(face, GarmentEvidenceMissing.valueOf(record.getString("missing")))
        else {
            val colors = record.getJSONObject("colors")
            val fractions = colors.keys().asSequence().associate { name -> GarmentColor.valueOf(name) to colors.getDouble(name).toFloat() }
            require(fractions.isNotEmpty() && fractions.values.all { it.isFinite() && it in 0f..1f } && fractions.values.sum() <= 1.001f)
            val pixels = record.getInt("pixels")
            require(pixels in 1..262_144)
            PersonGarmentEvidence.Found(face, record.getJSONArray("person").region(), model, pixels, fractions)
        }
    }
}
