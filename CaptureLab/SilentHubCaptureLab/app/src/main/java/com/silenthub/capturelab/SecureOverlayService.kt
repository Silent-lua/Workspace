package com.silenthub.capturelab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

class SecureOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: LiveEspOverlayView? = null
    private var realtimeReceiver: SupabaseRealtimeEspReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastFrameElapsed = 0L

    private val staleFrameRunnable = Runnable {
        if (SystemClock.elapsedRealtime() - lastFrameElapsed >= FRAME_STALE_MS &&
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_CONNECTION_STATE, "") == ConnectionState.PAIRED.name) {
            setConnectionState(ConnectionState.CONNECTED_WAITING, "waiting_for_delta_frame")
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_USER_STOPPED, true)
                .putBoolean(KEY_AUTO_START, false)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (intent == null && (!prefs.getBoolean(KEY_AUTO_START, false) ||
                prefs.getBoolean(KEY_USER_STOPPED, false))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (prefs.getBoolean(KEY_USER_STOPPED, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (prefs.getBoolean(KEY_UPDATE_MANDATORY, false)) {
            setConnectionState(ConnectionState.CONNECTION_FAILED, "mandatory_update_required")
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            setConnectionState(ConnectionState.CONNECTION_FAILED, "overlay_permission_missing")
            stopSelf()
            return START_NOT_STICKY
        }

        prefs.edit().putBoolean(KEY_AUTO_START, true).apply()
        startAsForegroundService()
        if (overlayView == null && !showSecureOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (realtimeReceiver == null) startRealtimeReceiver()
        return START_STICKY
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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()
            true
        } catch (_: SecurityException) {
            setConnectionState(ConnectionState.CONNECTION_FAILED, "overlay_security_exception")
            false
        } catch (_: WindowManager.BadTokenException) {
            setConnectionState(ConnectionState.CONNECTION_FAILED, "overlay_bad_token")
            false
        }
    }

    private fun startRealtimeReceiver() {
        val token = try {
            PairingTokenStore(this).getOrCreate()
        } catch (_: Exception) {
            setConnectionState(ConnectionState.CONNECTION_FAILED, "pairing_keystore_unavailable")
            return
        }
        setConnectionState(ConnectionState.CONNECTING, "opening_realtime_channel")
        realtimeReceiver = SupabaseRealtimeEspReceiver(
            pairingToken = token,
            onFrame = { frame ->
                lastFrameElapsed = SystemClock.elapsedRealtime()
                mainHandler.removeCallbacks(staleFrameRunnable)
                mainHandler.postDelayed(staleFrameRunnable, FRAME_STALE_MS)
                overlayView?.accept(frame)
                if (getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(KEY_CONNECTION_STATE, "") != ConnectionState.PAIRED.name) {
                    setConnectionState(ConnectionState.PAIRED, "first_valid_frame_received")
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_FRAME, System.currentTimeMillis())
                    .putInt(KEY_LAST_PLAYER_COUNT, frame.players.size)
                    .apply()
            },
            onStatus = { status ->
                when (status) {
                    "connecting" -> setConnectionState(ConnectionState.CONNECTING, status)
                    "connected" -> setConnectionState(ConnectionState.CONNECTED_WAITING, "realtime_join_accepted")
                    "reconnecting" -> setConnectionState(ConnectionState.RECONNECTING, status)
                    "channel_rejected", "pairing_token_invalid" ->
                        setConnectionState(ConnectionState.CONNECTION_FAILED, status)
                    "network_error" -> setConnectionState(ConnectionState.RECONNECTING, status)
                    "stopped" -> Unit
                }
            }
        ).also { it.start() }
    }

    private fun setConnectionState(state: ConnectionState, detail: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_CONNECTION_STATE, state.name)
            .putString(KEY_DETAIL, detail)
            .putBoolean(KEY_BRIDGE, state == ConnectionState.CONNECTED_WAITING || state == ConnectionState.PAIRED)
            .apply()
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
            .setContentText("Vercel relay · Supabase Realtime")
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
            ).apply { description = "Estado del overlay externo protegido" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(staleFrameRunnable)
        realtimeReceiver?.close()
        realtimeReceiver = null
        overlayView?.let { view ->
            try { windowManager?.removeView(view) } catch (_: IllegalArgumentException) { }
        }
        overlayView = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_BRIDGE, false)
            .putString(KEY_CONNECTION_STATE, ConnectionState.DISCONNECTED.name)
            .putString(KEY_DETAIL, "service_stopped")
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "external_esp_state_v4"
        const val KEY_RUNNING = "overlay_running"
        const val KEY_BRIDGE = "bridge_running"
        const val KEY_DETAIL = "state_detail"
        const val KEY_AUTO_START = "auto_start_enabled"
        const val KEY_USER_STOPPED = "user_stopped"
        const val KEY_CONNECTION_STATE = "connection_state"
        const val KEY_LAST_FRAME = "last_frame_at"
        const val KEY_LAST_PLAYER_COUNT = "last_player_count"
        const val KEY_UPDATE_MANDATORY = "mandatory_update_pending"
        const val KEY_PENDING_UPDATE_VERSION = "pending_update_version"
        const val KEY_PENDING_UPDATE_FILE = "pending_update_file"
        const val KEY_INSTALL_STATUS = "install_status"
        const val KEY_INSTALL_ERROR = "install_error"
        private const val CHANNEL_ID = "silent_hub_external_esp_v5"
        private const val NOTIFICATION_ID = 4112
        private const val ACTION_STOP = "com.silenthub.capturelab.STOP_EXTERNAL_ESP"
        private const val FRAME_STALE_MS = 2_500L
    }
}