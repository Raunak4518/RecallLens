package org.photolibrary.app.index

import android.os.Trace

internal inline fun <T> tracePhotoStage(name: String, action: () -> T): T {
    if (!Trace.isEnabled()) return action()
    Trace.beginSection(name)
    try {
        return action()
    } finally {
        Trace.endSection()
    }
}
