package com.gsh.phonecam

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    var passcode: String
        get() = sp.getString("passcode", "") ?: ""
        set(v) = sp.edit().putString("passcode", v).apply()

    var port: Int
        get() = sp.getInt("port", 8085)
        set(v) = sp.edit().putInt("port", v).apply()

    var torch: Boolean
        get() = sp.getBoolean("torch", false)
        set(v) = sp.edit().putBoolean("torch", v).apply()

    var useFrontCamera: Boolean
        get() = sp.getBoolean("front", false)
        set(v) = sp.edit().putBoolean("front", v).apply()

    var quality: Int // 0=720p 1=1080p 2=4K
        get() = sp.getInt("quality", 1)
        set(v) = sp.edit().putInt("quality", v).apply()

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("phonecam", Context.MODE_PRIVATE)
    }
}
