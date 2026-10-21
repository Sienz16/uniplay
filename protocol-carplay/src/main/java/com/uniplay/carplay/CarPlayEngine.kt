package com.uniplay.carplay

import android.util.Log
import com.uniplay.core.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Apple CarPlay protocol engine implementation.
 * Manages AirPlay/iAP2 transport framing and streams decrypted media to MediaSink.
 *
 * NOT a working receiver yet. The packet framing below is a placeholder, not real
 * AirPlay or iAP2: a real session needs Apple's MFi authentication, an RTSP/AirPlay
 * handshake and stream decryption, none of which exist here. The app refuses CarPlay
 * connections up front (see WirelessServer and ProjectionSessionController).
 */
class CarPlayEngine : ProjectionEngine {

    companion object {
        private const val TAG = "CarPlayEngine"
        const val PACKET_TYPE_VIDEO = 0x00
        const val PACKET_TYPE_AUDIO_MEDIA = 0x01
        const val PACKET_TYPE_AUDIO_ALT = 0x02 // Siri / Navigation
        const val PACKET_TYPE_CONTROL = 0x03

        private const val MAX_CARPLAY_PAYLOAD = 4 * 1024 * 1024 // 4MB maximum safe frame size
    }

    override val type: ProjectionType = ProjectionType.CARPLAY
    private val running = AtomicBoolean(false)
    override val isRunning: Boolean
        get() = running.get()

    private var currentTransport: TransportChannel? = null
    private var currentSink: MediaSink? = null
    private var workerThread: Thread? = null

    override fun start(transport: TransportChannel, sink: MediaSink) {
        if (running.getAndSet(true)) return
        currentTransport = transport
        currentSink = sink

        workerThread = Thread({
            runAirPlayLoop(transport.inputStream, sink)
        }, "CarPlayEngineThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun runAirPlayLoop(input: InputStream, sink: MediaSink) {
        Log.i(TAG, "CarPlay session started. Listening for AirPlay/iAP2 stream...")
        val header = ByteArray(8)

        try {
            while (running.get()) {
                // Read 8-byte framing header: [Type(2B)][PayloadLen(4B)][Reserved(2B)]
                readFully(input, header, "EOF in CarPlay transport stream")

                val type = readPacketType(header)
                val rawLength = readPacketLength(header)

                // Enforce strict bounds check to prevent OOM / negative array allocation crash
                if (rawLength <= 0 || rawLength > MAX_CARPLAY_PAYLOAD) {
                    throw IllegalArgumentException("Invalid CarPlay packet length: $rawLength bytes")
                }

                val payload = ByteArray(rawLength)
                readFully(input, payload, "EOF reading CarPlay payload")

                dispatchPacket(type, payload, sink)
            }
        } catch (e: Exception) {
            Log.w(TAG, "CarPlay loop terminated: ${e.message}")
            sink.onSessionTerminated(e.message ?: "Connection closed")
        } finally {
            stop()
        }
    }

    private fun readPacketType(header: ByteArray): Int =
        ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)

    private fun readPacketLength(header: ByteArray): Int =
        ((header[2].toInt() and 0xFF) shl 24) or
                ((header[3].toInt() and 0xFF) shl 16) or
                ((header[4].toInt() and 0xFF) shl 8) or
                (header[5].toInt() and 0xFF)

    private fun dispatchPacket(type: Int, payload: ByteArray, sink: MediaSink) {
        when (type) {
            PACKET_TYPE_VIDEO -> {
                sink.onVideoNal(payload, 0, payload.size, System.nanoTime() / 1000)
            }
            PACKET_TYPE_AUDIO_MEDIA -> {
                sink.onAudioData(AudioChannel.MEDIA, payload, 44100, 2)
            }
            PACKET_TYPE_AUDIO_ALT -> {
                sink.onAudioData(AudioChannel.SPEECH_SIRI_ASSISTANT, payload, 24000, 1)
            }
            PACKET_TYPE_CONTROL -> {
                Log.d(TAG, "AirPlay control event received (${payload.size} bytes)")
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, eofMessage: String) {
        var offset = 0
        while (offset < buffer.size) {
            val bytesRead = input.read(buffer, offset, buffer.size - offset)
            if (bytesRead < 0) throw IllegalStateException(eofMessage)
            offset += bytesRead
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping CarPlay engine...")
        try {
            currentTransport?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing CarPlay transport", e)
        }
        workerThread?.interrupt()
        workerThread = null
        currentTransport = null
        currentSink = null
    }

    override fun sendTouchEvent(action: Int, x: Float, y: Float) {
        val transport = currentTransport ?: return
        if (!running.get()) return

        try {
            // Formulate iAP2 HID touch report packet
            val payload = ByteBuffer.allocate(9).apply {
                put(action.toByte())
                putFloat(x)
                putFloat(y)
            }.array()
            sendControlPayload(transport, payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send CarPlay touch: ${e.message}")
        }
    }

    override fun sendKeyEvent(keyCode: Int, isDown: Boolean) {
        val transport = currentTransport ?: return
        if (!running.get()) return

        try {
            val payload = ByteBuffer.allocate(5).apply {
                putInt(keyCode)
                put(if (isDown) 1.toByte() else 0.toByte())
            }.array()
            sendControlPayload(transport, payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send CarPlay key: ${e.message}")
        }
    }

    private fun sendControlPayload(transport: TransportChannel, payload: ByteArray) {
        val packet = ByteArray(8 + payload.size).apply {
            this[0] = 0
            this[1] = PACKET_TYPE_CONTROL.toByte()
            this[2] = ((payload.size shr 24) and 0xFF).toByte()
            this[3] = ((payload.size shr 16) and 0xFF).toByte()
            this[4] = ((payload.size shr 8) and 0xFF).toByte()
            this[5] = (payload.size and 0xFF).toByte()
            this[6] = 0
            this[7] = 0
            System.arraycopy(payload, 0, this, 8, payload.size)
        }

        synchronized(transport) {
            transport.outputStream.write(packet)
            transport.outputStream.flush()
        }
    }
}
