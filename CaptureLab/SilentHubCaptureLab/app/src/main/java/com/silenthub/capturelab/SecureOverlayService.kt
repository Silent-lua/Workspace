package com.silenthub.capturelab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.graphics.PixelFormat

class SecureOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: LiveEspOverlayView? = null
    private var realtimeReceiver: SupabaseRealtimeEspReceiver? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            setState(false, false, "overlay_permission_missing")
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForegroundService()
        if (overlayView == null && !showSecureOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (realtimeReceiver == null && !startRealtimeReceiver()) {
            setState(true, false, "pairing_token_missing")
        }
        return START_NOT_STICKY
    }

    private fun showSecureOverlay(): Boolean {
        val view = LiveEspOverlayView(this)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SECURE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 0.75f
            title = "SilentHub External Secure ESP"
        }
        return try {
            windowManager?.addView(view, params)
            overlayView = view
            true
        } catch (_: SecurityException) {
            setState(false, false, "overlay_security_exception")
            false
        } catch (_: WindowManager.BadTokenException) {
            setState(false, false, "overlay_bad_token")
            false
        }
    }

    private fun startRealtimeReceiver(): Boolean {
        val token = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_PAIRING_TOKEN, "")?.trim().orEmpty()
        if (!token.matches(Regex("^[A-Za-z0-9_-]{43}$"))) return false
        return try {
            val receiver = SupabaseRealtimeEspReceiver(
                pairingToken = token,
                onFrame = { frame -> overlayView?.accept(frame) },
                onStatus = { status -> setState(true, status == "connected", status) }
            )
            realtimeReceiver = receiver
            receiver.start()
            true
        } catch (_: Exception) {
            realtimeReceiver?.close()
            realtimeReceiver = null
            false
        }
    }

    private fun startAsForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, SecureOverlayService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 11, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("SilentHub External ESP")
            .setContentText("Vercel relay + Supabase Realtime")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SilentHub External ESP", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Estado del overlay ESP externo protegido" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun setState(overlay: Boolean, bridge: Boolean, detail: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, overlay)
            .putBoolean(KEY_BRIDGE, bridge)
            .putString(KEY_DETAIL, detail)
            .apply()
    }

    override fun onDestroy() {
        realtimeReceiver?.close()
        realtimeReceiver = null
        overlayView?.let { view ->
            try { windowManager?.removeView(view) } catch (_: IllegalArgumentException) { }
        }
        overlayView = null
        setState(false, false, "stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "external_esp_state_v4"
        const val KEY_RUNNING = "overlay_running"
        const val KEY_BRIDGE = "bridge_running"
        const val KEY_DETAIL = "state_detail"
        const val KEY_PAIRING_TOKEN = "pairing_token"
                private const val CHANNEL_ID = "silent_hub_external_esp_v4"
        private const val NOTIFICATION_ID = 4111
        private const val ACTION_STOP = "com.silenthub.capturelab.STOP_EXTERNAL_ESP"
    }
}