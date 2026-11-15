package com.silenthub.capturelab

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Supabase Realtime protocol 2.0.0 receiver, including text arrays and binary user broadcasts. */
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
        private const val PROTOCOL_VERSION = "2.0.0"
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
                JSONArray().put(JSONObject.NULL).put(nextRef()).put("phoenix")
                    .put("heartbeat").put(JSONObject()).toString()
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
        val url = "wss://$PROJECT_REF.supabase.co/realtime/v1/websocket?apikey=$key&vsn=$PROTOCOL_VERSION"
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
                // Supabase Realtime protocol 2.0.0 uses positional JSON arrays:
                // [join_ref, ref, topic, event, payload].
                val join = JSONArray()
                    .put(JOIN_REF)
                    .put(JOIN_REF)
                    .put("realtime:$topic")
                    .put("phx_join")
                    .put(JSONObject().put("config", config))
                if (!webSocket.send(join.toString())) scheduleReconnect()
                handler.removeCallbacks(heartbeat)
                handler.postDelayed(heartbeat, HEARTBEAT_MS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!closed) handleTextPacket(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!closed) handleBinaryPacket(bytes.toByteArray())
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

    private fun handleTextPacket(text: String) {
        try {
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) {
                val packet = JSONArray(trimmed)
                if (packet.length() < 5) return
                val ref = packet.optString(1)
                val packetTopic = packet.optString(2)
                val event = packet.optString(3)
                val payload = packet.optJSONObject(4) ?: JSONObject()
                handleProtocolEvent(packetTopic, event, ref, payload)
            } else {
                // Keep compatibility with legacy protocol 1.0.0 text packets while
                // the socket itself negotiates the current 2.0.0 protocol.
                val packet = JSONObject(trimmed)
                handleProtocolEvent(
                    packet.optString("topic"),
                    packet.optString("event"),
                    packet.optString("ref"),
                    packet.optJSONObject("payload") ?: JSONObject()
                )
            }
        } catch (_: Exception) {
            // Ignore malformed protocol frames without killing the socket.
        }
    }

    private fun handleProtocolEvent(
        packetTopic: String,
        event: String,
        ref: String,
        payload: JSONObject
    ) {
        if (event == "phx_reply" &&
            packetTopic == "realtime:$topic" &&
            ref == JOIN_REF) {
            val status = payload.optString("status")
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
            handleBroadcast(payload.optString("event"), payload.optJSONObject("payload"))
        }
    }

    private fun handleBroadcast(event: String, frameJson: JSONObject?) {
        if (event != "frame" || !joined || frameJson == null) return
        val sequence = frameJson.optLong("sequence", -1L)
        // Ignore duplicates and out-of-order frames; the publisher uses a
        // wall-clock sequence base so executor restarts remain monotonic.
        if (sequence < 0 || sequence <= lastSequence) return
        val frame = parseFrame(frameJson)
        lastSequence = sequence
        onFrame(frame)
    }

    /**
     * Supabase Realtime 2.0.0 server broadcast binary layout:
     * type(0x04), topicLen, eventLen, metadataLen, encoding, topic, event,
     * metadata, payload. Encoding 1 means the user payload is UTF-8 JSON.
     */
    private fun handleBinaryPacket(bytes: ByteArray) {
        try {
            if (bytes.size < 5 || (bytes[0].toInt() and 0xff) != 0x04) return
            val topicLength = bytes[1].toInt() and 0xff
            val eventLength = bytes[2].toInt() and 0xff
            val metadataLength = bytes[3].toInt() and 0xff
            val payloadEncoding = bytes[4].toInt() and 0xff
            if (payloadEncoding != 1) return

            var offset = 5
            fun takeString(length: Int): String {
                require(length >= 0 && offset + length <= bytes.size)
                val value = String(bytes, offset, length, Charsets.UTF_8)
                offset += length
                return value
            }

            val packetTopic = takeString(topicLength)
            val event = takeString(eventLength)
            require(offset + metadataLength <= bytes.size)
            offset += metadataLength
            if (offset > bytes.size) return
            val payloadText = String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
            val payload = JSONObject(payloadText)
            if (packetTopic == "realtime:$topic" && joined) {
                handleBroadcast(event, payload)
            }
        } catch (_: Exception) {
            // Invalid or unsupported binary broadcasts are discarded safely.
        }
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
                    id = source.optString("id", source.optString("name", index.toString())).take(32),
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
            capturedAtEpochMs = json.optLong("capturedAtMs", System.currentTimeMillis())
                .takeIf { it > 0L } ?: System.currentTimeMillis(),
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