package org.photolibrary.app.models

import android.content.res.AssetManager
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject

internal data class ModelPackFile(
    val path: String,
    val sha256: String,
    val bytes: Long,
)

internal data class ModelPackCatalog(
    val schema: Int,
    val id: String,
    val version: String,
    val files: List<ModelPackFile>,
) {
    val byPath = files.associateBy(ModelPackFile::path)

    fun matchesManifest(input: InputStream): Boolean = runCatching {
        val root = JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val received = from(root)
        received == this
    }.getOrDefault(false)

    companion object {
        fun from(assets: AssetManager): ModelPackCatalog = assets.open("model-packs/catalog.json").use { read(it) }

        fun read(input: InputStream): ModelPackCatalog {
            val bytes = input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 1_048_576)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return from(JSONObject(String(bytes, Charsets.UTF_8)))
        }

        private fun from(root: JSONObject): ModelPackCatalog {
            val schema = root.getInt("schema")
            val id = root.getString("id")
            val version = root.getString("version")
            require(schema == 1 && id == "specialist-models" && version.matches(Regex("[0-9a-f]{64}")))
            val array = root.getJSONArray("files")
            require(array.length() in 1..256)
            val files = List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val path = item.getString("path")
                val sha256 = item.getString("sha256")
                val length = item.getLong("bytes")
                require(isSafeModelPath(path))
                require(sha256.matches(Regex("[0-9a-f]{64}")) && length in 0..1_073_741_824L)
                ModelPackFile(path, sha256, length)
            }.sortedBy(ModelPackFile::path)
            require(files.map(ModelPackFile::path).distinct().size == files.size)
            val payload = files.joinToString("|") { "${it.path}:${it.sha256}:${it.bytes}" }
            val calculated = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(calculated == version)
            return ModelPackCatalog(schema, id, version, files)
        }

        private fun isSafeModelPath(path: String): Boolean {
            if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains(':')) return false
            val parts = path.split('/')
            return parts.size >= 2 && parts.none { it.isBlank() || it == "." || it == ".." } &&
                parts.first() in setOf("detection", "identity", "garment")
        }
    }
}
