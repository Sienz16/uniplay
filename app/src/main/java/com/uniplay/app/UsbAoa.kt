package com.uniplay.app

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.util.Log
import com.uniplay.core.TransportChannel
import com.uniplay.core.TransportType
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Android Open Accessory (AOA) 2.0 host side: switches a plugged-in phone into
 * accessory mode, then exposes its bulk endpoints as a [TransportChannel] for the
 * Android Auto engine. The head unit is the USB host here.
 */
object UsbAoa {

    private const val TAG = "UsbAoa"
    private const val VID_GOOGLE = 0x18D1
    private const val PID_ACCESSORY_MIN = 0x2D00
    private const val PID_ACCESSORY_MAX = 0x2D05

    private const val REQ_GET_PROTOCOL = 51
    private const val REQ_SEND_STRING = 52
    private const val REQ_START = 53
    private const val TIMEOUT_MS = 2000

    // What the phone shows for the head unit. Android Auto looks for these strings.
    private val ACCESSORY_STRINGS = arrayOf(
        "Android",                                     // manufacturer
        "Android Auto",                                // model
        "Android Auto",                                // description
        "2.0.1",                                       // version
        "https://developer.android.com/auto/index.html", // uri
        "HU-AAAAAA"                                    // serial
    )

    fun isAccessoryMode(device: UsbDevice): Boolean =
        device.vendorId == VID_GOOGLE && device.productId in PID_ACCESSORY_MIN..PID_ACCESSORY_MAX

    /**
     * Asks the phone to re-enumerate as an accessory. Returns false when the device
     * does not speak AOA (a keyboard, a storage stick, an iPhone).
     */
    fun requestAccessoryMode(usb: UsbManager, device: UsbDevice): Boolean {
        val conn = usb.openDevice(device) ?: return false
        try {
            val version = ByteArray(2)
            val got = conn.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
                REQ_GET_PROTOCOL, 0, 0, version, 2, TIMEOUT_MS
            )
            val protocol = (version[1].toInt() shl 8) or (version[0].toInt() and 0xFF)
            if (got != 2 || protocol < 1) {
                Log.i(TAG, "${device.deviceName} does not support AOA (protocol=$protocol)")
                return false
            }
            ACCESSORY_STRINGS.forEachIndexed { index, text ->
                val bytes = (text + "\u0000").toByteArray(Charsets.UTF_8)
                conn.controlTransfer(
                    UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
                    REQ_SEND_STRING, 0, index, bytes, bytes.size, TIMEOUT_MS
                )
            }
            conn.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
                REQ_START, 0, 0, null, 0, TIMEOUT_MS
            )
            Log.i(TAG, "AOA $protocol start sent; waiting for the phone to re-attach")
            return true
        } finally {
            conn.close()
        }
    }

    /** Opens the bulk endpoints of a phone already in accessory mode. */
    fun openTransport(usb: UsbManager, device: UsbDevice): TransportChannel? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp == null || outEp == null) continue

            val conn = usb.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                continue
            }
            return UsbTransportChannel(conn, inEp, outEp)
        }
        Log.w(TAG, "No bulk in/out interface on ${device.deviceName}")
        return null
    }
}

class UsbTransportChannel(
    private val connection: UsbDeviceConnection,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) : TransportChannel {

    private companion object {
        /** Older kernels reject a single bulk transfer larger than this. */
        const val MAX_CHUNK = 16 * 1024
    }

    @Volatile
    private var closed = false

    override val type: TransportType = TransportType.USB

    override val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (!closed) {
                // A finite timeout, so close() ends a read that is waiting on a quiet cable.
                val started = System.nanoTime()
                val n = connection.bulkTransfer(inEndpoint, buffer, offset, minOf(length, MAX_CHUNK), 1000)
                if (n > 0) return n
                // bulkTransfer returns -1 for both a timeout and a real error (unplugged
                // phone). An error comes back at once; a timeout takes the full second.
                val waitedMs = (System.nanoTime() - started) / 1_000_000
                if (n < 0 && waitedMs < 900) return -1
            }
            return -1
        }
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            var sent = 0
            while (sent < length) {
                if (closed) throw IOException("USB transport closed")
                val chunk = minOf(length - sent, MAX_CHUNK)
                val n = connection.bulkTransfer(outEndpoint, buffer, offset + sent, chunk, 5000)
                if (n < 0) throw IOException("USB write failed")
                sent += n
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }
}
