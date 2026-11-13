package com.silenthub.capturelab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class SecureOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var markerView: View? = null

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
            setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForegroundService()
        showMarker()
        return START_NOT_STICKY
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

    private fun showMarker() {
        if (markerView != null) return
        val view = SecureMarkerView(this)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SECURE

        val params = WindowManager.LayoutParams(
            dp(286),
            dp(156),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(170)
            alpha = 1f
            title = "SilentHub Secure Test Marker"
        }
        try {
            windowManager?.addView(view, params)
            markerView = view
            setRunning(true)
        } catch (_: SecurityException) {
            setRunning(false)
            stopSelf()
        } catch (_: WindowManager.BadTokenException) {
            setRunning(false)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, SecureOverlayService::class.java).apply {
            action = ACTION_STOP
        }
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
            .setContentTitle("SilentHub Capture Lab")
            .setContentText("Marcador seguro activo")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SilentHub Capture Lab", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Estado del marcador de prueba protegido"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun setRunning(running: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, running).apply()
    }

    override fun onDestroy() {
        markerView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: IllegalArgumentException) {
                // The view was already detached.
            }
        }
        markerView = null
        setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS = "capture_lab_state"
        const val KEY_RUNNING = "overlay_running"
        private const val CHANNEL_ID = "silent_hub_capture_lab"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_STOP = "com.silenthub.capturelab.STOP_OVERLAY"
    }
}

private class SecureMarkerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 7, 20, 28)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(138, 242, 197)
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(184, 205, 213)
        textSize = 10f * density
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(138, 242, 197)
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val outer = RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f)
        canvas.drawRoundRect(outer, 12f * density, 12f * density, fillPaint)
        canvas.drawRoundRect(outer, 12f * density, 12f * density, borderPaint)
        canvas.drawText("SILENTHUB • SECURE TEST", 15f * density, 25f * density, textPaint)
        canvas.drawText("Visible on device / capture test", 15f * density, 43f * density, mutedPaint)
        val box = RectF(w * 0.40f, h * 0.43f, w * 0.60f, h * 0.82f)
        canvas.drawRect(box, linePaint)
        canvas.drawLine(w * 0.50f, h * 0.32f, w * 0.50f, box.top, linePaint)
        canvas.drawCircle(w * 0.50f, h * 0.30f, 4f * density, borderPaint)
        canvas.drawText("TEST MARKER", 15f * density, h - 15f * density, mutedPaint)
    }
}
