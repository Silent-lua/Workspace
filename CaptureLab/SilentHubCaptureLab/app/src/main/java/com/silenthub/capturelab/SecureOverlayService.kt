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
    private var overlayView: View? = null

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
        showTransparentPreview()
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

    private fun showTransparentPreview() {
        if (overlayView != null) return

        val view = DemoEspOverlayView(this)
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
            // Android 12+ restricts pass-through touches from opaque, non-touchable
            // application overlays. Keep the single overlay below the default 0.8 limit.
            alpha = 0.75f
            title = "SilentHub External Secure Preview"
        }

        try {
            windowManager?.addView(view, params)
            overlayView = view
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
            .setContentTitle("SilentHub External Preview")
            .setContentText("Capa ESP transparente activa")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SilentHub External Preview", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Estado de la capa ESP transparente de prueba"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun setRunning(running: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, running).apply()
    }

    override fun onDestroy() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: IllegalArgumentException) {
                // The view was already detached.
            }
        }
        overlayView = null
        setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "capture_lab_state_v2"
        const val KEY_RUNNING = "overlay_running"
        private const val CHANNEL_ID = "silent_hub_capture_lab_v2"
        private const val NOTIFICATION_ID = 4108
        private const val ACTION_STOP = "com.silenthub.capturelab.STOP_OVERLAY"
    }
}

private class DemoEspOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    private fun stroke(color: Int, width: Float = 2f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width * density
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private val green = Color.rgb(138, 242, 197)
    private val cyan = Color.rgb(104, 208, 255)
    private val amber = Color.rgb(255, 196, 104)
    private val greenStroke = stroke(green, 2.2f)
    private val cyanStroke = stroke(cyan, 2.0f)
    private val amberStroke = stroke(amber, 2.0f)
    private val tracerGreen = stroke(green, 1.6f)
    private val tracerCyan = stroke(cyan, 1.6f)
    private val tracerAmber = stroke(amber, 1.6f)
    private val labelBg = fill(Color.argb(190, 8, 19, 27))
    private val labelGreen = fill(green).apply { textSize = 11f * density; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val labelCyan = fill(cyan).apply { textSize = 11f * density; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val labelAmber = fill(amber).apply { textSize = 11f * density; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val subText = fill(Color.WHITE).apply { textSize = 9f * density }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawPlayer(
            canvas, w, h,
            cx = w * 0.25f, top = h * 0.30f, boxW = w * 0.13f, boxH = h * 0.22f,
            name = "Player_01", distance = "18m",
            boxPaint = greenStroke, tracerPaint = tracerGreen, namePaint = labelGreen
        )
        drawPlayer(
            canvas, w, h,
            cx = w * 0.54f, top = h * 0.19f, boxW = w * 0.12f, boxH = h * 0.25f,
            name = "Player_02", distance = "32m",
            boxPaint = cyanStroke, tracerPaint = tracerCyan, namePaint = labelCyan
        )
        drawPlayer(
            canvas, w, h,
            cx = w * 0.78f, top = h * 0.38f, boxW = w * 0.11f, boxH = h * 0.19f,
            name = "Player_03", distance = "46m",
            boxPaint = amberStroke, tracerPaint = tracerAmber, namePaint = labelAmber
        )
    }

    private fun drawPlayer(
        canvas: Canvas,
        screenW: Float,
        screenH: Float,
        cx: Float,
        top: Float,
        boxW: Float,
        boxH: Float,
        name: String,
        distance: String,
        boxPaint: Paint,
        tracerPaint: Paint,
        namePaint: Paint
    ) {
        val left = cx - boxW / 2f
        val right = cx + boxW / 2f
        val bottom = top + boxH
        val box = RectF(left, top, right, bottom)

        canvas.drawLine(screenW / 2f, screenH * 0.94f, cx, bottom, tracerPaint)
        canvas.drawRect(box, boxPaint)
        canvas.drawLine(cx, top - 7f * density, cx, top, boxPaint)

        val labelY = (top - 8f * density).coerceAtLeast(18f * density)
        val textWidth = namePaint.measureText(name)
        val labelLeft = (cx - textWidth / 2f - 5f * density).coerceAtLeast(2f)
        val labelTop = (labelY - 12f * density).coerceAtLeast(0f)
        val labelRight = (cx + textWidth / 2f + 5f * density).coerceAtMost(screenW)
        canvas.drawRoundRect(
            RectF(labelLeft, labelTop, labelRight, labelY + 4f * density),
            4f * density, 4f * density, labelBg
        )
        canvas.drawText(name, cx - textWidth / 2f, labelY, namePaint)
        canvas.drawText(distance, left, (bottom + 14f * density).coerceAtMost(screenH - 4f), subText)
    }
}
