package org.photolibrary.app.library

import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONObject

internal data class RuntimeNoticeMapping(
    val coordinate: String,
    val artifactHashes: List<String>,
    val sourceUrl: String,
    val sourceEntry: String,
)

internal data class RuntimeNoticeDocument(
    val title: String,
    val path: String,
    val sha256: String,
    val bytes: Int,
    val mappings: List<RuntimeNoticeMapping>,
)

internal data class RuntimeNoticeIndex(
    val scope: String,
    val gaps: List<String>,
    val componentCount: Int,
    val documents: List<RuntimeNoticeDocument>,
)

internal class RuntimeNotices(private val openAsset: (String) -> InputStream) {
    fun index(): RuntimeNoticeIndex {
        val root = JSONObject(read("index.json", 512 * 1024).decodeToString(throwOnInvalidSequence = true))
        require(root.getInt("version") == 1)
        val documents = root.getJSONArray("documents")
        return RuntimeNoticeIndex(root.getString("scope"), root.getJSONArray("gaps").strings(),
            root.getJSONArray("components").length(), List(documents.length()) { index ->
                val entry = documents.getJSONObject(index)
                val hash = entry.getString("sha256")
                require(hash.matches(Regex("[0-9a-f]{64}")))
                val path = entry.getString("path")
                require(path == "texts/$hash.txt" || path == "texts/$hash.json")
                val size = entry.getInt("bytes")
                require(size in 0..4_194_304)
                val mappings = entry.getJSONArray("mappings")
                RuntimeNoticeDocument(entry.getString("title"), path, hash, size, List(mappings.length()) { position ->
                    val mapping = mappings.getJSONObject(position)
                    RuntimeNoticeMapping(mapping.getString("coordinate"), mapping.getJSONArray("artifact_sha256").strings(),
                        mapping.getString("source_url"), mapping.getString("source_entry"))
                })
            })
    }

    fun text(document: RuntimeNoticeDocument): List<String> {
        val bytes = read(document.path, document.bytes)
        if (bytes.size != document.bytes) throw IOException("Notice length does not match its index")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != document.sha256) throw IOException("Notice hash does not match its index")
        return bytes.decodeToString(throwOnInvalidSequence = true).chunked(4_000)
    }

    private fun read(path: String, maximum: Int): ByteArray = openAsset("third-party-runtime/$path").use { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > maximum - bytes.size()) throw IOException("Notice asset exceeds its bound")
            bytes.write(buffer, 0, count)
        }
        bytes.toByteArray()
    }
}

private fun org.json.JSONArray.strings(): List<String> = List(length()) { getString(it) }
