package org.photolibrary.app.index

import android.graphics.Bitmap

internal data class ModelRevision(val name: String, val version: String, val maxImageEdge: Int) {
    init {
        require(name.isNotBlank() && version.isNotBlank() && maxImageEdge > 0)
    }
}

internal interface PhotoProcessor<out Output> : AutoCloseable {
    val model: ModelRevision

    fun process(image: Bitmap): Output
}

internal class PhotoProcessorFactory<Output : Any>(
    val model: ModelRevision,
    private val create: () -> PhotoProcessor<Output>,
) {
    fun open(): PhotoProcessor<Output> {
        val processor = create()
        if (processor.model != model) {
            processor.close()
            error("Processor revision does not match its factory")
        }
        return processor
    }
}

internal class PhotoProcessingException(cause: Throwable) : Exception(cause)
