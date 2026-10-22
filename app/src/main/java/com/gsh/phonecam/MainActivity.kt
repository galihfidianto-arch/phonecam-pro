package com.gsh.phonecam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var passcodeEdit: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val ui = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            updateStatus()
            if (CameraServerService.isRunning) ui.postDelayed(this, 1000)
        }
    }

    private val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        passcodeEdit = findViewById(R.id.passcodeEdit)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        Prefs.init(this)
        passcodeEdit.setText(Prefs.passcode)

        startButton.setOnClickListener {
            Prefs.passcode = passcodeEdit.text.toString().trim()
            if (hasPerms()) startServer() else requestPerms()
        }
        stopButton.setOnClickListener {
            CameraServerService.stop(this)
            ui.post { updateStatus() }
        }

        updateStatus()
    }

    private fun hasPerms() = perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        ActivityCompat.requestPermissions(this, perms, 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startServer()
        } else {
            Toast.makeText(this, "Izin kamera & mic wajib", Toast.LENGTH_LONG).show()
        }
    }

    private fun startServer() {
        CameraServerService.start(this)
        // poll status tiap detik — tampilkan error live
        ui.postDelayed(poller, 800)
        updateStatus()
    }

    private fun updateStatus() {
        val ip = NetUtils.wifiIpAddress(this) ?: "?"
        val port = Prefs.port
        val running = CameraServerService.isRunning
        val svc = CameraServerService.instance
        val engine = svc?.engine
        val server = svc?.server

        val camLine = when {
            engine == null -> "Kamera: belum mulai"
            engine.isStreaming -> "Kamera: 🟢 streaming ${engine.resolution} @ ${"%.1f".format(engine.fps)}fps"
            else -> "Kamera: ⏳ menunggu frame..."
        }
        val srvLine = when {
            server == null -> "Server: belum mulai"
            server.bindError != null -> "Server: 🔴 ${server.bindError}"
            server.bindOk -> "Server: 🟢 aktif di port $port"
            else -> "Server: ⏳ binding..."
        }
        val errLine = engine?.lastError?.let { "\n⚠ $it" } ?: ""

        statusText.text = buildString {
            appendLine("Status layanan: ${if (running) "🟢 BERJALAN" else "🔴 berhenti"}")
            appendLine(camLine)
            appendLine(srvLine)
            append(errLine)
            appendLine()
            appendLine(if (running) "Di PC buka: http://$ip:$port" else "Tekan mulai untuk menyalakan server")
            appendLine()
            appendLine("Passcode: ${if (Prefs.passcode.isEmpty()) "nonaktif" else "aktif ✅"}")
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        if (CameraServerService.isRunning) ui.postDelayed(poller, 1000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poller)
    }
}
