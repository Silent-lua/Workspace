package com.silenthub.capturelab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.SecureRandom

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var pairingCodeView: TextView

    private val statusPoll = object : Runnable {
        override fun run() {
            if (::statusView.isInitialized) refreshStatus()
            if (::statusView.isInitialized) statusView.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePairingToken()
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (::statusView.isInitialized) statusView.post(statusPoll)
    }

    override fun onPause() {
        if (::statusView.isInitialized) statusView.removeCallbacks(statusPoll)
        super.onPause()
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
            "Vercel HTTPS Relay • Supabase Realtime • 0.4.0",
            14f, Color.rgb(174, 190, 201), false
        ))

        root.addView(sectionTitle("CÓDIGO DE EMPAREJAMIENTO"), marginTopParams(dp(24)))
        pairingCodeView = text(pairingToken(), 12f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(13), dp(12), dp(13))
            setBackgroundColor(Color.rgb(20, 24, 33))
            setTextIsSelectable(true)
        }
        root.addView(pairingCodeView, marginTopParams(dp(8)))
        root.addView(actionButton("Copiar código para Delta") { copyPairingToken() }, marginTopParams(dp(8)))
        root.addView(actionButton("Generar código nuevo") { rotatePairingToken() }, marginTopParams(dp(8)))

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
        root.addView(statusCard, marginTopParams(dp(20)))

        root.addView(sectionTitle("CONTROLES"), marginTopParams(dp(26)))
        root.addView(actionButton("1. Conceder permiso de ventana flotante") { openOverlaySettings() }, marginTopParams(dp(10)))
        root.addView(actionButton("2. Iniciar receptor externo") { startSecureOverlay() }, marginTopParams(dp(10)))
        root.addView(actionButton("3. Detener receptor y overlay") {
            stopService(Intent(this, SecureOverlayService::class.java))
            refreshStatus()
        }, marginTopParams(dp(10)))

        root.addView(sectionTitle("CONEXIÓN CON DELTA"), marginTopParams(dp(28)))
        root.addView(text(
            "1. Copia el código de emparejamiento de esta pantalla.\n" +
                "2. Inicia el receptor externo y deja activa la notificación de SilentHub.\n" +
                "3. Abre Delta y ejecuta SilentHub-Bimo-Streaming-ESP.lua.\n" +
                "4. En Visuales, pega el código en Código de conexión.\n" +
                "5. Activa Mode Streaming (ESP externo), ESP Jugadores y las opciones que quieras.\n\n" +
                "Delta envía frames por HTTPS a silenthub-web.vercel.app; el móvil recibe los cuadros por Supabase Realtime. Ya no se usa localhost.",
            14f, Color.rgb(198, 210, 218), false
        ), marginTopParams(dp(10)))

        root.addView(sectionTitle("OPCIONES ESP"), marginTopParams(dp(24)))
        root.addView(text(
            "• Mostrar Brillo / Caja: contorno 2D rojo.\n" +
                "• Mostrar Nombre y Mostrar Distancia: etiquetas rojas.\n" +
                "• Tracers / Líneas: líneas rojas.\n" +
                "• Off-screen arrows: flechas rojas en el borde.\n" +
                "• Color fijo: #FF0000.",
            13f, Color.rgb(174, 190, 201), false
        ), marginTopParams(dp(10)))

        root.addView(sectionTitle("SEGURIDAD"), marginTopParams(dp(24)))
        root.addView(text(
            "El overlay mantiene FLAG_SECURE. El código de emparejamiento es aleatorio y funciona como credencial de publicación: no lo compartas. La clave secreta de Supabase permanece en Vercel; el APK solo incorpora la clave publishable pública.",
            13f, Color.rgb(153, 171, 182), false
        ), marginTopParams(dp(10)))

        root.addView(text("SilentHub External ESP • 0.4.0", 11f, Color.rgb(104, 116, 132), false).apply {
            gravity = Gravity.CENTER
        }, marginTopParams(dp(30)))
        setContentView(scroll)
    }

    private fun ensurePairingToken(): String {
        val prefs = getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE)
        val old = prefs.getString(SecureOverlayService.KEY_PAIRING_TOKEN, "")?.trim().orEmpty()
        if (old.matches(Regex("^[A-Za-z0-9_-]{43}$"))) return old
        return createPairingToken().also {
            prefs.edit().putString(SecureOverlayService.KEY_PAIRING_TOKEN, it).apply()
        }
    }

    private fun createPairingToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun pairingToken(): String = ensurePairingToken()

    private fun copyPairingToken() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SilentHub External ESP pairing code", pairingToken()))
        statusView.text = "Código copiado. Pégalo en Delta → Visuales → Código de conexión."
    }

    private fun rotatePairingToken() {
        stopService(Intent(this, SecureOverlayService::class.java))
        val token = createPairingToken()
        getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE).edit()
            .putString(SecureOverlayService.KEY_PAIRING_TOKEN, token)
            .putBoolean(SecureOverlayService.KEY_RUNNING, false)
            .putBoolean(SecureOverlayService.KEY_BRIDGE, false)
            .putString(SecureOverlayService.KEY_DETAIL, "pairing_token_rotated")
            .apply()
        pairingCodeView.text = token
        refreshStatus()
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
        ensurePairingToken()
        val intent = Intent(this, SecureOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        statusView.text = "Conectando a Supabase Realtime…"
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
            running && bridge -> "Supabase Realtime conectado • esperando frames de Delta."
            running && detail == "connecting" -> "Conectando al canal Realtime…"
            running && detail == "network_error" -> "No se pudo abrir el WebSocket. Reintentando."
            running && detail == "channel_rejected" -> "Supabase rechazó el canal. Comprueba la configuración."
            running && detail == "pairing_token_missing" -> "Falta el código de emparejamiento. Genera uno y reinicia."
            running -> "Overlay activo • estado del relay: " + detail
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