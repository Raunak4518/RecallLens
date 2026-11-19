package org.photolibrary.app.library

import android.content.Context
import android.Manifest
import android.app.AppOpsManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore

internal class PhotoSourceObserver(
    private val context: Context,
    onChanged: () -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val dispatch = Runnable { onChanged() }
    private val observers = mutableMapOf<Uri, ContentObserver>()
    private val storage = context.getSystemService(StorageManager::class.java)
    private var closed = false
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, packageName ->
        if (packageName == context.packageName) invalidate()
    }
    private val volumeListener = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) = invalidate()
    }

    init {
        val permissions = buildList {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_IMAGES)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        permissions.mapNotNull(AppOpsManager::permissionToOp).distinct()
            .forEach { appOps.startWatchingMode(it, context.packageName, permissionListener) }
        storage.registerStorageVolumeCallback(context.mainExecutor, volumeListener)
        folders(emptyList())
    }

    fun folders(trees: List<Uri>) {
        if (closed) return
        val wanted = trees.toSet() + MediaStore.AUTHORITY_URI
        for (uri in observers.keys.toList() - wanted) {
            context.contentResolver.unregisterContentObserver(checkNotNull(observers.remove(uri)))
        }
        for (uri in wanted - observers.keys) {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = invalidate()
                override fun onChange(selfChange: Boolean, uri: Uri?) = invalidate()
            }
            try {
                context.contentResolver.registerContentObserver(uri, true, observer)
                observers[uri] = observer
            } catch (_: SecurityException) {
                Unit
            }
        }
    }

    private fun invalidate() {
        handler.post {
            if (!closed) {
                handler.removeCallbacks(dispatch)
                handler.postDelayed(dispatch, 350)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(dispatch)
        observers.values.forEach(context.contentResolver::unregisterContentObserver)
        observers.clear()
        appOps.stopWatchingMode(permissionListener)
        storage.unregisterStorageVolumeCallback(volumeListener)
    }
}
