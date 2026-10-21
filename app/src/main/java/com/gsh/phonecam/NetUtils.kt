package com.gsh.phonecam

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface

object NetUtils {
    fun wifiIpAddress(ctx: Context): String? {
        // Coba semua interface non-loopback dulu (mendukung USB tethering)
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull {
                    !it.isLoopbackAddress && it is InetAddress &&
                    it.hostAddress?.contains('.') == true &&
                    !it.hostAddress!!.startsWith("127.")
                }?.hostAddress
        } catch (e: Exception) {
            wifiFallback(ctx)
        } ?: wifiFallback(ctx)
    }

    private fun wifiFallback(ctx: Context): String? = try {
        @Suppress("DEPRECATION")
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val raw = wm.connectionInfo.ipAddress
        if (raw != 0) String.format(
            "%d.%d.%d.%d", raw and 0xff, raw shr 8 and 0xff,
            raw shr 16 and 0xff, raw shr 24 and 0xff
        ) else null
    } catch (e: Exception) { null }
}
