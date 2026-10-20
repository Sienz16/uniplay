package com.uniplay.core

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Enumeration

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Resolves the current local IPv4 address (Wi-Fi STA, Car Hotspot AP, or Wi-Fi Direct).
     */
    fun getLocalIpAddress(context: Context? = null): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            findIpv4Address(interfaces, wirelessOnly = true)?.let { return it }

            // Fallback: check any IPv4 non-loopback
            val fallbackInterfaces = NetworkInterface.getNetworkInterfaces()!!
            findIpv4Address(fallbackInterfaces, wirelessOnly = false)?.let { return it }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving IP address", e)
        }
        return "127.0.0.1"
    }

    private fun findIpv4Address(
        interfaces: Enumeration<NetworkInterface>,
        wirelessOnly: Boolean
    ): String? {
        for (intf in interfaces) {
            if (intf.isLoopback || !intf.isUp) continue
            val isWireless = wirelessOnly && isWirelessInterface(intf.name)

            for (address in intf.inetAddresses) {
                if (address.isLoopbackAddress || address !is Inet4Address) continue

                val host = address.hostAddress
                if (wirelessOnly) {
                    if (isWireless && host != null) return host
                } else {
                    return host ?: "127.0.0.1"
                }
            }
        }
        return null
    }

    private fun isWirelessInterface(name: String): Boolean {
        val normalizedName = name.lowercase()
        return normalizedName.contains("wlan") || normalizedName.contains("ap") ||
            normalizedName.contains("p2p") || normalizedName.contains("rndis")
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
