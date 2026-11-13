package com.silenthub.capturelab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

internal class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(SecureOverlayService.PREFS, Context.MODE_PRIVATE)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmation) }
                    prefs.edit().putString(SecureOverlayService.KEY_INSTALL_STATUS, "awaiting_user_confirmation").apply()
                } else {
                    prefs.edit().putString(SecureOverlayService.KEY_INSTALL_STATUS, "confirmation_intent_missing").apply()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                prefs.edit()
                    .putString(SecureOverlayService.KEY_INSTALL_STATUS, "installed")
                    .putBoolean(SecureOverlayService.KEY_UPDATE_MANDATORY, false)
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
    }
}