package com.uniplay.core

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Resolves the current local IPv4 address (Wi-Fi STA, Car Hotspot AP, or Wi-Fi Direct).
     */
    fun getLocalIpAddress(context: Context? = null): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                // Look for active non-loopback wireless/hotspot interfaces (wlan0, ap0, p2p0)
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                val isWireless = name.contains("wlan") || name.contains("ap") || name.contains("p2p") || name.contains("rndis")

                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (isWireless) {
                            return host
                        }
                    }
                }
            }

            // Fallback: check any IPv4 non-loopback
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving IP address", e)
        }
        return "127.0.0.1"
    }

    /**
     * Detects if device is in Wi-Fi AP (Hotspot) mode.
     */
    fun isHotspotActive(): Boolean {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (intf in interfaces) {
                if (intf.name.contains("ap") || intf.name.contains("wlan1")) {
                    if (intf.isUp && intf.inetAddresses.hasMoreElements()) return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Resolves the gateway IP of the connected Wi-Fi (e.g. Phone Hotspot IP).
     */
    fun getGatewayIp(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo
            if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                val ip = dhcpInfo.gateway
                return String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting gateway IP", e)
        }
        return "192.168.43.1"
    }
}
