package org.photolibrary.app.models

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.zip.ZipInputStream

internal class ModelPackInstaller(
    private val root: File,
    private val catalog: ModelPackCatalog,
    private val files: ModelFileStore,
    private val validate: (ModelFileStore) -> Unit,
) {
    private val operationLock = ReentrantLock()

    fun install(input: InputStream): String = operationLock.withLock { installLocked(input) }

    fun cleanupInterrupted() = operationLock.withLock {
        files.withExclusivePackAccess {
            File(root, catalog.id).listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".staging-") }
                .forEach { it.deleteRecursively() }
            root.listFiles().orEmpty().filter { it.name.startsWith("${catalog.id}.active.") && it.name.endsWith(".tmp") }
                .forEach { it.delete() }
        }
    }

    private fun installLocked(input: InputStream): String {
        val packRoot = File(root, catalog.id)
        val installId = UUID.randomUUID().toString()
        val targetDirectory = "${catalog.version}-$installId"
        val target = File(packRoot, targetDirectory)
        val staging = File(packRoot, ".staging-$installId")
        val expectedDirectories = catalog.files.flatMap { record ->
            val parts = record.path.split('/')
            (1 until parts.size).map { parts.take(it).joinToString("/") }
        }.toSet()
        val seen = mutableSetOf<String>()
        val seenDirectories = mutableSetOf<String>()
        var manifestSeen = false
        val totalLimit = catalog.files.sumOf(ModelPackFile::bytes)
        var totalWritten = 0L
        val diskRoot = root.parentFile ?: root
        if (!packRoot.mkdirs() && !packRoot.isDirectory) throw IOException("Could not prepare local model-pack storage")
        packRoot.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".staging-") }
            .forEach { it.deleteRecursively() }
        if (diskRoot.usableSpace < totalLimit) throw IOException("Not enough free space to install the local model pack")
        if (!staging.mkdirs()) throw IOException("Could not prepare local model-pack storage")
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = safeName(entry.name, entry.isDirectory)
                    if (entry.isDirectory) {
                        if (name !in expectedDirectories || !seenDirectories.add(name)) throw IOException("Model pack contains an unexpected directory")
                        continue
                    }
                    if (name == "manifest.json") {
                        if (manifestSeen) throw IOException("Model pack repeats its manifest")
                        val bytes = readBounded(zip, 1_048_576)
                        if (!catalog.matchesManifest(bytes.inputStream())) throw IOException("Model pack does not match this app's trusted catalog")
                        manifestSeen = true
                        continue
                    }
                    val expected = catalog.byPath[name] ?: throw IOException("Model pack contains an unexpected file")
                    if (!seen.add(name)) throw IOException("Model pack repeats a file")
                    val destination = File(staging, name).canonicalFile
                    if (!destination.toPath().startsWith(staging.canonicalFile.toPath())) throw IOException("Model pack path is invalid")
                    destination.parentFile.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            written += count
                            totalWritten += count
                            if (written > expected.bytes || totalWritten > totalLimit) throw IOException("Model pack exceeds its catalog size")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    if (written != expected.bytes) throw IOException("Model pack file size is invalid")
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (actual != expected.sha256) throw IOException("Model pack file digest is invalid")
                    files.markVerified(destination, expected)
                }
            }
            if (!manifestSeen || seen != catalog.byPath.keys) throw IOException("Model pack is incomplete")
            if (files.validInstalled()) {
                staging.deleteRecursively()
            } else {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                validate(files.forDirectory(targetDirectory))
                files.withExclusivePackAccess {
                    val previous = files.activeDirectory()
                    try {
                        activate(targetDirectory)
                        files.activate(targetDirectory)
                    } catch (failure: Exception) {
                        if (previous == null) {
                            Files.deleteIfExists(File(root, "${catalog.id}.active").toPath())
                            files.deactivate()
                        } else {
                            activate(previous)
                            files.activate(previous)
                        }
                        throw failure
                    }
                    packRoot.listFiles().orEmpty().filter { it.isDirectory && it.name != targetDirectory }.forEach { it.deleteRecursively() }
                }
            }
            return catalog.version
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            if (target.exists() && files.activeDirectory() != targetDirectory) target.deleteRecursively()
            if (failure is IOException) throw failure
            if (failure is Exception) throw IOException("Could not install the local model pack", failure)
            throw failure
        }
    }

    fun remove() {
        operationLock.withLock {
            files.withExclusivePackAccess {
                val marker = File(root, "${catalog.id}.active")
                Files.deleteIfExists(marker.toPath())
                root.listFiles().orEmpty().filter { it.name.startsWith("${catalog.id}.active.") && it.name.endsWith(".tmp") }
                    .forEach { it.delete() }
                files.deactivate()
                val directory = File(root, catalog.id)
                if (directory.exists() && (!directory.deleteRecursively() || directory.exists())) {
                    throw IOException("Could not remove local specialist models")
                }
            }
        }
    }

    fun stored(): Boolean = File(root, catalog.id).listFiles().orEmpty().any { it.isDirectory }

    private fun activate(directory: String) {
        root.mkdirs()
        val marker = File(root, "${catalog.id}.active")
        val temporary = File(root, "${catalog.id}.active.${UUID.randomUUID()}.tmp")
        temporary.writeText(directory)
        try {
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    private fun safeName(raw: String, directory: Boolean): String {
        if (raw.isBlank() || raw.startsWith('/') || raw.contains('\\') || raw.contains(':') || raw.contains('\u0000')) {
            throw IOException("Model pack path is invalid")
        }
        val name = if (directory) raw.removeSuffix("/") else raw
        val segments = name.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) throw IOException("Model pack path is invalid")
        return name
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("Model pack manifest exceeds its bound")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

}
