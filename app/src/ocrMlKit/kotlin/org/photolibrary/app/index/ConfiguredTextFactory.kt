package org.photolibrary.app.index

import android.content.Context

internal fun configuredTextFactory(context: Context): PhotoProcessorFactory<PhotoText> = LocalTextIndex.defaultFactory
