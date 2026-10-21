package com.uniplay.app

import android.content.Context

/**
 * The head unit's own Wi-Fi hotspot name and password, as the user set them on the
 * head unit. Android does not let an app read or choose them, so the phone is told
 * what the user typed here; a made-up password would never match the real access
 * point, and a shared default would let anyone nearby join it.
 */
class HotspotSettings(context: Context) {

    private val prefs = context.getSharedPreferences("hotspot", Context.MODE_PRIVATE)

    val ssid: String get() = prefs.getString(KEY_SSID, "") ?: ""
    val psk: String get() = prefs.getString(KEY_PSK, "") ?: ""

    /** WPA2 needs an 8 to 63 character passphrase. */
    val isConfigured: Boolean get() = ssid.isNotBlank() && psk.length in 8..63

    fun save(ssid: String, psk: String) {
        prefs.edit().putString(KEY_SSID, ssid.trim()).putString(KEY_PSK, psk).apply()
    }

    private companion object {
        const val KEY_SSID = "ssid"
        const val KEY_PSK = "psk"
    }
}
