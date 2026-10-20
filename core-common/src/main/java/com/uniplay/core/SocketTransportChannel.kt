package com.uniplay.core

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Socket-based implementation of TransportChannel for Wireless CarPlay and Wireless Android Auto.
 * Wraps high-speed TCP socket streams over 5GHz Wi-Fi / Hotspot.
 */
class SocketTransportChannel(
    val socket: Socket,
    override val type: TransportType = TransportType.WIRELESS_WIFI
) : TransportChannel {

    companion object {
        private const val TAG = "SocketTransport"
    }

    override val inputStream: InputStream = socket.getInputStream()
    override val outputStream: OutputStream = socket.getOutputStream()

    val remoteAddress: String = socket.remoteSocketAddress?.toString() ?: "Unknown"

    init {
        // Optimize TCP socket for real-time video/audio streaming
        try {
            socket.tcpNoDelay = true // Disable Nagle's algorithm for lowest latency (<20ms)
            socket.keepAlive = true
            socket.receiveBufferSize = 256 * 1024
            socket.sendBufferSize = 64 * 1024
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply TCP optimizations: ${e.message}")
        }
    }

    override fun close() {
        try {
            if (!socket.isClosed) {
                socket.close()
                Log.i(TAG, "Socket transport closed: $remoteAddress")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket transport: ${e.message}")
        }
    }
}
