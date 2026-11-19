package org.photolibrary.app.models

import android.content.res.AssetManager
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal class ModelFileStore(
    private val assets: AssetManager,
    private val root: File?,
    private val catalog: ModelPackCatalog,
    private val verifiedFiles: ConcurrentHashMap<String, Pair<Long, Long>> = ConcurrentHashMap(),
) {
    private val packLock = ReentrantReadWriteLock()
    @Volatile private var activeDirectory: String? = readActiveDirectory()

    fun available(path: String): Boolean = packLock.read { installedFile(path) != null || packagedFile(path) }

    fun list(directory: String): Set<String> = packLock.read {
        val packaged = assets.list(directory).orEmpty().toSet()
        val catalogEntries = catalog.files.mapNotNull { record ->
            record.path.removePrefix("$directory/").takeIf { it != record.path && '/' !in it }
        }.toSet()
        return (packaged + catalogEntries).filterTo(mutableSetOf()) { available("$directory/$it") }
    }

    fun open(path: String): InputStream = packLock.read {
        val file = installedFile(path)
        if (file != null) {
            verify(file, catalog.byPath.getValue(path))
            return@read FileInputStream(file)
        }
        assets.open(path)
    }

    fun map(path: String): ByteBuffer = packLock.read {
        val file = installedFile(path)
        if (file != null) {
            verify(file, catalog.byPath.getValue(path))
            return@read FileChannel.open(file.toPath()).use { channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()) }
        }
        assets.openFd(path).use { descriptor ->
            descriptor.createInputStream().use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
            }
        }
    }

    fun mapVerified(path: String): ByteBuffer = packLock.read {
        val expected = catalog.byPath[path] ?: throw IOException("Model path is not present in the trusted catalog")
        val bytes = map(path)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes.duplicate())
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != expected.sha256) throw IOException("Model digest does not match its trusted catalog")
        bytes
    }

    fun activate(directory: String) = packLock.write {
        require(isValidDirectoryToken(directory))
        check(root != null)
        activeDirectory = directory
    }

    fun deactivate() = packLock.write {
        activeDirectory = null
    }

    fun installed(): Boolean = packLock.read { activeDirectory != null && catalog.files.all { installedFile(it.path) != null } }

    fun validInstalled(): Boolean = packLock.read {
        if (!installed()) return@read false
        catalog.files.all { record ->
            val file = installedFile(record.path) ?: return@all false
            try {
                verify(file, record)
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    fun activeDirectory(): String? = packLock.read { activeDirectory }

    fun sha256(path: String): String = catalog.byPath[path]?.sha256
        ?: throw IOException("Model path is not present in the trusted catalog")

    fun forDirectory(directory: String): ModelFileStore = ModelFileStore(assets, root, catalog, verifiedFiles).apply { activate(directory) }

    fun <T> withExclusivePackAccess(action: () -> T): T = packLock.write(action)

    fun verify(file: File, expected: ModelPackFile) {
        val metadata = file.lastModified() to file.length()
        val key = verificationKey(file, expected)
        if (verifiedFiles[key] == metadata) return
        if (metadata.second != expected.bytes) throw IOException("Installed model size does not match its catalog")
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(FileInputStream(file), digest).use { stream ->
            val buffer = ByteArray(65_536)
            while (stream.read(buffer) >= 0) Unit
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != expected.sha256) throw IOException("Installed model digest does not match its catalog")
        verifiedFiles[key] = metadata
    }

    fun markVerified(file: File, record: ModelPackFile) {
        verifiedFiles[verificationKey(file, record)] = file.lastModified() to file.length()
    }

    private fun verificationKey(file: File, record: ModelPackFile): String = "${file.canonicalPath}:${record.path}"

    private fun installedFile(path: String): File? {
        val root = root ?: return null
        val directory = activeDirectory ?: return null
        val expected = catalog.byPath[path] ?: return null
        val activeRoot = File(File(root, catalog.id), directory).canonicalFile
        val candidate = File(activeRoot, path).canonicalFile
        if (!candidate.toPath().startsWith(activeRoot.toPath())) return null
        return candidate.takeIf { it.isFile && it.length() == expected.bytes }
    }

    private fun packagedFile(path: String): Boolean {
        val separator = path.lastIndexOf('/')
        return separator > 0 && assets.list(path.substring(0, separator)).orEmpty().contains(path.substring(separator + 1))
    }

    private fun readActiveDirectory(): String? {
        val root = root ?: return null
        val marker = File(root, "${catalog.id}.active")
        return marker.takeIf { it.isFile }?.readText()?.trim()?.takeIf(::isValidDirectoryToken)
    }

    private fun isValidDirectoryToken(value: String): Boolean = value.matches(Regex("${catalog.version}-[0-9a-f-]{36}"))

    companion object {
        fun bundled(assets: AssetManager) = ModelFileStore(assets, null, ModelPackCatalog.from(assets))
    }
}
