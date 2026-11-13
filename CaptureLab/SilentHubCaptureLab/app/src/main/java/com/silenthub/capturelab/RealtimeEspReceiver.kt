package com.silenthub.capturelab

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Supabase Realtime protocol 1.0.0 receiver. Join acknowledgement is not app pairing. */
internal class SupabaseRealtimeEspReceiver(
    private val pairingToken: String,
    private val onFrame: (ExternalEspFrame) -> Unit,
    private val onStatus: (String) -> Unit
) : Closeable {
    companion object {
        private const val PROJECT_REF = "qmzcpguqqskqlrczdzsu"
        private const val PUBLISHABLE_KEY = "sb_publishable_OVQH-qo6gtnOWPkAvFCiCA_VSRUfruG"
        private const val HEARTBEAT_MS = 20_000L
        private const val MAX_PLAYERS = 5
        private const val JOIN_REF = "1"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val topic = "silenthub-esp-" + sha256(pairingToken).take(32)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var joined = false
    private var hasConnectedBefore = false
    private var refCounter = 2
    private var reconnectDelayMs = 1_000L
    private var lastSequence = -1L

    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed || socket == null) return
            val sent = socket?.send(
                JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                    .put("payload", JSONObject()).put("ref", nextRef()).toString()
            ) ?: false
            if (sent) handler.postDelayed(this, HEARTBEAT_MS) else scheduleReconnect()
        }
    }
    private val reconnect = Runnable { if (!closed) connect() }

    fun start() {
        if (!pairingToken.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            onStatus("pairing_token_invalid")
            return
        }
        connect()
    }

    private fun connect() {
        if (closed) return
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(heartbeat)
        joined = false
        onStatus(if (hasConnectedBefore) "reconnecting" else "connecting")

        val key = URLEncoder.encode(PUBLISHABLE_KEY, "UTF-8")
        val url = "wss://$PROJECT_REF.supabase.co/realtime/v1/websocket?apikey=$key&vsn=1.0.0"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) {
                    webSocket.close(1000, "service stopped")
                    return
                }
                socket = webSocket
                val config = JSONObject()
                    .put("broadcast", JSONObject().put("ack", false).put("self", false))
                    .put("presence", JSONObject().put("enabled", false))
                    .put("postgres_changes", JSONArray())
                    .put("private", false)
                val join = JSONObject()
                    .put("topic", "realtime:$topic")
                    .put("event", "phx_join")
                    .put("payload", JSONObject().put("config", config))
                    .put("ref", JOIN_REF)
                    .put("join_ref", JOIN_REF)
                if (!webSocket.send(join.toString())) scheduleReconnect()
                handler.removeCallbacks(heartbeat)
                handler.postDelayed(heartbeat, HEARTBEAT_MS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed) return
                try {
                    val packet = JSONObject(text)
                    val event = packet.optString("event")
                    val packetTopic = packet.optString("topic")
                    if (event == "phx_reply" &&
                        packetTopic == "realtime:$topic" &&
                        packet.optString("ref") == JOIN_REF) {
                        val status = packet.optJSONObject("payload")?.optString("status")
                        if (status == "ok") {
                            joined = true
                            hasConnectedBefore = true
                            reconnectDelayMs = 1_000L
                            onStatus("connected")
                        } else {
                            joined = false
                            onStatus("channel_rejected")
                            scheduleReconnect()
                        }
                        return
                    }
                    if (event == "phx_error" || event == "phx_close") {
                        joined = false
                        onStatus("reconnecting")
                        scheduleReconnect()
                        return
                    }
                    if (event == "broadcast" && packetTopic == "realtime:$topic" && joined) {
                        val broadcast = packet.optJSONObject("payload") ?: return
                        if (broadcast.optString("type") != "broadcast" ||
                            broadcast.optString("event") != "frame") return
                        val frameJson = broadcast.optJSONObject("payload") ?: return
                        val sequence = frameJson.optLong("sequence", -1L)
                        if (sequence < 0 || sequence == lastSequence) return
                        val frame = parseFrame(frameJson)
                        lastSequence = sequence
                        onFrame(frame)
                    }
                } catch (_: Exception) {
                    // Malformed packets never change connection state.
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) {
                    joined = false
                    onStatus("reconnecting")
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                if (!closed) {
                    joined = false
                    onStatus(if (response?.code == 401 || response?.code == 403) "channel_rejected" else "reconnecting")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun parseFrame(json: JSONObject): ExternalEspFrame {
        val options = json.optJSONObject("options") ?: JSONObject()
        val sourcePlayers = json.optJSONArray("players") ?: JSONArray()
        val players = ArrayList<EspPlayer>(min(sourcePlayers.length(), MAX_PLAYERS))
        for (index in 0 until min(sourcePlayers.length(), MAX_PLAYERS)) {
            val source = sourcePlayers.optJSONObject(index) ?: continue
            val anchor = source.optJSONObject("anchor")?.let {
                val x = unit(it.optDouble("x", Double.NaN))
                val y = unit(it.optDouble("y", Double.NaN))
                if (x.isFinite() && y.isFinite()) EspPoint(x, y) else null
            }
            val box = source.optJSONObject("box")?.let {
                val l = unit(it.optDouble("l", Double.NaN))
                val t = unit(it.optDouble("t", Double.NaN))
                val r = unit(it.optDouble("r", Double.NaN))
                val b = unit(it.optDouble("b", Double.NaN))
                if (l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite() && r > l && b > t) {
                    EspRect(l, t, r, b)
                } else null
            }
            val tracer = source.optJSONObject("tracer")?.let {
                val values = listOf("x1", "y1", "x2", "y2").map { key ->
                    unit(it.optDouble(key, Double.NaN))
                }
                if (values.all { it.isFinite() }) EspTracer(values[0], values[1], values[2], values[3]) else null
            }
            val arrow = source.optJSONObject("arrow")?.let {
                val x = unit(it.optDouble("x", Double.NaN))
                val y = unit(it.optDouble("y", Double.NaN))
                val angle = it.optDouble("angle", Double.NaN).toFloat()
                if (x.isFinite() && y.isFinite() && angle.isFinite()) EspArrow(x, y, angle) else null
            }
            players.add(
                EspPlayer(
                    name = source.optString("name", "Player").take(32),
                    distance = source.optInt("distance", 0).coerceIn(0, 9999),
                    anchor = anchor,
                    box = box,
                    tracer = tracer,
                    arrow = arrow
                )
            )
        }
        return ExternalEspFrame(
            enabled = json.optBoolean("enabled", false),
            glow = options.optBoolean("glow", false),
            showName = options.optBoolean("name", false),
            showDistance = options.optBoolean("distance", false),
            showLines = options.optBoolean("lines", false),
            showArrows = options.optBoolean("arrows", false),
            players = players,
            receivedAt = SystemClock.elapsedRealtime()
        )
    }

    private fun unit(value: Double): Float =
        if (value.isFinite()) value.toFloat().coerceIn(0f, 1f) else Float.NaN

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun nextRef(): String {
        val result = refCounter.toString()
        refCounter = if (refCounter >= Int.MAX_VALUE - 10) 2 else refCounter + 1
        return result
    }

    private fun scheduleReconnect() {
        if (closed) return
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(heartbeat)
        socket?.cancel()
        socket = null
        handler.postDelayed(reconnect, reconnectDelayMs)
        reconnectDelayMs = min(30_000L, reconnectDelayMs * 2L)
    }

    override fun close() {
        if (closed) return
        closed = true
        joined = false
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(heartbeat)
        socket?.close(1000, "service stopped")
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        onStatus("stopped")
    }
}