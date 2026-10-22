package com.gsh.phonecam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS advertiser — padanan _iriunwebcam._tcp. milik Iriun.
 * Mendaftarkan service "_phonecam._tcp." agar PC auto-discovery.
 */
object NsdAdvertiser {
    private const val TAG = "PhoneCamNsd"
    private const val SERVICE_TYPE = "_phonecam._tcp."
    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun start(ctx: Context, port: Int, name: String = "PhoneCamPro") {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = name
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(r: NsdServiceInfo) {
                    Log.i(TAG, "mDNS terdaftar: ${r.serviceName} port $port")
                }
                override fun onRegistrationFailed(r: NsdServiceInfo, err: Int) {
                    Log.e(TAG, "mDNS gagal kode $err")
                }
                override fun onServiceUnregistered(r: NsdServiceInfo) {}
                override fun onUnregistrationFailed(r: NsdServiceInfo, err: Int) {}
            }
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD tidak tersedia: ${e.message}")
        }
    }

    fun stop() {
        try {
            listener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        listener = null; nsdManager = null
    }
}
