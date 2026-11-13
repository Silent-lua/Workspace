package com.silenthub.capturelab

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal class UpdateCheckWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(SecureOverlayService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)) return Result.success()

        return try {
            val manager = UpdateManager(applicationContext)
            val info = manager.checkLatest(force = true)
            if (info == null) {
                prefs.edit().putBoolean(SecureOverlayService.KEY_UPDATE_MANDATORY, false).apply()
                return Result.success()
            }
            if (prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)) return Result.success()

            val file = manager.findVerifiedCachedFile(info) ?: manager.download(info)
            prefs.edit()
                .putString(SecureOverlayService.KEY_PENDING_UPDATE_VERSION, info.versionName)
                .putString(SecureOverlayService.KEY_PENDING_UPDATE_FILE, file.absolutePath)
                .putBoolean(
                    SecureOverlayService.KEY_UPDATE_MANDATORY,
                    info.mandatory && manager.currentVersionCode() < info.minimumSupportedVersionCode
                )
                .apply()
            Result.success()
        } catch (_: ReleaseSignatureMismatchException) {
            prefs.edit().putString(SecureOverlayService.KEY_INSTALL_STATUS, "release_signature_migration_required").apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

internal object UpdateWorkScheduler {
    private const val PERIODIC_WORK = "silenthub-periodic-update-check"
    private const val ONE_TIME_WORK = "silenthub-update-after-session"

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun enqueueAfterSession(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK, ExistingWorkPolicy.KEEP, request
        )
    }
}