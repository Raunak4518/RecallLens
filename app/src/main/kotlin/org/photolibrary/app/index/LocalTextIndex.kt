package org.photolibrary.app.index

import android.graphics.Bitmap
import android.net.Uri
import org.photolibrary.app.BuildConfig

internal object LocalTextIndex {
    private val model = ModelRevision("mlkit-latin",
        "${BuildConfig.ML_KIT_LATIN_VERSION}:android-srgb-oriented-max1600-v1:lines256:characters16384", maxImageEdge = 1600)
    val defaultFactory = PhotoProcessorFactory(model, ::recognizer)

    fun recognizer() = MlKitPhotoText(model, lineLimit = 256, characterLimit = 16_384)

    fun create(
        storage: EncryptedPhotoIndex,
        loadImage: suspend (Uri, Int) -> Bitmap?,
        readRevision: (Uri) -> String?,
        processorFactory: PhotoProcessorFactory<PhotoText> = defaultFactory,
        detail: Boolean = false,
    ): LocalPhotoIndexer<PhotoText> {
        if (!detail) return LocalPhotoIndexer(loadImage, processorFactory, storage::open, readRevision)
        val refined = processorFactory.model.copy(version = processorFactory.model.version + ":detail2560-v1")
        return LocalPhotoIndexer(loadImage, processorFactory, storage::open, readRevision, refined, TextDetailPass(loadImage)::refine)
    }
}
