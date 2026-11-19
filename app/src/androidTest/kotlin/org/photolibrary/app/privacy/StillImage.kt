package org.photolibrary.app.privacy

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

internal const val EXPORT_EDGE = 1600
internal const val MAX_SOURCE_BYTES = 24 * 1024 * 1024
internal const val MAX_EXPORT_BYTES = 12 * 1024 * 1024

private val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
private val allowedExportChunks = setOf("IHDR", "IDAT", "IEND")
private val animationChunks = setOf("acTL", "fcTL", "fdAT")

internal fun isSupportedStill(data: ByteArray): Boolean =
    if (data.size >= 8 && data.copyOf(8).contentEquals(pngSignature)) validPng(data, export = false) else validJpeg(data)

internal fun isVerifiedExport(data: ByteArray): Boolean = data.size in 1..MAX_EXPORT_BYTES && validPng(data, export = true)

internal fun canonicalExport(data: ByteArray): ByteArray {
    val clean = ByteArrayOutputStream().use { buffer ->
        buffer.write(pngSignature)
        if (!validPng(data, export = false, canonical = buffer)) throw ExportFailure.Render()
        buffer.toByteArray()
    }
    if (!isVerifiedExport(clean)) throw ExportFailure.Render()
    return clean
}

private fun validPng(data: ByteArray, export: Boolean, canonical: ByteArrayOutputStream? = null): Boolean {
    if (data.size < 45 || !data.copyOf(8).contentEquals(pngSignature)) return false
    val input = ByteBuffer.wrap(data).apply { position(8) }
    var header = false
    var pixels = false
    while (input.remaining() >= 12) {
        val chunkStart = input.position()
        val size = input.int
        if (size < 0 || size > input.remaining() - 8) return false
        val bytes = ByteArray(4).also(input::get)
        val chunk = bytes.toString(Charsets.US_ASCII)
        val start = input.position()
        if (!header && chunk != "IHDR") return false
        if (chunk in animationChunks) return false
        if (export && chunk !in allowedExportChunks) return false
        val crc = CRC32().apply { update(bytes); update(data, start, size) }
        input.position(start + size)
        if (input.int != crc.value.toInt()) return false
        if (canonical != null && chunk in allowedExportChunks) {
            canonical.write(data, chunkStart, input.position() - chunkStart)
        }
        when (chunk) {
            "IHDR" -> {
                if (header || size != 13) return false
                val width = input.getInt(start)
                val height = input.getInt(start + 4)
                if (width <= 0 || height <= 0 || width.toLong() * height > 128_000_000L) return false
                if (export && maxOf(width, height) > EXPORT_EDGE) return false
                header = true
            }
            "IDAT" -> pixels = true
            "IEND" -> return pixels && size == 0 && !input.hasRemaining()
        }
    }
    return false
}

private fun validJpeg(data: ByteArray): Boolean {
    fun value(at: Int) = data[at].toInt() and 255
    if (data.size < 4 || value(0) != 255 || value(1) != 216) return false
    var at = 2
    var scan = false
    var sawScan = false
    while (at < data.size) {
        if (value(at++) != 255) {
            if (!scan) return false
            continue
        }
        while (at < data.size && value(at) == 255) at++
        if (at == data.size) return false
        val marker = value(at++)
        if (marker == 217) return sawScan && at == data.size
        if (scan && (marker == 0 || marker in 208..215)) continue
        if (marker == 216 || marker == 0 || marker in 208..215 || at + 2 > data.size) return false
        val length = value(at) * 256 + value(at + 1)
        if (length < 2 || length > data.size - at) return false
        if (marker == 226 && length >= 6 && data.copyOfRange(at + 2, at + 6)
                .contentEquals(byteArrayOf(77, 80, 70, 0))) return false
        scan = marker == 218
        sawScan = sawScan || scan
        at += length
    }
    return false
}
