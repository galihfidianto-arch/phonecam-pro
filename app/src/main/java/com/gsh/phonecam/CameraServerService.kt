package com.gsh.phonecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service: menjaga kamera + HTTP server hidup.
 */
class CameraServerService : Service() {

    companion object {
        const val CHANNEL = "phonecam_server"
        @Volatile var isRunning = false
            private set
        @Volatile var instance: CameraServerService? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, CameraServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CameraServerService::class.java))
        }
    }

    var engine: CameraEngine? = null
        private set
    var server: CamHttpServer? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
        isRunning = true
        instance = this

        engine = CameraEngine(this).also { it.start() }
        server = CamHttpServer(this, engine!!, Prefs.port).also { it.start() }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        server?.stop()
        engine?.fullStop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL, "PhoneCam Server",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("PhoneCam Pro aktif")
            .setContentText("Webcam server berjalan — port ${Prefs.port}")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
