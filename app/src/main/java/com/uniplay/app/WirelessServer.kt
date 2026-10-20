package com.uniplay.app

import android.util.Log
import com.uniplay.core.*
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, dual-protocol Wireless Server.
 * Listens on port 5000 (and 7000) over 5GHz Wi-Fi / Hotspot.
 * Automatically identifies incoming phone connections (CarPlay vs. Android Auto)
 * and feeds them to the DeviceRouter.
 */
class WirelessServer(
    private val deviceRouter: DeviceRouter,
    private val sink: MediaSink,
    private val onStatusUpdate: (status: String, isConnected: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "WirelessServer"
        const val PORT_MAIN = 5000
        const val PORT_ALT = 7000
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var listenThread: Thread? = null
    private var activeSocketChannel: SocketTransportChannel? = null

    @Synchronized
    fun start() {
        if (isRunning.getAndSet(true)) return

        listenThread = Thread({
            runServerLoop()
        }, "UniplayWirelessServer").apply {
            isDaemon = true
            start()
        }
    }

    private fun runServerLoop() {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(PORT_MAIN))
            serverSocket = server

            Log.i(TAG, "Wireless server listening on port $PORT_MAIN")
            onStatusUpdate("Listening on port $PORT_MAIN. Ready for phone...", false)

            while (isRunning.get()) {
                val clientSocket = server.accept()
                val remoteIp = clientSocket.inetAddress?.hostAddress ?: "Unknown"
                Log.i(TAG, "Incoming wireless connection from: $remoteIp")
                onStatusUpdate("Connecting: $remoteIp", true)

                handleIncomingConnection(clientSocket)
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Wireless server exception", e)
                onStatusUpdate("Server stopped: ${e.message}", false)
            }
        } finally {
            stop()
        }
    }

    private fun handleIncomingConnection(socket: Socket) {
        try {
            val transport = SocketTransportChannel(socket, TransportType.WIRELESS_WIFI)
            activeSocketChannel = transport

            // Peek initial bytes to detect protocol
            val bufferedInput = BufferedInputStream(socket.getInputStream())
            bufferedInput.mark(16)
            val peekBuffer = ByteArray(4)
            val bytesRead = bufferedInput.read(peekBuffer, 0, 4)
            bufferedInput.reset()

            // If starts with RTSP/AirPlay ascii (e.g. 'O' for OPTIONS, 'A' for ANNOUNCE, 'P' for POST) -> CarPlay
            // If starts with binary 0x00 or AAP channel header (0, 1, 2) -> Android Auto
            val detectedType = if (bytesRead >= 1) {
                val firstChar = peekBuffer[0].toInt().toChar()
                if (firstChar in 'A'..'Z') {
                    Log.i(TAG, "Detected Apple CarPlay AirPlay RTSP handshake")
                    ProjectionType.CARPLAY
                } else {
                    Log.i(TAG, "Detected Google Android Auto AAP framing")
                    ProjectionType.ANDROID_AUTO
                }
            } else {
                ProjectionType.ANDROID_AUTO
            }

            // Wrap buffered input back into a proxy transport
            val proxyTransport = object : TransportChannel {
                override val type: TransportType = TransportType.WIRELESS_WIFI
                override val inputStream: InputStream = bufferedInput
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun close() = transport.close()
            }

            deviceRouter.onManualSelect(detectedType, proxyTransport, sink)
            onStatusUpdate("Connected: ${detectedType.name} (${remoteIpSafe(socket)})", true)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection", e)
            onStatusUpdate("Connection failed: ${e.message}", false)
        }
    }

    private fun remoteIpSafe(socket: Socket): String {
        return socket.inetAddress?.hostAddress ?: "Wireless"
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Shutting down wireless server...")
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        activeSocketChannel?.close()
        activeSocketChannel = null

        listenThread?.interrupt()
        listenThread = null
        onStatusUpdate("Wireless server stopped", false)
    }
}
