package com.silenthub.capturelab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

internal data class EspPoint(val x: Float, val y: Float)
internal data class EspRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class EspTracer(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
internal data class EspArrow(val x: Float, val y: Float, val angle: Float)
internal data class EspPlayer(
    val name: String,
    val distance: Int,
    val anchor: EspPoint?,
    val box: EspRect?,
    val tracer: EspTracer?,
    val arrow: EspArrow?
)
internal data class ExternalEspFrame(
    val enabled: Boolean,
    val glow: Boolean,
    val showName: Boolean,
    val showDistance: Boolean,
    val showLines: Boolean,
    val showArrows: Boolean,
    val players: List<EspPlayer>,
    val receivedAt: Long = SystemClock.elapsedRealtime()
)

/** A loopback-only HTTP receiver. It never binds to Wi-Fi/LAN interfaces. */
internal class LocalEspBridgeServer(
    private val onFrame: (ExternalEspFrame) -> Unit
) : Closeable {
    companion object {
        const val PORT = 18273
        private const val MAX_BODY_BYTES = 64 * 1024
    }

    private val running = AtomicBoolean(false)
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val clients = Executors.newFixedThreadPool(2)
    @Volatile private var serverSocket: ServerSocket? = null

    @Synchronized
    fun start() {
        if (running.get()) return
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8)
        } catch (error: Exception) {
            try { socket.close() } catch (_: Exception) { }
            throw error
        }
        serverSocket = socket
        running.set(true)
        Thread({ acceptLoop(socket) }, "SilentHub-ESP-Accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running.get()) {
            try {
                val client = listener.accept()
                clients.execute { handleClient(client) }
            } catch (_: IOException) {
                if (running.get()) continue else break
            } catch (_: RuntimeException) {
                if (!running.get()) break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 2000
            val input = socket.getInputStream()
            val requestLine = readAsciiLine(input) ?: return
            val requestParts = requestLine.split(' ')
            if (requestParts.size < 2) {
                respond(socket, 400, "{\"ok\":false,\"error\":\"bad_request\"}")
                return
            }
            val method = requestParts[0].uppercase()
            val path = requestParts[1].substringBefore('?')
            val headers = HashMap<String, String>()
            var headerCount = 0
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                headerCount++
                if (headerCount > 64) {
                    respond(socket, 431, "{\"ok\":false,\"error\":\"headers_too_large\"}")
                    return
                }
                val split = line.indexOf(':')
                if (split > 0) headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
            }

            when {
                method == "GET" && path == "/health" -> {
                    respond(socket, 200, "{\"ok\":true,\"protocol\":1,\"port\":" + PORT + "}")
                }
                method == "GET" && path == "/hello" -> {
                    respond(socket, 200, JSONObject().put("ok", true).put("protocol", 1).put("token", token).toString())
                }
                method == "POST" && path == "/esp" -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
                        respond(socket, 413, "{\"ok\":false,\"error\":\"invalid_content_length\"}")
                        return
                    }
                    val bodyBytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = input.read(bodyBytes, offset, contentLength - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    if (offset != contentLength) {
                        respond(socket, 400, "{\"ok\":false,\"error\":\"incomplete_body\"}")
                        return
                    }
                    val json = try {
                        JSONObject(String(bodyBytes, Charsets.UTF_8))
                    } catch (_: Exception) {
                        respond(socket, 400, "{\"ok\":false,\"error\":\"invalid_json\"}")
                        return
                    }
                    val suppliedToken = json.optString("token", "")
                    if (!constantTimeEquals(token, suppliedToken)) {
                        respond(socket, 401, "{\"ok\":false,\"error\":\"unauthorized\"}")
                        return
                    }
                    if (json.optInt("protocol", 0) != 1 || json.optString("type") != "frame") {
                        respond(socket, 400, "{\"ok\":false,\"error\":\"unsupported_protocol\"}")
                        return
                    }
                    try {
                        onFrame(parseFrame(json))
                    } catch (_: Exception) {
                        respond(socket, 400, "{\"ok\":false,\"error\":\"invalid_frame\"}")
                        return
                    }
                    respond(socket, 204, "")
                }
                else -> respond(socket, 404, "{\"ok\":false,\"error\":\"not_found\"}")
            }
        }
    }

    private fun parseFrame(json: JSONObject): ExternalEspFrame {
        val options = json.optJSONObject("options") ?: JSONObject()
        val sourcePlayers = json.optJSONArray("players") ?: JSONArray()
        val players = ArrayList<EspPlayer>(min(sourcePlayers.length(), 8))
        for (index in 0 until min(sourcePlayers.length(), 8)) {
            val source = sourcePlayers.optJSONObject(index) ?: continue
            val anchorJson = source.optJSONObject("anchor")
            val anchor = anchorJson?.let {
                EspPoint(unit(it.optDouble("x", Double.NaN)), unit(it.optDouble("y", Double.NaN)))
                    .takeIf { point -> point.x.isFinite() && point.y.isFinite() }
            }
            val boxJson = source.optJSONObject("box")
            val box = boxJson?.let {
                val left = unit(it.optDouble("l", Double.NaN))
                val top = unit(it.optDouble("t", Double.NaN))
                val right = unit(it.optDouble("r", Double.NaN))
                val bottom = unit(it.optDouble("b", Double.NaN))
                if (left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && right > left && bottom > top) {
                    EspRect(left, top, right, bottom)
                } else null
            }
            val tracerJson = source.optJSONObject("tracer")
            val tracer = tracerJson?.let {
                val values = listOf("x1", "y1", "x2", "y2").map { key -> unit(it.optDouble(key, Double.NaN)) }
                if (values.all { value -> value.isFinite() }) EspTracer(values[0], values[1], values[2], values[3]) else null
            }
            val arrowJson = source.optJSONObject("arrow")
            val arrow = arrowJson?.let {
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
            players = players
        )
    }

    private fun unit(value: Double): Float = if (value.isFinite()) value.toFloat().coerceIn(0f, 1f) else Float.NaN

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
            if (value == 10) break
            if (value != 13) bytes.write(value)
            if (bytes.size() > 8192) throw IOException("HTTP line too long")
        }
        return bytes.toString(Charsets.US_ASCII.name())
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val header = "HTTP/1.1 " + status + " " + reason + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "Content-Length: " + bytes.size + "\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            if (bytes.isNotEmpty()) write(bytes)
            flush()
        }
    }

    @Synchronized
    override fun close() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        clients.shutdownNow()
    }
}

/** Screen-protected Canvas renderer. All elements use a single fixed red. */
internal class LiveEspOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var currentFrame: ExternalEspFrame? = null

    private val boxGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        alpha = 75
        setShadowLayer(7f * density, 0f, 0f, Color.RED)
    }
    private val boxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val tracerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 8, 8, 12)
        style = Paint.Style.FILL
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 12f * density
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val labelPadding = 5f * density

    private val freshnessTick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 180L)
        }
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
        handler.post(freshnessTick)
    }

    fun accept(frame: ExternalEspFrame) {
        currentFrame = frame
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = currentFrame ?: return
        if (!frame.enabled || SystemClock.elapsedRealtime() - frame.receivedAt > 850L) return
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()

        for (player in frame.players) {
            val box = player.box
            if (frame.showLines) {
                player.tracer?.let { line ->
                    canvas.drawLine(line.x1 * w, line.y1 * h, line.x2 * w, line.y2 * h, tracerLine)
                }
            }
            if (frame.glow && box != null) {
                val rect = RectF(box.left * w, box.top * h, box.right * w, box.bottom * h)
                canvas.drawRect(rect, boxGlow)
                canvas.drawRect(rect, boxLine)
            }
            if (frame.showName || frame.showDistance) {
                val labelLines = ArrayList<String>(2)
                if (frame.showName && player.name.isNotBlank()) labelLines.add(player.name)
                if (frame.showDistance) labelLines.add(player.distance.toString() + "m")
                if (labelLines.isNotEmpty()) {
                    val anchorPoint = player.anchor
                    val anchorX = box?.let { (it.left + it.right) * 0.5f * w }
                        ?: anchorPoint?.x?.times(w) ?: Float.NaN
                    val anchorY = box?.let { it.top * h }
                        ?: anchorPoint?.y?.times(h) ?: Float.NaN
                    if (anchorX.isFinite() && anchorY.isFinite()) {
                        drawLabel(canvas, labelLines, anchorX, anchorY)
                    }
                }
            }
            if (frame.showArrows) {
                player.arrow?.let { arrow -> drawArrow(canvas, arrow, w, h) }
            }
        }
    }

    private fun drawLabel(canvas: Canvas, lines: List<String>, anchorX: Float, anchorY: Float) {
        val lineHeight = 13f * density
        val widest = lines.maxOfOrNull { labelText.measureText(it) } ?: return
        val left = (anchorX - widest * 0.5f - labelPadding).coerceIn(2f, max(2f, width - widest - 2f * labelPadding))
        val top = (anchorY - (lines.size * lineHeight) - 7f * density).coerceAtLeast(2f)
        val right = min(width - 2f, left + widest + 2f * labelPadding)
        val bottom = top + lines.size * lineHeight + 2f * density
        canvas.drawRoundRect(RectF(left, top, right, bottom), 4f * density, 4f * density, labelBackground)
        var baseline = top + lineHeight
        for (line in lines) {
            canvas.drawText(line, left + labelPadding, baseline, labelText)
            baseline += lineHeight
        }
    }

    private fun drawArrow(canvas: Canvas, arrow: EspArrow, screenWidth: Float, screenHeight: Float) {
        val centerX = arrow.x * screenWidth
        val centerY = arrow.y * screenHeight
        val size = 9f * density
        val path = Path().apply {
            moveTo(0f, -size)
            lineTo(-size * 0.72f, size * 0.75f)
            lineTo(0f, size * 0.35f)
            lineTo(size * 0.72f, size * 0.75f)
            close()
        }
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(arrow.angle)
        canvas.drawPath(path, arrowFill)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(freshnessTick)
        currentFrame = null
        super.onDetachedFromWindow()
    }
}