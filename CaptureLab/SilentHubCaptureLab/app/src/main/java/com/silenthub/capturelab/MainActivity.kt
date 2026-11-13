package com.silenthub.capturelab

import android.Manifest
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

    private val bg = Color.rgb(8, 9, 11)
    private val panel = Color.rgb(18, 20, 23)
    private val panelRaised = Color.rgb(25, 28, 32)
    private val stroke = Color.rgb(47, 51, 57)
    private val primary = Color.rgb(241, 243, 245)
    private val secondary = Color.rgb(172, 179, 187)
    private val muted = Color.rgb(112, 120, 130)
    private val gold = Color.rgb(190, 160, 105)
    private val connected = Color.rgb(126, 183, 150)
    private val warning = Color.rgb(195, 160, 103)
    private val errorColor = Color.rgb(195, 112, 108)

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
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(this).apply {
            text = "S"
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(gold)
            gravity = Gravity.CENTER
            background = rounded(panelRaised, dp(10), stroke)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("SILENTHUB", 11f, secondary, true).apply { letterSpacing = 0.18f })
            addView(label("EXTERNAL", 9f, muted, true).apply { letterSpacing = 0.2f })
        }
        header.addView(mark)
        header.addView(brand)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(label("v" + installedVersionName(), 11f, muted, false))
        content.addView(header)

        content.addView(label("Capture companion", 25f, primary, true), topMargin(dp(30)))
        content.addView(label(
            "Secure overlay  ·  Remote pairing  ·  Background receiver",
            12f, muted, false
        ), topMargin(dp(5)))

        val stateCard = card()
        val stateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stateDot = View(this).apply {
            background = oval(muted)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        val stateText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        stateTitle = label("Disconnected", 15f, primary, true)
        stateSubtitle = label("El receptor está detenido.", 12f, muted, false).apply {
            setPadding(0, dp(4), 0, 0)
        }
        stateText.addView(stateTitle)
        stateText.addView(stateSubtitle)
        stateRow.addView(stateDot)
        stateRow.addView(stateText)
        stateCard.addView(stateRow)
        content.addView(stateCard, topMargin(dp(24)))

        content.addView(section("PAIRING"), topMargin(dp(26)))
        val pairCard = card()
        pairCard.addView(label("Código de emparejamiento", 14f, primary, true))
        pairCard.addView(label(
            "Cópialo una vez en Delta. Paired se confirma al recibir el primer frame válido.",
            12f, muted, false
        ), topMargin(dp(5)))
        pairingValue = label(maskedToken(), 13f, secondary, true).apply {
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.06f
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = rounded(panelRaised, dp(8), stroke)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        pairCard.addView(pairingValue, topMargin(dp(12)))
        val pairButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        revealButton = textAction("Revelar") {
            pairingRevealed = !pairingRevealed
            refreshPairingToken()
        }
        val copyButton = actionButton("Copiar código", true) { copyToken() }
        pairButtons.addView(revealButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        pairButtons.addView(copyButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            leftMargin = dp(8)
        })
        pairCard.addView(pairButtons, topMargin(dp(10)))
        pairCard.addView(textAction("Regenerar código de acceso") { confirmTokenRotation() }, topMargin(dp(7)))
        content.addView(pairCard, topMargin(dp(10)))

        content.addView(section("RECEIVER"), topMargin(dp(26)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = actionButton("Iniciar receptor", true) { startReceiver(userInitiated = true) }
        stopButton = actionButton("Detener", false) { stopReceiver(userInitiated = true) }
        controls.addView(startButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(8)
        })
        content.addView(controls, topMargin(dp(10)))
        content.addView(label(
            "El servicio permanece activo con una notificación del sistema. Detenerlo aquí o desde la notificación desactiva la reanudación automática.",
            11f, muted, false
        ), topMargin(dp(8)))

        content.addView(section("UPDATES"), topMargin(dp(26)))
        val updateCard = card()
        updateTitle = label("Actualizaciones automáticas", 14f, primary, true)
        updateSubtitle = label("Consultando el manifiesto de publicación…", 12f, muted, false)
        updateProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(gold)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(stroke)
        }
        updateButton = actionButton("Buscar actualizaciones", false) { checkForUpdate(force = true) }
        updateCard.addView(updateTitle)
        updateCard.addView(updateSubtitle, topMargin(dp(5)))
        updateCard.addView(updateProgress, topMargin(dp(12)))
        updateCard.addView(updateButton, topMargin(dp(12)))
        content.addView(updateCard, topMargin(dp(10)))

        content.addView(section("ENGINEERING"), topMargin(dp(26)))
        val note = card()
        note.addView(label("Capture-safe rendering", 14f, primary, true))
        note.addView(label(
            "FLAG_SECURE se conserva. El Canvas invalida al llegar un frame y al vencer su tiempo de vida; no mantiene un bucle de refresco cuando no hay datos.",
            12f, muted, false
        ), topMargin(dp(6)))
        note.addView(label(
            "Las actualizaciones se descargan y verifican fuera del hilo principal. Android muestra su propio diálogo de instalación.",
            12f, muted, false
        ), topMargin(dp(10)))
        content.addView(note, topMargin(dp(10)))

        content.addView(label(
            "SILENTHUB  ·  ENGINEERING BUILD",
            9f, muted, true
        ).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        }, topMargin(dp(30)))

        scroll.addView(content)
        setContentView(scroll)
        refreshPairingToken()
        refreshConnectionState()
        renderInstallStatus()
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
        updateSubtitle.text = "SHA-256 y certificado de firma comprobados. Android solicitará confirmación para instalar."
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
                UpdateManager(this).install(file)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            android.animation.ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 0.72f
            view.animate().alpha(1f).setDuration(140L).start()
        }
    }

    private fun installedVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.5.0"
    }.getOrDefault("0.5.0")

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(panel, dp(12), stroke)
    }

    private fun section(value: String) = label(value, 10f, gold, true).apply {
        letterSpacing = 0.18f
    }

    private fun label(value: String, size: Float, color: Int, medium: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun actionButton(value: String, emphasized: Boolean, action: () -> Unit) =
        Button(this).apply {
            text = value
            textSize = 13f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (emphasized) bg else secondary)
            background = rounded(if (emphasized) gold else panelRaised, dp(8), if (emphasized) gold else stroke)
            minHeight = dp(44)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                maybeAnimate(this)
                action()
            }
        }

    private fun textAction(value: String, action: () -> Unit) =
        label(value, 12f, secondary, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(3))
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

    private fun rounded(fill: Int, radius: Int, outline: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), outline)
    }

    private fun oval(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun maskedToken() = "••••••••••••••••••••••••"
    private fun hasOverlayPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 505
    }
}