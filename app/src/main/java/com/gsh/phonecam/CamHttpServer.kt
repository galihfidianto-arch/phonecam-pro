package com.gsh.phonecam

import android.content.Context
import android.util.Base64
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * HTTP server MJPEG di HP — pintu masuk PC/OBS:
 *
 *   GET /mjpeg           → stream MJPEG (multipart/x-mixed-replace)
 *   GET /snapshot.jpg    → 1 frame JPEG
 *   GET /control?cmd=X   → kontrol kamera (fitur Pro):
 *                          torch=on|off, camera=front|back, zoom=1..5
 *   GET /status          → JSON: fps, resolusi, passcode aktif
 *   GET /                → halaman viewer HTML (buka di browser PC)
 *
 * Semua endpoint (kecuali /status) dicek passcode via Basic Auth bila diaktifkan.
 */
class CamHttpServer(
    private val ctx: Context,
    private val engine: CameraEngine,
    private val port: Int
) {
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var server: com.sun.net.httpserver.HttpServer? = null

    fun start() {
        running = true
        server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress(port), 0).also { s ->
            s.executor = pool
            s.createContext("/") { exchange ->
                try {
                    handle(exchange.requestURI.path ?: "/", exchange)
                } catch (_: Exception) {
                    // koneksi putus — normal untuk MJPEG
                } finally {
                    if (exchange.requestURI.path != "/mjpeg") {
                        try { exchange.close() } catch (_: Exception) {}
                    }
                }
            }
            s.start()
        }
    }

    fun stop() {
        running = false
        server?.stop(0)
        pool.shutdownNow()
    }

    private fun checkAuth(exchange: com.sun.net.httpserver.HttpExchange): Boolean {
        val code = Prefs.passcode
        if (code.isEmpty()) return true
        val auth = exchange.requestHeaders.getFirst("Authorization") ?: return false
        if (!auth.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = String(Base64.decode(auth.substring(6), Base64.DEFAULT))
        return decoded == ":$code"
    }

    private fun unauthorized(exchange: com.sun.net.httpserver.HttpExchange) {
        exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"PhoneCam Pro\"")
        exchange.sendResponseHeaders(401, -1)
    }

    private fun handle(path: String, exchange: com.sun.net.httpserver.HttpExchange) {
        if (path == "/status") { status(exchange); return }
        if (!checkAuth(exchange)) { unauthorized(exchange); return }

        when (path) {
            "/", "/index.html" -> viewer(exchange)
            "/mjpeg" -> mjpeg(exchange)
            "/snapshot.jpg" -> snapshot(exchange)
            "/control" -> control(exchange)
            else -> {
                exchange.sendResponseHeaders(404, -1)
            }
        }
    }

    private fun status(exchange: com.sun.net.httpserver.HttpExchange) {
        val json = """
            {"app":"PhoneCam Pro","fps":${"%.1f".format(engine.fps)},
             "resolution":"${engine.resolution}","passcode":${Prefs.passcode.isNotEmpty()},
             "front_camera":${engine.useFront},"torch":${engine.torch}}
        """.trimIndent()
        bytes(exchange, 200, json.toByteArray(), "application/json")
    }

    private fun control(exchange: com.sun.net.httpserver.HttpExchange) {
        val q = exchange.requestURI.rawQuery ?: ""
        val params = q.split("&").mapNotNull {
            val (k, v) = it.split("=", limit = 2).let { p -> p.getOrNull(0) to p.getOrNull(1) }
            k?.let { kk -> kk to (v ?: "") }
        }.toMap()

        when (params["cmd"]) {
            "torch" -> {
                val on = params["v"] == "on"
                engine.setTorch(on)
                json(exchange, """{"ok":true,"torch":$on}""")
            }
            "camera" -> {
                val front = params["v"] == "front"
                if (front != engine.useFront) engine.switchCamera()
                json(exchange, """{"ok":true,"front":$front}""")
            }
            "zoom" -> {
                val z = (params["v"] ?: "1").toFloatOrNull()?.coerceIn(1f, 5f) ?: 1f
                engine.setZoom(z)
                json(exchange, """{"ok":true,"zoom":$z}""")
            }
            "quality" -> {
                val qv = (params["v"] ?: "1").toIntOrNull()?.coerceIn(0, 2) ?: 1
                Prefs.quality = qv
                json(exchange, """{"ok":true,"quality":$qv}""")
            }
            else -> json(exchange, """{"ok":false,"error":"unknown cmd"}""")
        }
    }

    private fun snapshot(exchange: com.sun.net.httpserver.HttpExchange) {
        val jpeg = engine.latestJpeg
        if (jpeg == null) {
            exchange.sendResponseHeaders(503, -1)
        } else {
            bytes(exchange, 200, jpeg, "image/jpeg")
        }
    }

    private fun mjpeg(exchange: com.sun.net.httpserver.HttpExchange) {
        val out = exchange.responseHeaders
        out.add("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        exchange.sendResponseHeaders(200, 0)
        val body = exchange.responseBody
        val buf = BufferedOutputStream(body, 1 shl 16)
        var lastSent = 0L
        while (running) {
            val jpeg = engine.latestJpeg
            if (jpeg != null) {
                buf.write("--frame\r\n".toByteArray())
                buf.write("Content-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                buf.write(jpeg)
                buf.write("\r\n".toByteArray())
                buf.flush()
            }
            Thread.sleep(33) // ~30 FPS max
        }
        try { body.close() } catch (_: Exception) {}
    }

    private fun viewer(exchange: com.sun.net.httpserver.HttpExchange) {
        val html = VIEWER_HTML.toByteArray()
        bytes(exchange, 200, html, "text/html; charset=utf-8")
    }

    private fun json(exchange: com.sun.net.httpserver.HttpExchange, s: String) =
        bytes(exchange, 200, s.toByteArray(), "application/json")

    private fun bytes(
        exchange: com.sun.net.httpserver.HttpExchange,
        code: Int, data: ByteArray, type: String
    ) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(code, data.size.toLong())
        exchange.responseBody.use { it.write(data) }
    }

    private val VIEWER_HTML = """
        <!doctype html><html><head><meta charset="utf-8">
        <title>PhoneCam Pro</title>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          body{background:#0e1116;color:#e9eef5;font:15px system-ui;margin:0;padding:24px;text-align:center}
          img{max-width:100%;border-radius:12px;margin:16px 0;border:1px solid #293647}
          .btn{display:inline-block;margin:4px;padding:10px 16px;background:#36a269;color:#fff;border:0;border-radius:8px;cursor:pointer;font-size:15px}
          .btn.off{background:#d54f55} .btn.sec{background:#3b4a61}
          #bar{display:flex;gap:8px;justify-content:center;flex-wrap:wrap}
        </style></head><body>
        <h2>📱 PhoneCam Pro — Viewer</h2>
        <img src="/mjpeg" id="cam">
        <div id="bar">
          <button class="btn" onclick="ctl('camera','front')">🤳 Kamera Depan</button>
          <button class="btn" onclick="ctl('camera','back')">📷 Kamera Belakang</button>
          <button class="btn" onclick="ctl('torch','on')">🔦 Torch On</button>
          <button class="btn off" onclick="ctl('torch','off')">🔦 Torch Off</button>
          <button class="btn sec" onclick="ctl('zoom','1')">1x</button>
          <button class="btn sec" onclick="ctl('zoom','2')">2x</button>
          <button class="btn sec" onclick="ctl('zoom','3')">3x</button>
        </div>
        <p style="color:#aeb9c6">Untuk OBS: tambah source <b>Browser</b> → URL ini,
        atau Media Source dengan <code>/mjpeg</code>. Snapshot: <a style="color:#7fc4ff" href="/snapshot.jpg">/snapshot.jpg</a></p>
        <script>
        async function ctl(cmd,v){
          await fetch('/control?cmd='+cmd+'&v='+v);
        }
        </script></body></html>
    """.trimIndent()
}
