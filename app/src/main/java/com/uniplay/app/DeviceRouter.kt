package com.uniplay.app

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.uniplay.androidauto.AndroidAutoEngine
import com.uniplay.carplay.CarPlayEngine
import com.uniplay.core.*

/**
 * Auto-detection and session dispatcher.
 * Identifies connected devices (Apple vs Android) and guarantees single active session.
 */
class DeviceRouter(private val context: Context) {

    companion object {
        private const val TAG = "DeviceRouter"
        const val VID_APPLE = 0x05AC
        const val VID_GOOGLE_AOA = 0x18D1
    }

    private val carPlayEngine = CarPlayEngine()
    private val androidAutoEngine = AndroidAutoEngine(context)

    private var activeEngine: ProjectionEngine? = null

    @Synchronized
    fun onUsbDeviceAttached(device: UsbDevice, transport: TransportChannel, sink: MediaSink): ProjectionType {
        Log.i(TAG, "USB Device Attached: VID=0x${Integer.toHexString(device.vendorId)}, PID=0x${Integer.toHexString(device.productId)}")

        // Release any ongoing session before starting a new one
        stopActiveSession()

        val selectedEngine = if (device.vendorId == VID_APPLE) {
            Log.i(TAG, "Routing to Apple CarPlay Engine")
            carPlayEngine
        } else {
            Log.i(TAG, "Routing to Android Auto Engine")
            androidAutoEngine
        }

        activeEngine = selectedEngine
        selectedEngine.start(transport, sink)
        return selectedEngine.type
    }

    @Synchronized
    fun onManualSelect(type: ProjectionType, transport: TransportChannel, sink: MediaSink) {
        stopActiveSession()
        val engine = when (type) {
            ProjectionType.CARPLAY -> carPlayEngine
            ProjectionType.ANDROID_AUTO -> androidAutoEngine
            ProjectionType.NONE -> null
        }
        activeEngine = engine
        engine?.start(transport, sink)
    }

    @Synchronized
    fun getActiveEngine(): ProjectionEngine? = activeEngine

    @Synchronized
    fun stopActiveSession() {
        activeEngine?.stop()
        activeEngine = null
    }
}
