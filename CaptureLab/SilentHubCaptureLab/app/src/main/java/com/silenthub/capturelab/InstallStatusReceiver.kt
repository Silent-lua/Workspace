package com.silenthub.capturelab

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build

internal class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(SecureOverlayService.PREFS, Context.MODE_PRIVATE)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    prefs.edit()
                        .putString(SecureOverlayService.KEY_INSTALL_STATUS, "awaiting_user_confirmation")
                        .apply()
                    val launched = runCatching { context.startActivity(confirmation) }.isSuccess
                    if (!launched) {
                        prefs.edit()
                            .putString(KEY_PENDING_CONFIRMATION, confirmation.toUri(Intent.URI_INTENT_SCHEME))
                            .apply()
                        postConfirmationNotification(context, confirmation)
                    }
                } else {
                    prefs.edit().putString(SecureOverlayService.KEY_INSTALL_STATUS, "confirmation_intent_missing").apply()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                prefs.edit()
                    .putString(SecureOverlayService.KEY_INSTALL_STATUS, "installed")
                    .putBoolean(SecureOverlayService.KEY_UPDATE_MANDATORY, false)
                    .remove(UpdateManager.KEY_REQUIRED_VERSION_CODE)
                    .remove(KEY_PENDING_CONFIRMATION)
                    .apply()
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.take(160) ?: "Android package installer rejected the update."
                prefs.edit()
                    .putString(SecureOverlayService.KEY_INSTALL_STATUS, "failed")
                    .putString(SecureOverlayService.KEY_INSTALL_ERROR, message)
                    .apply()
            }
        }

    private fun postConfirmationNotification(context: Context, confirmation: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SilentHub updates",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Confirm and complete mandatory SilentHub updates."
                }
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            confirmation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SilentHub External · Confirm update")
            .setContentText("Tap to approve the Android package installation.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val KEY_PENDING_CONFIRMATION = "silenthub_pending_install_confirmation"
        private const val CHANNEL_ID = "silenthub_updates"
        private const val NOTIFICATION_ID = 7506
    }
    }
}