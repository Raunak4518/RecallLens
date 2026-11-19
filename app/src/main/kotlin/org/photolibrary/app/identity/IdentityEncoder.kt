package org.photolibrary.app.identity

import android.graphics.Bitmap
import org.photolibrary.app.detection.DetectedRegion
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind

internal interface IdentityEncoder : AutoCloseable {
    fun encode(image: Bitmap, region: PhotoRegion?, detection: DetectedRegion? = null): IdentityVector?
}

internal interface IdentityModelFactory {
    fun available(kind: SubjectKind): Boolean
    fun fingerprint(kind: SubjectKind): String
    fun open(kind: SubjectKind): IdentityEncoder
}
