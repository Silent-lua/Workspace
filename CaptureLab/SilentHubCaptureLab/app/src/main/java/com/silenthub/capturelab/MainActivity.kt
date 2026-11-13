package com.silenthub.capturelab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(Color.rgb(8, 12, 18))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        root.addView(text("SILENTHUB", 13f, Color.rgb(255, 70, 70), true))
        root.addView(text("External ESP", 29f, Color.WHITE, true).apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        root.addView(text(
            "Receptor local • overlay transparente protegido • versión 0.3.0",
            14f, Color.rgb(174, 190, 201), false
        ))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(20, 24, 33))
        }
        statusCard.addView(text("ESTADO DEL RECEPTOR", 11f, Color.rgb(255, 90, 90), true))
        statusView = text("Comprobando permisos…", 14f, Color.WHITE, false).apply {
            setPadding(0, dp(7), 0, 0)
        }
        statusCard.addView(statusView)
        root.addView(statusCard, marginTopParams(dp(24)))

        root.addView(sectionTitle("CONTROLES"), marginTopParams(dp(26)))
        root.addView(actionButton("1. Conceder permiso de ventana flotante") { openOverlaySettings() }, marginTopParams(dp(10)))
        root.addView(actionButton("2. Iniciar receptor ESP protegido") { startSecureOverlay() }, marginTopParams(dp(10)))
        root.addView(actionButton("3. Detener receptor y overlay") {
            stopService(Intent(this, SecureOverlayService::class.java))
            refreshStatus()
        }, marginTopParams(dp(10)))

        root.addView(sectionTitle("CONEXIÓN CON DELTA"), marginTopParams(dp(28)))
        root.addView(text(
            "1. Inicia este receptor y deja activa la notificación de SilentHub.\n" +
                "2. Abre Delta y ejecuta SilentHub-Bimo-Streaming-ESP.lua.\n" +
                "3. En Visuales, activa Mode Streaming (ESP externo).\n" +
                "4. Activa ESP Jugadores y las opciones que quieras mostrar.\n\n" +
                "El script envía las posiciones proyectadas a 127.0.0.1:18273. El overlay solo dibuja datos reales recibidos desde Delta; no contiene jugadores ficticios.",
            14f, Color.rgb(198, 210, 218), false
        ), marginTopParams(dp(10)))

        root.addView(sectionTitle("OPCIONES ESP"), marginTopParams(dp(24)))
        root.addView(text(
            "• Mostrar Brillo / Caja: contorno 2D rojo.\n" +
                "• Mostrar Nombre y Mostrar Distancia: etiquetas rojas.\n" +
                "• Tracers / Líneas: líneas rojas.\n" +
                "• Off-screen arrows: flechas rojas en el borde.\n" +
                "• El color se mantiene fijo en #FF0000.",
            13f, Color.rgb(174, 190, 201), false
        ), marginTopParams(dp(10)))

        root.addView(sectionTitle("PROTECCIÓN"), marginTopParams(dp(24)))
        root.addView(text(
            "El overlay usa FLAG_SECURE y una ventana transparente no táctil. Los datos se reciben únicamente por loopback (127.0.0.1); el servidor no escucha conexiones desde Wi‑Fi ni desde la red local. Si Delta no logra conectar, comprueba que el receptor esté iniciado y vuelve a activar Mode Streaming.",
            13f, Color.rgb(153, 171, 182), false
        ), marginTopParams(dp(10)))

        root.addView(text("SilentHub External ESP • 0.3.0", 11f, Color.rgb(104, 116, 132), false).apply {
            gravity = Gravity.CENTER
        }, marginTopParams(dp(30)))
        setContentView(scroll)
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        } else refreshStatus()
    }

    private fun startSecureOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            statusView.text = "Falta el permiso de ventana flotante. Concédelo primero."
            openOverlaySettings()
            return
        }
        val intent = Intent(this, SecureOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        statusView.text = "Iniciando receptor local…"
        statusView.postDelayed({ refreshStatus() }, 700L)
    }

    private fun refreshStatus() {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val prefs = getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE)
        val running = prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)
        val bridge = prefs.getBoolean(SecureOverlayService.KEY_BRIDGE, false)
        val detail = prefs.getString(SecureOverlayService.KEY_DETAIL, "") ?: ""
        statusView.text = when {
            !allowed -> "Permiso pendiente: autoriza mostrar sobre otras aplicaciones."
            running && bridge -> "Receptor activo • 127.0.0.1:" + SecureOverlayService.PORT + "\nEsperando datos de Delta."
            running -> "Overlay activo, pero el puerto local no pudo iniciarse: " + detail + "."
            else -> "Permiso concedido • receptor detenido."
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(174, 36, 42))
        minHeight = dp(52)
        setOnClickListener { action() }
    }

    private fun sectionTitle(value: String): TextView = text(value, 11f, Color.rgb(255, 90, 90), true)

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun marginTopParams(top: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}