package com.gsh.phonecam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * PhoneCam Pro — HP jadi webcam 4K untuk PC via WiFi/USB.
 * Semua fitur "Pro" aktif: tanpa watermark, passcode, kontrol kamera penuh.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var passcodeEdit: EditText
    private lateinit var startButton: Button

    private val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        passcodeEdit = findViewById(R.id.passcodeEdit)
        startButton = findViewById(R.id.startButton)

        Prefs.init(this)
        passcodeEdit.setText(Prefs.passcode)
        passcodeEdit.hint = "Passcode (kosong = bebas)"

        startButton.setOnClickListener {
            Prefs.passcode = passcodeEdit.text.toString().trim()
            if (hasPerms()) startServer() else requestPerms()
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
        Toast.makeText(this, "Server kamera aktif", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        val ip = NetUtils.wifiIpAddress(this) ?: "?"
        val running = CameraServerService.isRunning
        statusText.text = buildString {
            appendLine("PhoneCam Pro — semua fitur aktif ✅")
            appendLine()
            appendLine("Status: ${if (running) "🟢 BERJALAN" else "🔴 berhenti"}")
            appendLine()
            appendLine("Di PC, buka: http://$ip:8085")
            appendLine("atau jalankan viewer Python: python phonecam_viewer.py")
            appendLine()
            appendLine("mDNS: _phonecam._tcp. (auto-discovery)")
            appendLine("Video: MJPEG — langsung HP→PC, tanpa cloud")
            appendLine("Passcode: ${if (Prefs.passcode.isEmpty()) "nonaktif" else "aktif ✅"}")
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
