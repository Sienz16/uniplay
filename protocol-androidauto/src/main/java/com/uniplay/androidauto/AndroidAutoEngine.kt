package com.uniplay.androidauto

import android.util.Log
import com.uniplay.core.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Auto protocol engine implementation.
 * Handles framing, Protobuf message dispatching, and streaming to MediaSink.
 */
class AndroidAutoEngine : ProjectionEngine {

    companion object {
        private const val TAG = "AndroidAutoEngine"
        const val CHANNEL_CONTROL = 0
        const val CHANNEL_VIDEO = 1
        const val CHANNEL_AUDIO_MEDIA = 2
        const val CHANNEL_AUDIO_GUIDANCE = 3
        const val CHANNEL_INPUT = 4
        const val CHANNEL_SENSOR = 5

        private const val MAX_AAP_PAYLOAD = 4 * 1024 * 1024 // 4MB maximum safe frame size
    }

    override val type: ProjectionType = ProjectionType.ANDROID_AUTO
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
            runAapLoop(transport.inputStream, transport.outputStream, sink)
        }, "AapEngineThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun runAapLoop(input: InputStream, output: OutputStream, sink: MediaSink) {
        Log.i(TAG, "Android Auto session started. Listening for AAP frames...")
        val headerBuffer = ByteArray(4)

        try {
            while (running.get()) {
                // Read 4-byte AAP frame header: [Channel(1B)][Flags(1B)][Length(2B)]
                var bytesRead = 0
                while (bytesRead < 4) {
                    val r = input.read(headerBuffer, bytesRead, 4 - bytesRead)
                    if (r < 0) throw IllegalStateException("EOF reached in AAP input stream")
                    bytesRead += r
                }

                val channel = headerBuffer[0].toInt() and 0xFF
                val flags = headerBuffer[1].toInt() and 0xFF
                val length = ((headerBuffer[2].toInt() and 0xFF) shl 8) or (headerBuffer[3].toInt() and 0xFF)

                // Security bounds verification to prevent DoS / OOM crashes
                if (length <= 0 || length > MAX_AAP_PAYLOAD) {
                    throw IllegalArgumentException("Invalid AAP frame length: $length bytes")
                }

                // Read payload
                val payload = ByteArray(length)
                var payloadRead = 0
                while (payloadRead < length) {
                    val r = input.read(payload, payloadRead, length - payloadRead)
                    if (r < 0) throw IllegalStateException("EOF reading AAP payload")
                    payloadRead += r
                }

                dispatchPacket(channel, flags, payload, sink)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Android Auto loop terminated: ${e.message}")
            sink.onSessionTerminated(e.message ?: "Connection closed")
        } finally {
            stop()
        }
    }

    private fun dispatchPacket(channel: Int, flags: Int, payload: ByteArray, sink: MediaSink) {
        when (channel) {
            CHANNEL_VIDEO -> {
                // H.264 video frame packet
                sink.onVideoNal(payload, 0, payload.size, System.nanoTime() / 1000)
            }
            CHANNEL_AUDIO_MEDIA -> {
                // High quality music stream
                sink.onAudioData(AudioChannel.MEDIA, payload, 48000, 2)
            }
            CHANNEL_AUDIO_GUIDANCE -> {
                // Navigation prompt stream
                sink.onAudioData(AudioChannel.NAVIGATION, payload, 16000, 1)
            }
            CHANNEL_CONTROL -> {
                // Service discovery / Handshake packet
                Log.d(TAG, "Received control packet (${payload.size} bytes, flags=$flags)")
            }
            CHANNEL_INPUT -> {
                // Input ack
                Log.d(TAG, "Received input acknowledgment (${payload.size} bytes)")
            }
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping Android Auto engine...")
        try {
            currentTransport?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing transport", e)
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
            // Formulate AAP Touch Input frame on CHANNEL_INPUT
            val payload = ByteBuffer.allocate(9).apply {
                put(action.toByte())
                putFloat(x)
                putFloat(y)
            }.array()

            val frame = ByteArray(4 + payload.size).apply {
                this[0] = CHANNEL_INPUT.toByte()
                this[1] = 0 // Flags
                this[2] = ((payload.size shr 8) and 0xFF).toByte()
                this[3] = (payload.size and 0xFF).toByte()
                System.arraycopy(payload, 0, this, 4, payload.size)
            }

            synchronized(transport) {
                transport.outputStream.write(frame)
                transport.outputStream.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send AAP touch event: ${e.message}")
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

            val frame = ByteArray(4 + payload.size).apply {
                this[0] = CHANNEL_INPUT.toByte()
                this[1] = 0 // Flags
                this[2] = ((payload.size shr 8) and 0xFF).toByte()
                this[3] = (payload.size and 0xFF).toByte()
                System.arraycopy(payload, 0, this, 4, payload.size)
            }

            synchronized(transport) {
                transport.outputStream.write(frame)
                transport.outputStream.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send AAP key event: ${e.message}")
        }
    }
}
