package com.gsh.phonecam

import android.content.Context
import android.util.Base64
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * HTTP server MJPEG di HP — pure java.net (tanpa com.sun, kompatibel Android).
 *
 *   GET /mjpeg           → stream MJPEG (multipart/x-mixed-replace)
 *   GET /snapshot.jpg    → 1 frame JPEG
 *   GET /control?cmd=X    → kontrol Pro: torch=on|off, camera=front|back, zoom=1..5, quality=0..2
 *   GET /status          → JSON: fps, resolusi, passcode aktif
 *   GET /                → halaman viewer HTML (buka di browser PC / OBS Browser Source)
 *
 * Endpoint (kecuali /status) dicek passcode via Basic Auth bila aktif.
 */
class CamHttpServer(
    private val ctx: Context,
    private val engine: CameraEngine,
    private val port: Int
) {
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        running = true
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        Thread {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    pool.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (running) Thread.sleep(100)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val fullPath = parts[1]

            // Baca headers (untuk Authorization)
            var authHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Authorization:", ignoreCase = true)) authHeader = line
            }

            val path = fullPath.substringBefore('?')
            val query = fullPath.substringAfter('?', "")
            val out = BufferedOutputStream(client.getOutputStream(), 1 shl 16)

            if (path == "/status") {
                status(out)
            } else if (!checkAuth(authHeader)) {
                unauthorized(out)
            } else {
                when (path) {
                    "/", "/index.html" -> viewer(out)
                    "/mjpeg" -> mjpeg(out)
                    "/snapshot.jpg" -> snapshot(out)
                    "/control" -> control(query, out)
                    else -> notFound(out)
                }
            }
            out.flush()
        } catch (_: Exception) {
            // koneksi putus — normal
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun checkAuth(authHeader: String?): Boolean {
        val code = Prefs.passcode
        if (code.isEmpty()) return true
        val auth = authHeader ?: return false
        val idx = auth.indexOf(' ')
        if (idx < 0 || !auth.substring(0, idx).equals("Basic", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(auth.substring(idx + 1).trim(), Base64.DEFAULT))
        } catch (_: Exception) { return false }
        return decoded == ":$code"
    }

    private fun writeHeaders(out: OutputStream, code: Int, status: String, type: String?, extra: List<String> = emptyList()) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Server: PhoneCamPro\r\n")
        if (type != null) sb.append("Content-Type: $type\r\n")
        for (e in extra) sb.append(e).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun unauthorized(out: OutputStream) {
        writeHeaders(out, 401, "Unauthorized", null, listOf("WWW-Authenticate: Basic realm=\"PhoneCam Pro\""))
    }

    private fun notFound(out: OutputStream) {
        writeHeaders(out, 404, "Not Found", "text/plain")
        out.write("404".toByteArray())
    }

    private fun status(out: OutputStream) {
        val fps = "%.1f".format(engine.fps)
        val json = """{"app":"PhoneCam Pro","fps":$fps,"resolution":"${engine.resolution}",""" +
                """"passcode":${Prefs.passcode.isNotEmpty()},"front_camera":${engine.useFront},"torch":${engine.isTorchOn}}"""
        writeHeaders(out, 200, "OK", "application/json")
        out.write(json.toByteArray())
    }

    private fun snapshot(out: OutputStream) {
        val jpeg = engine.latestJpeg
        if (jpeg == null) {
            writeHeaders(out, 503, "Service Unavailable", "text/plain")
            out.write("kamera belum siap".toByteArray())
        } else {
            writeHeaders(out, 200, "OK", "image/jpeg")
            out.write(jpeg)
        }
    }

    private fun control(query: String, out: OutputStream) {
        val params = query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val json = when (params["cmd"]) {
            "torch" -> {
                val on = params["v"] == "on"
                engine.setTorch(on)
                """{"ok":true,"torch":$on}"""
            }
            "camera" -> {
                val front = params["v"] == "front"
                if (front != engine.useFront) engine.switchCamera()
                """{"ok":true,"front":$front}"""
            }
            "zoom" -> {
                val z = params["v"]?.toFloatOrNull()?.coerceIn(1f, 5f) ?: 1f
                engine.setZoom(z)
                """{"ok":true,"zoom":$z}"""
            }
            "quality" -> {
                val qv = params["v"]?.toIntOrNull()?.coerceIn(0, 2) ?: 1
                Prefs.quality = qv
                """{"ok":true,"quality":$qv}"""
            }
            else -> """{"ok":false,"error":"unknown cmd"}"""
        }
        writeHeaders(out, 200, "OK", "application/json")
        out.write(json.toByteArray())
    }

    private fun mjpeg(out: OutputStream) {
        writeHeaders(out, 200, "OK", "multipart/x-mixed-replace; boundary=frame")
        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
        while (running) {
            try {
                val jpeg = engine.latestJpeg
                if (jpeg != null) {
                    out.write(header.toByteArray())
                    out.write(jpeg.size.toString().toByteArray())
                    out.write("\r\n\r\n".toByteArray())
                    out.write(jpeg)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                Thread.sleep(33) // ~30 FPS
            } catch (_: Exception) {
                break // client putus
            }
        }
    }

    private fun viewer(out: OutputStream) {
        writeHeaders(out, 200, "OK", "text/html; charset=utf-8")
        out.write(VIEWER_HTML.toByteArray())
    }

    private val VIEWER_HTML = """
        <!doctype html><html><head><meta charset="utf-8">
        <title>PhoneCam Pro</title>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          body{background:#0e1116;color:#e9eef5;font:15px system-ui;margin:0;padding:24px;text-align:center}
          img{max-width:100%;border-radius:12px;margin:16px 0;border:1px solid #293647}
          .btn{margin:4px;padding:10px 16px;background:#36a269;color:#fff;border:0;border-radius:8px;cursor:pointer;font-size:15px}
          .btn.off{background:#d54f55} .btn.sec{background:#3b4a61}
          #bar{display:flex;gap:8px;justify-content:center;flex-wrap:wrap}
        </style></head><body>
        <h2>&#128241; PhoneCam Pro &mdash; Viewer</h2>
        <img src="/mjpeg" id="cam">
        <div id="bar">
          <button class="btn" onclick="ctl('camera','front')">&#129489; Depan</button>
          <button class="btn" onclick="ctl('camera','back')">&#128247; Belakang</button>
          <button class="btn" onclick="ctl('torch','on')">&#128295; Torch On</button>
          <button class="btn off" onclick="ctl('torch','off')">&#128295; Torch Off</button>
          <button class="btn sec" onclick="ctl('zoom','1')">1x</button>
          <button class="btn sec" onclick="ctl('zoom','2')">2x</button>
          <button class="btn sec" onclick="ctl('zoom','3')">3x</button>
        </div>
        <p style="color:#aeb9c6">OBS: Source <b>Browser</b> &rarr; URL ini. Snapshot: <a style="color:#7fc4ff" href="/snapshot.jpg">/snapshot.jpg</a></p>
        <script>
        function ctl(cmd,v){fetch('/control?cmd='+cmd+'&v='+v)}
        </script></body></html>
    """.trimIndent()
}
