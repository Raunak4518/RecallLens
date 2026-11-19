package org.photolibrary.app.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.photolibrary.app.detection.DetectedRegion
import org.photolibrary.app.identity.IdentityVector
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind

internal data class SuggestionRegion(
    val detection: DetectedRegion,
    val descriptor: IdentityVector?,
    val dismissed: Boolean = false,
)

internal object SuggestionRecordCodec {
    fun encode(regions: List<SuggestionRegion>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(regions.size)
            for ((detection, descriptor, dismissed) in regions) {
                output.writeUTF(detection.kind.name)
                output.writeUTF(detection.label)
                with(detection.region) {
                    listOf(left, top, right, bottom).forEach(output::writeFloat)
                }
                output.writeFloat(detection.confidence)
                output.writeBoolean(dismissed)
                output.writeBoolean(descriptor != null)
                descriptor?.writeTo(output)
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<SuggestionRegion> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val count = input.readInt()
        require(count in 0..64)
        List(count) {
            val kind = SubjectKind.valueOf(input.readUTF())
            require(kind == SubjectKind.PERSON || kind == SubjectKind.OBJECT)
            val label = input.readUTF()
            val region = PhotoRegion(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
            val confidence = input.readFloat()
            val dismissed = input.readBoolean()
            val vector = if (input.readBoolean()) IdentityVector.readFrom(input, kind) else null
            SuggestionRegion(DetectedRegion(label, kind, region, confidence), vector, dismissed)
        }.also { require(input.read() == -1) }
    }
}
