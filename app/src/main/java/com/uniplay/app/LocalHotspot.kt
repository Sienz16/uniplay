package com.uniplay.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Starts a private Wi-Fi hotspot the app controls (Android's "local-only hotspot"), so
 * the head unit can hand its name and password to the phone without anyone typing them.
 * Needs Android 8+. Many head units cannot run it next to their normal Wi-Fi; callers
 * fall back to typed-in details when this reports a failure.
 */
class LocalHotspot(context: Context) {

    class Credentials(val ssid: String, val psk: String, val ip: String)

    private companion object {
        const val TAG = "LocalHotspot"
        const val FALLBACK_AP_IP = "192.168.43.1"
    }

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var current: Credentials? = null
    private var starting = false

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && wifi != null

    /** Calls back on the main thread, with the existing hotspot if one is already up. */
    @SuppressLint("MissingPermission")
    fun start(onReady: (Credentials) -> Unit, onFailed: (String) -> Unit) {
        current?.let { onReady(it); return }
        if (!isSupported) return onFailed("Automatic hotspot needs Android 8 or newer")
        if (starting) return
        starting = true

        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(started: WifiManager.LocalOnlyHotspotReservation) {
                starting = false
                reservation = started
                val creds = read(started)
                if (creds == null) {
                    started.close()
                    reservation = null
                    onFailed("The hotspot started but gave no name or password")
                } else {
                    current = creds
                    Log.i(TAG, "Local hotspot up: ${creds.ssid} at ${creds.ip}")
                    onReady(creds)
                }
            }

            override fun onStopped() {
                current = null
                reservation = null
            }

            override fun onFailed(reason: Int) {
                starting = false
                Log.w(TAG, "Local hotspot failed, reason=$reason")
                onFailed("This head unit could not start its own hotspot (code $reason)")
            }
        }

        try {
            wifi!!.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            starting = false
            onFailed("Location permission is needed to start the hotspot")
        } catch (e: Exception) {
            starting = false
            onFailed("Could not start the hotspot: ${e.message}")
        }
    }

    fun stop() {
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null
        current = null
        starting = false
    }

    @Suppress("DEPRECATION")
    private fun read(r: WifiManager.LocalOnlyHotspotReservation): Credentials? {
        val ssid: String?
        val psk: String?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg: SoftApConfiguration = r.softApConfiguration
            ssid = cfg.ssid
            psk = cfg.passphrase
        } else {
            val cfg: WifiConfiguration = r.wifiConfiguration ?: return null
            ssid = cfg.SSID?.trim('"')
            psk = cfg.preSharedKey?.trim('"')
        }
        if (ssid.isNullOrBlank() || psk.isNullOrBlank()) return null
        return Credentials(ssid, psk, findApAddress())
    }

    /**
     * The head unit's address on its own hotspot, which differs from its normal Wi-Fi
     * address. Looks for the access point interface, then falls back to the usual one.
     */
    private fun findApAddress(): String {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces() ?: return FALLBACK_AP_IP) {
                val name = intf.name.lowercase()
                val isAp = name.startsWith("ap") || name.startsWith("swlan") ||
                    name.startsWith("softap") || name == "wlan1"
                if (!isAp || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress ?: continue
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the access point address: ${e.message}")
        }
        return FALLBACK_AP_IP
    }
}
