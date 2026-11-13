#!/usr/bin/env python3
"""Apply reviewed 0.5.0 runtime, low-I/O, watchdog and mandatory-update patches."""
from pathlib import Path
import sys

project = Path(sys.argv[1])
root = project / "app/src/main/java/com/silenthub/capturelab"

def edit(path: Path, pairs):
    text = path.read_text()
    for old, new in pairs:
        if old not in text:
            raise SystemExit(f"Patch anchor missing in {path.name}: {old[:75]!r}")
        text = text.replace(old, new, 1)
    path.write_text(text)

service = root / "SecureOverlayService.kt"
edit(service, [
    ("import android.os.IBinder\n", "import android.os.IBinder\nimport android.os.SystemClock\nimport android.os.Handler\nimport android.os.Looper\n"),
    ("    private var realtimeReceiver: SupabaseRealtimeEspReceiver? = null\n", """    private var realtimeReceiver: SupabaseRealtimeEspReceiver? = null
    @Volatile private var lastFrameStateWriteAt = 0L
    private val stateHandler = Handler(Looper.getMainLooper())
    private val serviceHeartbeat = object : Runnable {
        override fun run() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_SERVICE_HEARTBEAT, System.currentTimeMillis())
                .apply()
            stateHandler.postDelayed(this, 15_000L)
        }
    }
"""),
    ("""        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }""", """        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_USER_STOPPED, true)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }"""),
    ("""        startAsForegroundService()
        if (overlayView == null && !showSecureOverlay()) {""", """        startAsForegroundService()
        stateHandler.removeCallbacks(serviceHeartbeat)
        stateHandler.post(serviceHeartbeat)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_USER_STOPPED, false)
            .putLong(KEY_SERVICE_HEARTBEAT, System.currentTimeMillis())
            .apply()
        if (overlayView == null && !showSecureOverlay()) {"""),
    ("""                onFrame = { frame ->
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putLong(KEY_LAST_FRAME, System.currentTimeMillis())
                        .putInt(KEY_LAST_PLAYER_COUNT, frame.players.size)
                        .apply()
                    overlayView?.accept(frame)
                },""", """                onFrame = { frame ->
                    // Persist health no more than once per 1.2s; never do disk I/O for each visual frame.
                    val nowElapsed = SystemClock.elapsedRealtime()
                    if (nowElapsed - lastFrameStateWriteAt >= 1200L) {
                        lastFrameStateWriteAt = nowElapsed
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putLong(KEY_LAST_FRAME, System.currentTimeMillis())
                            .putInt(KEY_LAST_PLAYER_COUNT, frame.players.size)
                            .apply()
                    }
                    overlayView?.accept(frame)
                },"""),
    ("""    override fun onDestroy() {
        realtimeReceiver?.close()""", """    override fun onDestroy() {
        stateHandler.removeCallbacks(serviceHeartbeat)
        realtimeReceiver?.close()"""),
    ('        const val KEY_LAST_PLAYER_COUNT = "last_player_count"\n', '        const val KEY_LAST_PLAYER_COUNT = "last_player_count"\n        const val KEY_SERVICE_HEARTBEAT = "service_heartbeat_at"\n')
])

main_path = root / "MainActivity.kt"
main = main_path.read_text()
def r(old, new, name):
    global main
    if old not in main:
        raise SystemExit(f"Patch anchor missing in MainActivity ({name}): {old[:75]!r}")
    main = main.replace(old, new, 1)

r("""        override fun run() {
            if (::statusView.isInitialized) refreshStatus()
            if (::statusView.isInitialized) statusView.postDelayed(this, 1500L)
        }""",
  """        override fun run() {
            if (::statusView.isInitialized) {
                refreshStatus()
                checkForUpdate(force = false)
                statusView.postDelayed(this, 1500L)
            }
        }""", "status poll")
r('''        if (!BuildConfig.DEBUG) checkForUpdate(force = false)
    }

    override fun onPause()''',
  '''        checkForUpdate(force = false)
    }

    override fun onPause()''', "resume updater")
r('''        val prefs = getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)) startReceiver(silent = true)''',
  '''        val prefs = getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(SecureOverlayService.KEY_USER_STOPPED, false)) return
        val running = prefs.getBoolean(SecureOverlayService.KEY_RUNNING, false)
        val lastHeartbeat = prefs.getLong(SecureOverlayService.KEY_SERVICE_HEARTBEAT, 0L)
        val heartbeatStale = lastHeartbeat == 0L || System.currentTimeMillis() - lastHeartbeat > SERVICE_HEARTBEAT_TIMEOUT
        if (!running || heartbeatStale) startReceiver(silent = true)''', "service watchdog")
r('''        val intent = Intent(this, SecureOverlayService::class.java)
        try {''',
  '''        getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(SecureOverlayService.KEY_USER_STOPPED, false)
            .apply()
        val intent = Intent(this, SecureOverlayService::class.java)
        try {''', "clear manual stop on start")
r('''    private fun stopReceiver() {
        stopService(Intent(this, SecureOverlayService::class.java))
        serviceButton.text = "Iniciar receptor protegido"
        refreshStatus()
    }''',
  '''    private fun stopReceiver(userInitiated: Boolean = true) {
        if (userInitiated) {
            getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(SecureOverlayService.KEY_USER_STOPPED, true)
                .apply()
        }
        stopService(Intent(this, SecureOverlayService::class.java))
        serviceButton.text = "Iniciar receptor protegido"
        refreshStatus()
    }''', "remember manual stop")
r('''    private fun rotatePairingToken() {
        stopReceiver()
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val token = android.util.Base64.encodeToString(bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE).edit()
            .putString(SecureOverlayService.KEY_PAIRING_TOKEN, token)
            .putLong(SecureOverlayService.KEY_LAST_FRAME, 0L)
            .putString(SecureOverlayService.KEY_DETAIL, "pairing_token_rotated")
            .apply()
        pairRevealed = true
        pairingCodeView.text = token
        pairRevealButton.text = "Ocultar código"
        refreshStatus()
    }''',
  '''    private fun rotatePairingToken() {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val token = android.util.Base64.encodeToString(bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        getSharedPreferences(SecureOverlayService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(SecureOverlayService.KEY_USER_STOPPED, false)
            .putBoolean(SecureOverlayService.KEY_RUNNING, false)
            .putBoolean(SecureOverlayService.KEY_BRIDGE, false)
            .putString(SecureOverlayService.KEY_PAIRING_TOKEN, token)
            .putLong(SecureOverlayService.KEY_LAST_FRAME, 0L)
            .putLong(SecureOverlayService.KEY_SERVICE_HEARTBEAT, 0L)
            .putString(SecureOverlayService.KEY_DETAIL, "pairing_token_rotated")
            .apply()
        stopService(Intent(this, SecureOverlayService::class.java))
        pairRevealed = true
        pairingCodeView.text = token
        pairRevealButton.text = "Ocultar código"
        statusView.text = "Código renovado. El receptor se reiniciará con el nuevo vínculo."
        statusView.postDelayed({ startReceiver(silent = true) }, 450L)
    }''', "rotate pairing token")
r('''        if (BuildConfig.DEBUG || checkInFlight || updateGate != null) return''',
  '''        if (checkInFlight || updateGate != null) return''', "allow migration out of debug builds")
r('''                        stopReceiver()
                        showUpdateGate(info)''',
  '''                        stopReceiver(userInitiated = false)
                        showUpdateGate(info)''', "don't remember mandatory update as manual stop")
r('''                }.onFailure { error ->
                    statusView.text = "No se pudo comprobar la actualización. Se reintentará al volver a abrir."''',
  '''                }.onFailure { error ->
                    prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                    statusView.text = "No se pudo comprobar la actualización. Se reintentará más tarde."''', "backoff update check")
r('''        wrapper.alpha = 0f
        wrapper.animate().alpha(1f).setDuration(220L).start()
    }

    private fun canInstallPackages()''',
  '''        wrapper.alpha = 0f
        wrapper.animate().alpha(1f).setDuration(220L).start()
        if (!updater.isCurrentInstallSignedWithReleaseKey()) {
            state.text = "Migración inicial: esta versión de prueba tiene otra firma. Abre el release oficial, desinstala esta versión y después instala la APK oficial. Las siguientes actualizaciones serán compatibles y se prepararán automáticamente."
            installButton.text = "Abrir release oficial v" + info.versionName
            installButton.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
            }
        }
    }

    private fun canInstallPackages()''', "one-time signing migration")
r('''        private const val UPDATE_CHECK_INTERVAL = 6L * 60L * 60L * 1000L''',
  '''        private const val UPDATE_CHECK_INTERVAL = 30L * 60L * 1000L
        private const val SERVICE_HEARTBEAT_TIMEOUT = 45_000L''', "polling intervals")
main_path.write_text(main)

update = root / "UpdateManager.kt"
edit(update, [
    ('    val sizeBytes: Long\n)', '    val sizeBytes: Long,\n    val releaseUrl: String\n)'),
    ('                            sizeBytes = latest.optLong("sizeBytes", 0L)\n', '                            sizeBytes = latest.optLong("sizeBytes", 0L),\n                            releaseUrl = latest.optString("releaseUrl", "https://github.com/Silent-lua/Workspace/releases/latest")\n'),
    ('                            || info.sizeBytes !in 1L..MAX_APK_BYTES) {', '                            || info.sizeBytes !in 1L..MAX_APK_BYTES\n                            || !info.releaseUrl.startsWith("https://github.com/Silent-lua/Workspace/releases/")) {')
])

readme = project / "README.md"
if readme.exists():
    readme.write_text(readme.read_text().replace("12 frames/second", "10 frames/second"))
print("SilentHub External ESP 0.5.0 runtime and update patches applied")
