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
            setBackgroundColor(Color.rgb(8, 19, 27))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        root.addView(text("SILENTHUB", 13f, Color.rgb(138, 242, 197), true))
        root.addView(text("Capture Lab", 30f, Color.WHITE, true).apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        root.addView(text(
            "Prueba de aislamiento de una ventana flotante protegida en Android.",
            15f, Color.rgb(174, 190, 201), false
        ))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(17, 34, 44))
        }
        statusCard.addView(text("ESTADO", 11f, Color.rgb(138, 242, 197), true))
        statusView = text("Comprobando permisos…", 14f, Color.WHITE, false).apply {
            setPadding(0, dp(7), 0, 0)
        }
        statusCard.addView(statusView)
        root.addView(statusCard, marginTopParams(dp(24)))

        root.addView(sectionTitle("PRUEBA"), marginTopParams(dp(26)))
        root.addView(actionButton("1. Conceder permiso de ventana flotante") {
            openOverlaySettings()
        }, marginTopParams(dp(10)))
        root.addView(actionButton("2. Mostrar marcador seguro") {
            startSecureOverlay()
        }, marginTopParams(dp(10)))
        root.addView(actionButton("3. Ocultar marcador") {
            stopService(Intent(this, SecureOverlayService::class.java))
            refreshStatus()
        }, marginTopParams(dp(10)))

        root.addView(sectionTitle("CÓMO COMPROBARLO"), marginTopParams(dp(28)))
        root.addView(text(
            "• Concede el permiso de mostrar sobre otras aplicaciones.\n" +
                "• Pulsa “Mostrar marcador seguro”. Debe aparecer un recuadro verde sobre la pantalla.\n" +
                "• Abre Roblox y graba con Medal. Revisa si el marcador desaparece del video y si el juego sigue visible debajo.\n" +
                "• Repite la prueba con la grabadora de Samsung.\n" +
                "• Vuelve aquí y pulsa “Ocultar marcador” al terminar.",
            14f, Color.rgb(198, 210, 218), false
        ), marginTopParams(dp(10)))

        root.addView(sectionTitle("LÍMITES DE ESTA VERSIÓN"), marginTopParams(dp(28)))
        root.addView(text(
            "Esta aplicación solo dibuja un marcador de prueba. No se conecta a Delta, no lee Roblox y no contiene un ESP. “FLAG_SECURE” protege la ventana completa, no cada elemento dibujado. El resultado depende de cómo One UI y cada grabador traten las ventanas protegidas.",
            13f, Color.rgb(153, 171, 182), false
        ), marginTopParams(dp(10)))

        root.addView(text("SilentHub Capture Lab • Prototype 0.1.0", 11f, Color.rgb(104, 126, 139), false).apply {
            gravity = Gravity.CENTER
        }, marginTopParams(dp(30)))

        setContentView(scroll)
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        } else {
            refreshStatus()
        }
    }

    private fun startSecureOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            statusView.text = "Falta el permiso de ventana flotante. Concédelo primero."
            openOverlaySettings()
            return
        }
        val intent = Intent(this, SecureOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusView.text = "Solicitud enviada. El marcador debería aparecer sobre las aplicaciones."
    }

    private fun refreshStatus() {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val running = getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE)
            .getBoolean(SecureOverlayService.KEY_RUNNING, false)
        statusView.text = when {
            !allowed -> "Permiso pendiente: autoriza “mostrar sobre otras aplicaciones”."
            running -> "Permiso concedido • marcador activo."
            else -> "Permiso concedido • marcador detenido."
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.rgb(8, 19, 27))
        setBackgroundColor(Color.rgb(138, 242, 197))
        minHeight = dp(52)
        setOnClickListener { action() }
    }

    private fun sectionTitle(value: String): TextView =
        text(value, 11f, Color.rgb(138, 242, 197), true)

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun marginTopParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
