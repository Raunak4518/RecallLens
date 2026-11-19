package org.photolibrary.app.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.photolibrary.app.PhotoLibraryApplication

class PhotoIndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val coordinator = (applicationContext as PhotoLibraryApplication).indexing
        try {
            if (coordinator.runBatch()) PhotoIndexScheduler(applicationContext).continueWork()
            Result.success()
        } catch (_: PhotoIndexUnavailable) {
            coordinator.unavailable()
            Result.failure()
        } catch (_: SecurityException) {
            Result.retry()
        }
    }
}

internal class PhotoIndexScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun request() {
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
        val work = OneTimeWorkRequestBuilder<PhotoIndexWorker>().setConstraints(constraints).build()
        workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work).result.get()
    }

    fun continueWork() = request()

    fun states() = workManager.getWorkInfosForUniqueWorkFlow(NAME)

    fun cancel() {
        workManager.cancelUniqueWork(NAME).result.get()
    }

    private companion object {
        const val NAME = "local-photo-index"
    }
}
