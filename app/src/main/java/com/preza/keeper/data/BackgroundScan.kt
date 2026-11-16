package com.preza.keeper.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps analysing photos after the app is closed. Android gives a background job about 10 minutes at a time;
 * progress is saved as it goes, and the job asks to run again until every photo is done. Nothing leaves the phone.
 */
object BackgroundScan {
    private const val NAME = "keeper-scan"

    fun schedule(ctx: Context, onlyCharging: Boolean) {
        val req = OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(onlyCharging).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(ctx: Context) {
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(NAME) }
    }
}

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) return Result.success()
        // The app is scanning right now: let it, try again later.
        if (ScanEngine.lock.isLocked) return Result.retry()
        val cache = Cache.get(ctx)
        if (!cache.loaded) cache.load()
        val left = ScanEngine.analyzeMissing(ctx, cache, Prefs(ctx), stop = { isStopped })
        return if (left > 0) Result.retry() else Result.success()
    }
}
