package com.silenthub.capturelab

import android.Manifest
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: SharedPreferences
    private lateinit var tokenStore: PairingTokenStore
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var stateDot: View
    private lateinit var stateTitle: TextView
    private lateinit var stateSubtitle: TextView
    private lateinit var pairingValue: TextView
    private lateinit var revealButton: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var updateTitle: TextView
    private lateinit var updateSubtitle: TextView
    private lateinit var updateProgress: ProgressBar
    private lateinit var updateButton: Button

    private var pairingRevealed = false
    private var updateInfo: UpdateInfo? = null
    private var updateFile: File? = null
    private var updateCheckInFlight = false
    private var downloadInFlight = false
    private var mandatoryGate = false
    private var lastProgressUiAt = 0L

    // Midnight graphite, cool steel and restrained champagne accents.
    private val bg = Color.rgb(7, 10, 14)
    private val panel = Color.rgb(16, 22, 29)
    private val panelRaised = Color.rgb(24, 32, 41)
    private val stroke = Color.rgb(48, 61, 73)
    private val primary = Color.rgb(241, 245, 248)
    private val secondary = Color.rgb(177, 190, 200)
    private val muted = Color.rgb(126, 142, 154)
    private val gold = Color.rgb(195, 163, 111)
    private val goldLight = Color.rgb(229, 205, 160)
    private val connected = Color.rgb(113, 194, 151)
    private val warning = Color.rgb(218, 174, 105)
    private val errorColor = Color.rgb(214, 123, 123)
    private var statusPulse: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        prefs = getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE)
        tokenStore = PairingTokenStore(this)
        buildScreen()
        UpdateWorkScheduler.ensureScheduled(this)
        checkForUpdate(force = false)
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(this)
        refreshConnectionState()
        restoreReceiverWhenAppropriate()
        renderInstallStatus()
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        statusPulse?.cancel()
        statusPulse = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshConnectionState()
        if (!updateCheckInFlight && !downloadInFlight &&
            !prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)) {
            checkForUpdate(force = false)
        }
        renderInstallStatus()
    }

    override fun onDestroy() {
        statusPulse?.cancel()
        statusPulse = null
        io.shutdown()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            SecureOverlayService.KEY_RUNNING,
            SecureOverlayService.KEY_BRIDGE,
            SecureOverlayService.KEY_DETAIL,
            SecureOverlayService.KEY_CONNECTION_STATE,
            SecureOverlayService.KEY_LAST_PLAYER_COUNT,
            SecureOverlayService.KEY_AUTO_START,
            SecureOverlayService.KEY_USER_STOPPED,
            SecureOverlayService.KEY_INSTALL_STATUS,
            SecureOverlayService.KEY_INSTALL_ERROR,
            SecureOverlayService.KEY_UPDATE_MANDATORY -> runOnUiThread {
                refreshConnectionState()
                renderInstallStatus()
                applyMandatoryGate()
            }
        }
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.BLACK, bg, Color.BLACK)
            )
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(18), dp(18), dp(18), dp(28))
            // Target 35 is edge-to-edge on recent Android versions. Keep all controls
            // clear of status/navigation bars without relying on a fixed device size.
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    dp(18) + insets.systemWindowInsetLeft,
                    dp(18) + insets.systemWindowInsetTop,
                    dp(18) + insets.systemWindowInsetRight,
                    dp(28) + insets.systemWindowInsetBottom
                )
                insets
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Brand bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(this).apply {
            text = "S"
            textSize = 21f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(goldLight)
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(42, 53, 63), Color.rgb(17, 24, 32))
            ).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(94, 111, 124))
            }
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            contentDescription = "SilentHub"
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("SILENTHUB", 12f, primary, true).apply { letterSpacing = 0.19f })
            addView(label("EXTERNAL", 9f, muted, true).apply {
                letterSpacing = 0.14f
                setPadding(0, dp(3), 0, 0)
            })
        }
        header.addView(mark)
        header.addView(brand)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val versionPill = label("v" + installedVersionName(), 10f, goldLight, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = pillBackground(Color.rgb(35, 41, 45), Color.rgb(91, 78, 58))
        }
        header.addView(versionPill)
        content.addView(header)

        // Hero panel
        val hero = card(hero = true)
        val animatedLogoId = resources.getIdentifier("silenthub_logo", "raw", packageName)
        if (animatedLogoId != 0) {
            hero.addView(
                GifLogoView(this, animatedLogoId),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132))
            )
        }
        hero.addView(label("FIELD CONSOLE   /   ANDROID CLIENT", 9f, goldLight, true).apply {
            letterSpacing = 0.16f
        })
        hero.addView(label("External ESP", 29f, primary, true), topMargin(dp(10)))
        hero.addView(label(
            "A secure, low-latency companion for your Delta ESP session.",
            13f, secondary, false
        ), topMargin(dp(6)))
        val heroRule = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(gold, Color.rgb(93, 112, 125), Color.TRANSPARENT)
            )
            layoutParams = LinearLayout.LayoutParams(dp(94), dp(2))
        }
        hero.addView(heroRule, topMargin(dp(18)))
        val secureChip = label("◆  SECURE CHANNEL  ·  PRIVATE PAIRING", 9f, secondary, true).apply {
            letterSpacing = 0.07f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = pillBackground(Color.rgb(22, 31, 39), stroke)
        }
        hero.addView(secureChip, topMargin(dp(14)))
        content.addView(hero, topMargin(dp(24)))

        // Live connection state
        content.addView(sectionHeader("SESSION", "Live connection status"), topMargin(dp(24)))
        val stateCard = card()
        val stateHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stateDot = View(this).apply {
            background = oval(muted)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
            contentDescription = "Connection status indicator"
        }
        val stateText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        stateTitle = label("Disconnected", 17f, primary, true)
        stateSubtitle = label("El receptor está detenido.", 12f, muted, false).apply {
            setPadding(0, dp(4), 0, 0)
        }
        stateText.addView(stateTitle)
        stateText.addView(stateSubtitle)
        stateHeader.addView(stateDot)
        stateHeader.addView(stateText)
        stateCard.addView(stateHeader)
        val stateDivider = View(this).apply {
            setBackgroundColor(stroke)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
        }
        stateCard.addView(stateDivider, topMargin(dp(14)))
        stateCard.addView(label(
            "The link is considered paired only after the first valid ESP frame arrives.",
            11f, muted, false
        ), topMargin(dp(11)))
        content.addView(stateCard, topMargin(dp(10)))

        // Pairing
        content.addView(sectionHeader("PAIRING", "Device identity and access"), topMargin(dp(24)))
        val pairCard = card()
        pairCard.addView(label("PAIRING TOKEN", 10f, goldLight, true).apply {
            letterSpacing = 0.13f
        })
        pairCard.addView(label(
            "Copy this code once into Delta. Keep it private; anyone with the code can publish to your channel.",
            12f, muted, false
        ), topMargin(dp(7)))
        pairingValue = label(maskedToken(), 13f, primary, true).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(15), dp(14), dp(15))
            background = roundedGradient(
                intArrayOf(Color.rgb(10, 16, 22), Color.rgb(20, 28, 36)),
                dp(10), stroke
            )
            minHeight = dp(52)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        pairCard.addView(pairingValue, topMargin(dp(14)))
        val pairButtons = LinearLayout(this).apply {
            orientation = if (resources.configuration.screenWidthDp < 360)
                LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        revealButton = textAction("Reveal code") {
            pairingRevealed = !pairingRevealed
            refreshPairingToken()
        }.apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            background = rippleBackground(panelRaised, stroke, dp(10))
        }
        val copyButton = actionButton("Copy token", true) { copyToken() }
        if (pairButtons.orientation == LinearLayout.VERTICAL) {
            pairButtons.addView(copyButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ))
            pairButtons.addView(revealButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(8) })
        } else {
            pairButtons.addView(revealButton, LinearLayout.LayoutParams(0, dp(48), 0.85f))
            pairButtons.addView(copyButton, LinearLayout.LayoutParams(0, dp(48), 1.15f).apply {
                leftMargin = dp(8)
            })
        }
        pairCard.addView(pairButtons, topMargin(dp(12)))
        pairCard.addView(textAction("Restore saved pairing code") { importPairingToken() }, topMargin(dp(12)))
        pairCard.addView(textAction("Rotate access token") { confirmTokenRotation() }, topMargin(dp(2)))
        content.addView(pairCard, topMargin(dp(10)))

        // Receiver controls
        content.addView(sectionHeader("RECEIVER", "Secure overlay controls"), topMargin(dp(24)))
        val receiverCard = card()
        receiverCard.addView(label("ESP SESSION", 10f, goldLight, true).apply {
            letterSpacing = 0.13f
        })
        receiverCard.addView(label(
            "Start the protected foreground service to receive frames and render the overlay.",
            12f, muted, false
        ), topMargin(dp(6)))
        val controls = LinearLayout(this).apply {
            orientation = if (resources.configuration.screenWidthDp < 360)
                LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        startButton = actionButton("Start receiver", true) { startReceiver(userInitiated = true) }
        stopButton = actionButton("Stop session", false) { stopReceiver(userInitiated = true) }
        if (controls.orientation == LinearLayout.VERTICAL) {
            controls.addView(startButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
            ))
            controls.addView(stopButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { topMargin = dp(8) })
        } else {
            controls.addView(startButton, LinearLayout.LayoutParams(0, dp(50), 1f))
            controls.addView(stopButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                leftMargin = dp(9)
            })
        }
        receiverCard.addView(controls, topMargin(dp(15)))
        receiverCard.addView(label(
            "A persistent Android notification remains visible while the receiver is active. Stopping it disables automatic resume.",
            11f, muted, false
        ), topMargin(dp(11)))
        content.addView(receiverCard, topMargin(dp(10)))

        // Updates
        content.addView(sectionHeader("SOFTWARE", "Verified update channel"), topMargin(dp(24)))
        val updateCard = card()
        val updateTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val updateHeading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("UPDATE MANAGER", 10f, goldLight, true).apply {
                letterSpacing = 0.12f
            })
            updateTitle = label("Automatic updates", 16f, primary, true)
            addView(updateTitle, topMargin(dp(5)))
        }
        updateTop.addView(updateHeading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val verified = label("SHA-256", 9f, secondary, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = pillBackground(Color.rgb(24, 36, 38), Color.rgb(56, 91, 83))
        }
        updateTop.addView(verified)
        updateCard.addView(updateTop)
        updateSubtitle = label("Consultando el manifiesto de publicación…", 12f, muted, false)
        updateCard.addView(updateSubtitle, topMargin(dp(8)))
        updateProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(stroke)
        }
        updateCard.addView(updateProgress, topMargin(dp(14)))
        updateButton = actionButton("Check for updates", false) { checkForUpdate(force = true) }
        updateCard.addView(updateButton, topMargin(dp(14)))
        content.addView(updateCard, topMargin(dp(10)))

        // Engineering note
        content.addView(sectionHeader("SYSTEM", "Capture-safe by design"), topMargin(dp(24)))
        val note = card()
        note.addView(label("RENDER PIPELINE", 10f, goldLight, true).apply {
            letterSpacing = 0.13f
        })
        note.addView(label("Event-driven rendering", 16f, primary, true), topMargin(dp(6)))
        note.addView(label(
            "FLAG_SECURE stays enabled. The Canvas redraws on new frames and once on expiry, without an idle refresh loop.",
            12f, secondary, false
        ), topMargin(dp(7)))
        val noteDivider = View(this).apply {
            setBackgroundColor(stroke)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
        }
        note.addView(noteDivider, topMargin(dp(14)))
        note.addView(label(
            "Downloads, integrity checks and package preparation run away from the main thread. Android retains final installation approval.",
            11f, muted, false
        ), topMargin(dp(12)))
        content.addView(note, topMargin(dp(10)))

        content.addView(label(
            "SILENTHUB  /  ENGINEERING EDITION",
            9f, muted, true
        ).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            setPadding(0, dp(16), 0, dp(8))
        }, topMargin(dp(24)))

        scroll.addView(content)
        setContentView(scroll)
        scroll.requestApplyInsets()
        refreshPairingToken()
        refreshConnectionState()
        renderInstallStatus()
        animateEntrance(content)
    }

    private fun restoreReceiverWhenAppropriate() {
        val autoStart = prefs.getBoolean(SecureOverlayService.KEY_AUTO_START, false)
        val userStopped = prefs.getBoolean(SecureOverlayService.KEY_USER_STOPPED, false)
        val updateRequired = prefs.getBoolean(SecureOverlayService.KEY_UPDATE_MANDATORY, false)
        if (!autoStart || userStopped || updateRequired || !hasOverlayPermission()) return
        if (!prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)) {
            startReceiver(userInitiated = false)
        }
    }

    private fun refreshPairingToken() {
        val token = tokenStore.getOrCreate()
        pairingValue.text = if (pairingRevealed) token else maskedToken()
        revealButton.text = if (pairingRevealed) "Ocultar" else "Revelar"
    }

    private fun copyToken() {
        val clip = ClipData.newPlainText("SilentHub pairing code", tokenStore.getOrCreate())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        stateSubtitle.text = "Código copiado. Pégalo en Delta una vez."
        maybeAnimate(stateSubtitle)
    }

    private fun importPairingToken() {
        val input = android.widget.EditText(this).apply {
            hint = "Pega el código de 43 caracteres"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(primary)
            setHintTextColor(muted)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(panelRaised, dp(8), stroke)
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Restaurar código de emparejamiento")
            .setMessage("Pega el código que guardaste antes de desinstalar la compilación de prueba. Se cifrará de nuevo en este dispositivo y podrás seguir usando el mismo código en Delta.")
            .setView(wrapper)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Restaurar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    tokenStore.restore(input.text?.toString().orEmpty())
                } catch (_: IllegalArgumentException) {
                    input.error = "Código no válido. Debe tener 43 caracteres."
                    return@setOnClickListener
                }
                val restartReceiver = prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)
                if (restartReceiver) stopReceiver(userInitiated = false)
                pairingRevealed = false
                refreshPairingToken()
                if (restartReceiver) startButton.postDelayed({ startReceiver(userInitiated = false) }, 400L)
                android.widget.Toast.makeText(
                    this,
                    "Código restaurado y guardado de forma cifrada.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmTokenRotation() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Regenerar código")
            .setMessage("El código anterior dejará de funcionar. Deberás pegar el nuevo código en Delta.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Regenerar") { _, _ -> rotateToken() }
            .show()
    }

    private fun rotateToken() {
        tokenStore.rotate()
        prefs.edit()
            .putBoolean(SecureOverlayService.KEY_USER_STOPPED, false)
            .putBoolean(SecureOverlayService.KEY_AUTO_START, true)
            .putBoolean(SecureOverlayService.KEY_RUNNING, false)
            .putBoolean(SecureOverlayService.KEY_BRIDGE, false)
            .putString(SecureOverlayService.KEY_CONNECTION_STATE, ConnectionState.DISCONNECTED.name)
            .putString(SecureOverlayService.KEY_DETAIL, "pairing_token_rotated")
            .apply()
        stopService(Intent(this, SecureOverlayService::class.java))
        pairingRevealed = true
        refreshPairingToken()
        startButton.postDelayed({ startReceiver(userInitiated = false) }, 400L)
    }

    private fun startReceiver(userInitiated: Boolean) {
        if (mandatoryGate || prefs.getBoolean(SecureOverlayService.KEY_UPDATE_MANDATORY, false)) {
            stateSubtitle.text = "Instala la actualización obligatoria antes de iniciar el receptor."
            return
        }
        if (!hasOverlayPermission()) {
            stateSubtitle.text = "Concede el permiso de ventana flotante para continuar."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        prefs.edit()
            .putBoolean(SecureOverlayService.KEY_USER_STOPPED, false)
            .putBoolean(SecureOverlayService.KEY_AUTO_START, true)
            .putString(SecureOverlayService.KEY_CONNECTION_STATE, ConnectionState.CONNECTING.name)
            .apply()
        try {
            val intent = Intent(this, SecureOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            if (userInitiated) stateSubtitle.text = "Iniciando el servicio protegido…"
        } catch (_: Exception) {
            prefs.edit()
                .putBoolean(SecureOverlayService.KEY_RUNNING, false)
                .putString(SecureOverlayService.KEY_CONNECTION_STATE, ConnectionState.CONNECTION_FAILED.name)
                .putString(SecureOverlayService.KEY_DETAIL, "service_start_failed")
                .apply()
        }
    }

    private fun stopReceiver(userInitiated: Boolean) {
        if (userInitiated) {
            prefs.edit()
                .putBoolean(SecureOverlayService.KEY_USER_STOPPED, true)
                .putBoolean(SecureOverlayService.KEY_AUTO_START, false)
                .apply()
        }
        stopService(Intent(this, SecureOverlayService::class.java))
        prefs.edit()
            .putBoolean(SecureOverlayService.KEY_RUNNING, false)
            .putBoolean(SecureOverlayService.KEY_BRIDGE, false)
            .putString(SecureOverlayService.KEY_CONNECTION_STATE, ConnectionState.DISCONNECTED.name)
            .putString(SecureOverlayService.KEY_DETAIL, "stopped")
            .apply()
        UpdateWorkScheduler.enqueueAfterSession(this)
        refreshConnectionState()
    }

    private fun refreshConnectionState() {
        val running = prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)
        val raw = prefs.getString(
            SecureOverlayService.KEY_CONNECTION_STATE,
            ConnectionState.DISCONNECTED.name
        ) ?: ConnectionState.DISCONNECTED.name
        val state = runCatching { ConnectionState.valueOf(raw) }
            .getOrDefault(ConnectionState.DISCONNECTED)
        val detail = prefs.getString(SecureOverlayService.KEY_DETAIL, "") ?: ""
        val display = when {
            !running || state == ConnectionState.DISCONNECTED ->
                Triple("Disconnected", "El receptor está detenido.", muted)
            state == ConnectionState.DETECTING ->
                Triple("Detecting", "Esperando la primera transmisión de Delta.", secondary)
            state == ConnectionState.CONNECTING ->
                Triple("Connecting", "Abriendo el canal remoto.", warning)
            state == ConnectionState.CONNECTED_WAITING ->
                Triple("Connected", "Canal abierto; esperando el primer frame válido de Delta.", warning)
            state == ConnectionState.PAIRED ->
                Triple("Paired", "Primer frame recibido. La sesión está activa.", connected)
            state == ConnectionState.RECONNECTING ->
                Triple("Reconnecting", "La conexión se interrumpió; reintentando.", warning)
            else ->
                Triple("Connection failed", humanize(detail), errorColor)
        }
        stateTitle.text = display.first
        stateSubtitle.text = display.second
        stateDot.background = oval(display.third)
        updateStatusPulse(running && state == ConnectionState.PAIRED)
        startButton.isEnabled = !running && !mandatoryGate
        stopButton.isEnabled = running
        startButton.alpha = if (startButton.isEnabled) 1f else 0.45f
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.45f
    }

    private fun humanize(code: String): String = when (code) {
        "pairing_token_invalid" -> "El código de acceso no es válido. Regenera el código y vuelve a vincular."
        "channel_rejected" -> "Supabase rechazó el canal. Comprueba el proyecto y el código."
        "network_error" -> "No se pudo abrir el WebSocket. Comprueba tu conexión."
        "service_start_failed" -> "Android no permitió iniciar el servicio protegido."
        "mandatory_update_required" -> "Instala la actualización obligatoria para reanudar."
        else -> "No se pudo establecer la conexión. Detalle: " + code.take(48)
    }

    private fun checkForUpdate(force: Boolean) {
        if (updateCheckInFlight || downloadInFlight) return
        if (prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)) {
            updateSubtitle.text = "La comprobación se pospone mientras el receptor está activo."
            return
        }
        updateCheckInFlight = true
        updateTitle.text = "Comprobando versión"
        updateSubtitle.text = "Consultando el manifiesto remoto…"
        updateButton.isEnabled = false
        io.execute {
            try {
                val manager = UpdateManager(this)
                val info = manager.checkLatest(force)
                val cached = if (info != null) manager.findVerifiedCachedFile(info) else null
                runOnUiThread {
                    updateCheckInFlight = false
                    updateButton.isEnabled = true
                    updateInfo = info
                    updateFile = cached
                    if (info == null) {
                        mandatoryGate = false
                        prefs.edit().putBoolean(SecureOverlayService.KEY_UPDATE_MANDATORY, false).apply()
                        updateTitle.text = "Aplicación actualizada"
                        updateSubtitle.text = "No hay una versión más reciente publicada."
                        updateProgress.visibility = View.GONE
                        updateButton.text = "Buscar actualizaciones"
                        updateButton.setOnClickListener { checkForUpdate(force = true) }
                        applyMandatoryGate()
                    } else {
                        mandatoryGate = info.mandatory &&
                            manager.currentVersionCode() < info.minimumSupportedVersionCode
                        prefs.edit().putBoolean(SecureOverlayService.KEY_UPDATE_MANDATORY, mandatoryGate).apply()
                        if (cached != null) renderUpdateReady(info)
                        else {
                            renderUpdateAvailable(info)
                            if (!prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)) downloadUpdate(info)
                        }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateCheckInFlight = false
                    updateButton.isEnabled = true
                    updateTitle.text = "No se pudo verificar"
                    updateSubtitle.text = error.message?.take(160)
                        ?: "El servicio de actualizaciones no está disponible."
                    updateButton.text = "Reintentar"
                    updateButton.setOnClickListener { checkForUpdate(force = true) }
                }
            }
        }
    }

    private fun renderUpdateAvailable(info: UpdateInfo) {
        updateTitle.text = if (info.mandatory) "Actualización obligatoria · v" + info.versionName
            else "Nueva versión · v" + info.versionName
        updateSubtitle.text = info.changelog
        updateButton.text = "Descargando automáticamente…"
        updateButton.isEnabled = false
        applyMandatoryGate()
    }

    private fun downloadUpdate(info: UpdateInfo) {
        if (downloadInFlight || prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)) return
        downloadInFlight = true
        updateProgress.visibility = View.VISIBLE
        updateProgress.isIndeterminate = false
        updateProgress.progress = 0
        updateTitle.text = "Preparando v" + info.versionName
        updateSubtitle.text = "Descarga y verificación de integridad…"
        updateButton.isEnabled = false
        io.execute {
            try {
                val file = UpdateManager(this).download(info) { downloaded, total ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgressUiAt >= 180L || downloaded == total) {
                        lastProgressUiAt = now
                        runOnUiThread {
                            val percent = if (total > 0L) {
                                ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            } else 0
                            updateProgress.progress = percent
                            updateSubtitle.text = "Descargando · " + percent + "%"
                        }
                    }
                }
                runOnUiThread {
                    downloadInFlight = false
                    updateFile = file
                    updateProgress.visibility = View.GONE
                    renderUpdateReady(info)
                }
            } catch (error: ReleaseSignatureMismatchException) {
                runOnUiThread {
                    downloadInFlight = false
                    updateProgress.visibility = View.GONE
                    updateTitle.text = "Migración inicial de firma"
                    updateSubtitle.text = "La APK de prueba y el release utilizan certificados distintos. Esta migración requiere desinstalar la versión de prueba una vez; las siguientes versiones firmadas podrán actualizarse en el sitio."
                    updateButton.isEnabled = true
                    updateButton.text = "Abrir release oficial"
                    updateButton.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    downloadInFlight = false
                    updateProgress.visibility = View.GONE
                    updateTitle.text = "Descarga no completada"
                    updateSubtitle.text = error.message?.take(150) ?: "No se pudo verificar el paquete."
                    updateButton.isEnabled = true
                    updateButton.text = "Reintentar"
                    updateButton.setOnClickListener { downloadUpdate(info) }
                }
            }
        }
    }

    private fun renderUpdateReady(info: UpdateInfo) {
        updateTitle.text = "v" + info.versionName + " verificada"
        updateSubtitle.text = "Integridad y firma verificadas. Se reemplazará esta versión sin borrar tus datos ni el emparejamiento; Android solicitará confirmación."
        updateProgress.visibility = View.GONE
        updateButton.isEnabled = true
        updateButton.text = "Instalar actualización"
        updateButton.setOnClickListener { installUpdate() }
        mandatoryGate = info.mandatory &&
            UpdateManager(this).currentVersionCode() < info.minimumSupportedVersionCode
        prefs.edit().putBoolean(SecureOverlayService.KEY_UPDATE_MANDATORY, mandatoryGate).apply()
        applyMandatoryGate()
    }

    private fun installUpdate() {
        val file = updateFile ?: return
        val info = updateInfo ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + packageName)
            ))
            updateSubtitle.text = "Permite instalar actualizaciones desde SilentHub y vuelve a pulsar Instalar."
            return
        }
        updateButton.isEnabled = false
        updateTitle.text = "Preparando instalación"
        updateSubtitle.text = "El instalador de Android solicitará confirmación."
        io.execute {
            try {
                UpdateManager(this).install(file, info)
                runOnUiThread {
                    updateTitle.text = "Confirmación de Android"
                    updateSubtitle.text = "Completa la instalación en el diálogo del sistema."
                    updateButton.isEnabled = true
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateTitle.text = "No se pudo iniciar la instalación"
                    updateSubtitle.text = error.message?.take(140) ?: "Error del instalador de Android."
                    updateButton.isEnabled = true
                }
            }
        }
    }

    private fun renderInstallStatus() {
        when (prefs.getString(SecureOverlayService.KEY_INSTALL_STATUS, "")) {
            "installed" -> {
                updateTitle.text = "Actualización instalada"
                updateSubtitle.text = "Android confirmó la instalación."
            }
            "failed" -> {
                updateTitle.text = "Instalación no completada"
                updateSubtitle.text = prefs.getString(
                    SecureOverlayService.KEY_INSTALL_ERROR,
                    "Error de instalación."
                )
            }
            "awaiting_user_confirmation" ->
                updateSubtitle.text = "Esperando confirmación en Android."
        }
    }

    private fun applyMandatoryGate() {
        val info = updateInfo
        mandatoryGate = prefs.getBoolean(SecureOverlayService.KEY_UPDATE_MANDATORY, false) ||
            (info != null && info.mandatory &&
                UpdateManager(this).currentVersionCode() < info.minimumSupportedVersionCode)
        if (mandatoryGate) {
            if (prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)) stopReceiver(userInitiated = false)
            startButton.isEnabled = false
            startButton.alpha = 0.45f
            stateSubtitle.text = "Una actualización obligatoria bloquea el receptor hasta instalarse."
        } else {
            refreshConnectionState()
        }
    }

    private fun maybeAnimate(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        view.animate()
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(75L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(130L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun animateEntrance(container: ViewGroup) {
        val enabled = ValueAnimator.areAnimatorsEnabled()
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (!enabled) {
                child.alpha = 1f
                child.translationY = 0f
                continue
            }
            child.alpha = 0f
            child.translationY = dp(12).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 24L)
                .setDuration(330L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun updateStatusPulse(active: Boolean) {
        statusPulse?.cancel()
        statusPulse = null
        stateDot.scaleX = 1f
        stateDot.scaleY = 1f
        stateDot.alpha = 1f
        if (!active || !ValueAnimator.areAnimatorsEnabled()) return

        statusPulse = ValueAnimator.ofFloat(1f, 1.22f).apply {
            duration = 1050L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                val scale = animation.animatedValue as Float
                stateDot.scaleX = scale
                stateDot.scaleY = scale
                stateDot.alpha = 1.45f - (scale * 0.36f)
            }
            start()
        }
    }

    private fun installedVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.5.0"
    }.getOrDefault("0.5.0")

    private fun card(hero: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = roundedGradient(
            if (hero) intArrayOf(Color.rgb(30, 40, 49), Color.rgb(17, 25, 33))
            else intArrayOf(Color.rgb(24, 32, 41), panel),
            dp(if (hero) 18 else 15),
            if (hero) Color.rgb(76, 91, 102) else stroke
        )
        elevation = dp(if (hero) 5 else 2).toFloat()
        clipToPadding = false
        clipChildren = false
    }

    private fun sectionHeader(title: String, subtitle: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val marker = View(this@MainActivity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(goldLight, gold)
                ).apply { cornerRadius = dp(2).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(28))
            }
            val labels = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, 0, 0)
                addView(label(title, 10f, goldLight, true).apply { letterSpacing = 0.16f })
                addView(label(subtitle, 11f, muted, false).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            }
            addView(marker)
            addView(labels)
        }

    private fun label(value: String, size: Float, color: Int, medium: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
            includeFontPadding = true
        }

    private fun actionButton(value: String, emphasized: Boolean, action: () -> Unit) =
        Button(this).apply {
            text = value
            textSize = 12f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (emphasized) Color.rgb(20, 24, 27) else primary)
            minHeight = dp(48)
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            background = rippleBackground(
                if (emphasized) intArrayOf(goldLight, gold)
                else intArrayOf(Color.rgb(46, 58, 68), Color.rgb(29, 39, 49)),
                if (emphasized) Color.rgb(226, 196, 145) else Color.rgb(84, 101, 115),
                dp(11)
            )
            stateListAnimator = null
            setOnClickListener {
                maybeAnimate(this)
                action()
            }
        }

    private fun textAction(value: String, action: () -> Unit) =
        label(value, 12f, secondary, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                maybeAnimate(this)
                action()
            }
        }

    private fun topMargin(value: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = value }

    private fun rounded(fill: Int, radius: Int, outline: Int) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), outline)
        }

    private fun roundedGradient(colors: IntArray, radius: Int, outline: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = radius.toFloat()
            setStroke(dp(1), outline)
        }

    private fun pillBackground(fill: Int, outline: Int) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(50).toFloat()
            setStroke(dp(1), outline)
        }

    private fun rippleBackground(fill: Int, outline: Int, radius: Int): Drawable =
        rippleBackground(intArrayOf(fill, fill), outline, radius)

    private fun rippleBackground(colors: IntArray, outline: Int, radius: Int): Drawable {
        val content = roundedGradient(colors, radius, outline)
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(42, 224, 232, 238)),
            content,
            rounded(Color.WHITE, radius, Color.TRANSPARENT)
        )
    }

    private fun oval(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(2), Color.argb(50, fill.red(), fill.green(), fill.blue()))
    }

    private fun Int.red() = Color.red(this)
    private fun Int.green() = Color.green(this)
    private fun Int.blue() = Color.blue(this)

    private fun maskedToken() = "••••••••••••••••••••••••"
    private fun hasOverlayPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 505
    }
}