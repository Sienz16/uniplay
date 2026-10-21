package com.uniplay.app

import android.content.Context
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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

    init {
        (mediaSink as? ProjectionMediaRenderer)?.keyframeRequester = {
            deviceRouter.getActiveEngine()?.requestKeyframe()
        }
    }
    private val bluetoothBeacon = BluetoothBeacon(context)
    private val isConnectingDirect = AtomicBoolean(false)
    private val isWirelessInitialized = AtomicBoolean(false)

    private var wirelessServer: WirelessServer? = null
    private var aaWirelessManager: AndroidAutoWirelessManager? = null

    fun getBluetoothDeviceName(): String = bluetoothBeacon.getDeviceName()

    fun startWirelessServices(localIp: String, btName: String) {
        if (isWirelessInitialized.getAndSet(true)) return

        bluetoothBeacon.startAdvertising()

        startAndroidAutoHandoff(localIp)

        // Initialize and start background high-speed wireless projection server
        wirelessServer = WirelessServer(deviceRouter, mediaSink) { status, isConnected ->
            val color = if (isConnected) "#8FB2FF" else "#5FD0A5"
            onStatusUpdate(status, Color.parseColor(color))
        }
        wirelessServer?.start()
    }

    val hotspot = HotspotSettings(context)

    /** Set by the UI: asks the user for hotspot details when none could be found automatically. */
    var onHotspotSetupNeeded: () -> Unit = {}

    private val localHotspot = LocalHotspot(context)

    /**
     * (Re)starts the Bluetooth handoff that tells the phone which Wi-Fi to join.
     * Typed-in hotspot details win; otherwise the app starts a hotspot of its own and
     * uses that, and only asks the user when this head unit cannot do so.
     * Safe to call again after the user saves details or grants a permission.
     */
    fun startAndroidAutoHandoff(localIp: String) {
        aaWirelessManager?.stop()
        aaWirelessManager = null

        if (hotspot.isConfigured) {
            beginHandoff(hotspot.ssid, hotspot.psk, localIp)
            return
        }

        onStatusUpdate("Starting the car hotspot...", Color.parseColor("#8FB2FF"))
        localHotspot.start(
            onReady = { creds ->
                beginHandoff(creds.ssid, creds.psk, creds.ip)
            },
            onFailed = { reason ->
                Log.w(TAG, "Automatic hotspot unavailable: $reason")
                onStatusUpdate("$reason. Enter your hotspot details instead.", Color.parseColor("#F0B46A"))
                onHotspotSetupNeeded()
            }
        )
    }

    private fun beginHandoff(ssid: String, psk: String, ip: String) {
        aaWirelessManager?.stop()
        aaWirelessManager = AndroidAutoWirelessManager(context) { phoneName ->
            onStatusUpdate(
                "Bluetooth handshake with $phoneName! Handing off to Wi-Fi...",
                Color.parseColor("#8FB2FF")
            )
        }.also {
            it.start(ssid = ssid, psk = psk, localIp = ip, port = WirelessServer.PORT_MAIN)
        }
        onStatusUpdate("Ready. Waiting for your phone", Color.parseColor("#5FD0A5"))
    }

    fun connectDirectToPhone(manual: Boolean, gatewayIp: String, hasStreamStarted: Boolean) {
        if (hasStreamStarted || isConnectingDirect.getAndSet(true)) return

        if (manual) {
            onStatusUpdate(
                "Attempting direct connection to phone at $gatewayIp...",
                Color.parseColor("#8FB2FF")
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
                    Color.parseColor("#5FD0A5")
                )
                val transport = SocketTransportChannel(connectedSocket, TransportType.WIRELESS_WIFI)
                deviceRouter.onManualSelect(ProjectionType.ANDROID_AUTO, transport, mediaSink)
            } else {
                isConnectingDirect.set(false)
                if (manual) {
                    onStatusUpdate(
                        "Could not find Phone on $gatewayIp.\nEnsure 'Start head unit server' is ON in Android Auto settings.",
                        Color.parseColor("#F0B46A")
                    )
                }
            }
        }, "DirectPhoneConnector").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * A phone was plugged in. An iPhone is turned away (CarPlay is not available); any
     * other device is switched into accessory mode, and once it re-attaches as an
     * accessory its endpoints become the Android Auto transport. Caller must hold
     * USB permission for [device].
     */
    fun onUsbDeviceAttached(device: UsbDevice) {
        if (device.vendorId == DeviceRouter.VID_APPLE) {
            onStatusUpdate("CarPlay is not supported yet. Use Android Auto.", Color.parseColor("#F0B46A"))
            return
        }
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        Thread({
            try {
                if (UsbAoa.isAccessoryMode(device)) {
                    val transport = UsbAoa.openTransport(usb, device)
                    if (transport == null) {
                        onStatusUpdate("Could not open the USB connection to the phone", Color.parseColor("#F07C7C"))
                        return@Thread
                    }
                    onStatusUpdate("Phone connected over USB. Starting Android Auto...", Color.parseColor("#5FD0A5"))
                    deviceRouter.onUsbDeviceAttached(device, transport, mediaSink)
                } else if (UsbAoa.requestAccessoryMode(usb, device)) {
                    onStatusUpdate("Switching phone to Android Auto...", Color.parseColor("#8FB2FF"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "USB attach handling failed", e)
                onStatusUpdate("USB error: ${e.message}", Color.parseColor("#F07C7C"))
            }
        }, "UsbAttachHandler").apply {
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
        localHotspot.stop()
        aaWirelessManager?.stop()
        wirelessServer?.stop()
        bluetoothBeacon.stopAdvertising()
        deviceRouter.stopActiveSession()
    }
}
