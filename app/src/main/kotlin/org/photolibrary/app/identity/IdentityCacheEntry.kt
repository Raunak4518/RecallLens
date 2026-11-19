package org.photolibrary.app.identity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind

internal data class IdentityRegion(val region: PhotoRegion?, val vector: IdentityVector)

internal data class IdentityCacheEntry(
    val gallery: List<IdentityRegion>? = null,
    val references: Map<PhotoRegion?, IdentityVector?> = emptyMap(),
) {
    fun withReference(region: PhotoRegion?, vector: IdentityVector?): IdentityCacheEntry {
        val recent = (references + (region to vector)).entries.toList().takeLast(MAX_REFERENCES)
        return copy(references = recent.associate { it.key to it.value })
    }
}

private const val MAX_REFERENCES = 24
private const val MAX_GALLERY_REGIONS = 12

internal object IdentityCacheCodec {
    fun encode(entry: IdentityCacheEntry): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(entry.gallery?.size ?: -1)
            entry.gallery?.forEach {
                output.writeRegion(it.region)
                it.vector.writeTo(output)
            }
            output.writeInt(entry.references.size)
            for ((region, vector) in entry.references) {
                output.writeRegion(region)
                output.writeBoolean(vector != null)
                vector?.writeTo(output)
            }
        }
        buffer.toByteArray()
    }

    fun decode(bytes: ByteArray, kind: SubjectKind): IdentityCacheEntry =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val galleryCount = input.readInt()
            require(galleryCount in -1..MAX_GALLERY_REGIONS)
            val gallery = if (galleryCount < 0) null else List(galleryCount) {
                IdentityRegion(input.readRegion(), IdentityVector.readFrom(input, kind))
            }
            val referenceCount = input.readInt()
            require(referenceCount in 0..MAX_REFERENCES)
            val references = buildMap {
                repeat(referenceCount) {
                    val region = input.readRegion()
                    require(!containsKey(region))
                    put(region, if (input.readBoolean()) IdentityVector.readFrom(input, kind) else null)
                }
            }
            require(input.read() == -1)
            IdentityCacheEntry(gallery, references)
        }

    private fun DataOutputStream.writeRegion(region: PhotoRegion?) {
        writeBoolean(region != null)
        if (region != null) {
            writeFloat(region.left)
            writeFloat(region.top)
            writeFloat(region.right)
            writeFloat(region.bottom)
        }
    }

    private fun DataInputStream.readRegion(): PhotoRegion? =
        if (readBoolean()) PhotoRegion(readFloat(), readFloat(), readFloat(), readFloat()) else null
}
