package com.uniplay.app

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.uniplay.androidauto.AndroidAutoWirelessManager
import com.uniplay.core.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ProjectionSessionController(
    private val context: Context,
    private val mediaSink: MediaSink,
    private val onStatusUpdate: (status: String, color: Int) -> Unit
) {
    companion object {
        private const val TAG = "ProjectionSessionController"
    }

    private val deviceRouter = DeviceRouter(context)
    private val bluetoothBeacon = BluetoothBeacon(context)
    private val isConnectingDirect = AtomicBoolean(false)
    private val isWirelessInitialized = AtomicBoolean(false)

    private var wirelessServer: WirelessServer? = null
    private var aaWirelessManager: AndroidAutoWirelessManager? = null

    fun getBluetoothDeviceName(): String = bluetoothBeacon.getDeviceName()

    fun startWirelessServices(localIp: String, btName: String) {
        if (isWirelessInitialized.getAndSet(true)) return

        bluetoothBeacon.startAdvertising()

        // Start official Android Auto Wireless RFCOMM Handshake Server
        aaWirelessManager = AndroidAutoWirelessManager(context) { phoneName ->
            onStatusUpdate(
                "Bluetooth handshake with $phoneName! Handing off to Wi-Fi...",
                Color.parseColor("#50E3C2")
            )
        }
        aaWirelessManager?.start(
            ssid = btName,
            psk = "12345678",
            localIp = localIp,
            port = WirelessServer.PORT_MAIN
        )

        // Initialize and start background high-speed wireless projection server
        wirelessServer = WirelessServer(deviceRouter, mediaSink) { status, isConnected ->
            val color = if (isConnected) "#4E9FFF" else "#00FF9D"
            onStatusUpdate(status, Color.parseColor(color))
        }
        wirelessServer?.start()
    }

    fun connectDirectToPhone(manual: Boolean, gatewayIp: String, hasStreamStarted: Boolean) {
        if (hasStreamStarted || isConnectingDirect.getAndSet(true)) return

        if (manual) {
            onStatusUpdate(
                "Attempting direct connection to phone at $gatewayIp...",
                Color.parseColor("#4E9FFF")
            )
        }

        Thread({
            var connectedSocket: Socket? = null
            // Standard Android Auto Headunit Server port is 5277, fallback 5000, 5288
            val portsToTry = intArrayOf(5277, 5000, 5288)

            for (port in portsToTry) {
                try {
                    val socket = Socket()
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(gatewayIp, port), 2500)
                    connectedSocket = socket
                    Log.i(TAG, "Successfully connected to Phone at $gatewayIp:$port!")
                    break
                } catch (_: Exception) {}
            }

            if (connectedSocket != null) {
                onStatusUpdate(
                    "Connected to Phone! Initializing Android Auto stream...",
                    Color.parseColor("#00FF9D")
                )
                val transport = SocketTransportChannel(connectedSocket, TransportType.WIRELESS_WIFI)
                deviceRouter.onManualSelect(ProjectionType.ANDROID_AUTO, transport, mediaSink)
            } else {
                isConnectingDirect.set(false)
                if (manual) {
                    onStatusUpdate(
                        "Could not find Phone on $gatewayIp.\nEnsure 'Start head unit server' is ON in Android Auto settings.",
                        Color.parseColor("#FF9F4E")
                    )
                }
            }
        }, "DirectPhoneConnector").apply {
            isDaemon = true
            start()
        }
    }

    fun onSessionTerminated() {
        isConnectingDirect.set(false)
    }

    fun sendTouchEvent(action: Int, x: Float, y: Float) {
        deviceRouter.getActiveEngine()?.sendTouchEvent(action, x, y)
    }

    fun sendKeyEvent(keyCode: Int, isDown: Boolean) {
        deviceRouter.getActiveEngine()?.sendKeyEvent(keyCode, isDown)
    }

    fun stop() {
        aaWirelessManager?.stop()
        wirelessServer?.stop()
        bluetoothBeacon.stopAdvertising()
        deviceRouter.stopActiveSession()
    }
}
