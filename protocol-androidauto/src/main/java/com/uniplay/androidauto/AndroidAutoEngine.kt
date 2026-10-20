package com.uniplay.androidauto

import android.content.Context
import android.util.Log
import com.andrerinas.openheadunit.aap.AapSslContext
import com.andrerinas.openheadunit.aap.Utils
import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import com.andrerinas.openheadunit.aap.protocol.proto.*
import com.andrerinas.openheadunit.connection.AccessoryConnection
import com.andrerinas.openheadunit.ssl.SingleKeyKeyManager
import com.uniplay.core.*
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-ready Android Auto protocol engine implementation.
 * Implements complete Google AAP handshake state machine:
 * Version Exchange (v1.2) -> TLS 1.2 Authentication -> Service Discovery -> Channel Open -> 60fps Video.
 */
class AndroidAutoEngine(private val context: Context? = null) : ProjectionEngine {

    companion object {
        private const val TAG = "AndroidAutoEngine"

        const val CHANNEL_CONTROL = 0
        const val CHANNEL_VIDEO = 1
        const val CHANNEL_AUDIO_SYSTEM = 2
        const val CHANNEL_AUDIO_SPEECH = 3
        const val CHANNEL_AUDIO_MEDIA = 4
        const val CHANNEL_INPUT = 5
        const val CHANNEL_SENSOR = 6

        private const val MAX_AAP_PAYLOAD = 4 * 1024 * 1024 // 4MB maximum frame size
    }

    override val type: ProjectionType = ProjectionType.ANDROID_AUTO
    private val running = AtomicBoolean(false)
    override val isRunning: Boolean
        get() = running.get()

    private var currentTransport: TransportChannel? = null
    private var currentSink: MediaSink? = null
    private var workerThread: Thread? = null

    private var sslContext: AapSslContext? = null
    private var isSslHandshakeComplete = false

    override fun start(transport: TransportChannel, sink: MediaSink) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Android Auto session already active. Restarting...")
            stop()
            running.set(true)
        }

        currentTransport = transport
        currentSink = sink
        isSslHandshakeComplete = false

        workerThread = Thread({
            runAapLoop(transport, sink)
        }, "AapEngineThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun runAapLoop(transport: TransportChannel, sink: MediaSink) {
        Log.i(TAG, "Starting Android Auto AAP session. Initializing handshake...")
        val conn = TransportAccessoryConnection(transport)
        val input = transport.inputStream
        val output = transport.outputStream
        val dataIn = DataInputStream(input)

        try {
            // STEP 1 & 2: Version Exchange (v1.2) with timeout and up to 3 retries
            var versionReceived = false
            val verBuf = ByteArray(Messages.DEF_BUFFER_LENGTH)

            for (attempt in 1..4) {
                Log.i(TAG, "Handshake: Sending AAP Version Request (attempt $attempt/4)...")
                val versionReq = Messages.versionRequest
                conn.sendBlocking(versionReq, versionReq.size, 2000)

                val deadline = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < deadline) {
                    val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(100)
                    val ret = conn.recvBlocking(verBuf, verBuf.size, remaining, false)
                    if (ret >= 6
                        && verBuf[0] == 0.toByte()
                        && verBuf[4] == 0.toByte()
                        && verBuf[5] == 2.toByte()
                    ) {
                        Log.i(TAG, "Handshake: Version Response received! ($ret bytes, attempt $attempt)")
                        versionReceived = true
                        break
                    }
                    if (ret <= 0) break
                }
                if (versionReceived) break
                Thread.sleep(300)
            }

            if (!versionReceived) {
                throw IllegalStateException("Phone did not respond. Please toggle 'Stop head unit server' then 'Start head unit server' on your phone.")
            }

            // STEP 3: Perform TLS 1.2 Handshake (Channel 0, Msg 3)
            val ctx = context
            if (ctx != null) {
                Log.i(TAG, "Handshake: Initializing SSL Context & Keys...")
                val keyManager = SingleKeyKeyManager(ctx)
                val ssl = AapSslContext(keyManager)
                sslContext = ssl

                Log.i(TAG, "Handshake: Executing TLS 1.2 Authentication with Phone...")
                val sslSuccess = ssl.performHandshake(conn)
                if (sslSuccess) {
                    isSslHandshakeComplete = true
                    ssl.postHandshakeReset()
                    Log.i(TAG, "Handshake: TLS Handshake SUCCESSFUL! Sending Status OK to Phone...")
                    val statusOk = Messages.statusOk
                    conn.sendBlocking(statusOk, statusOk.size, 2000)
                    Log.i(TAG, "Handshake: Status OK sent! Awaiting Service Discovery Request...")
                } else {
                    Log.w(TAG, "Handshake: TLS Handshake failed, proceeding with fallback...")
                }
            } else {
                Log.w(TAG, "Context unavailable; skipping TLS handshake.")
            }

            // Reset socket timeout to non-blocking stream mode
            (transport as? SocketTransportChannel)?.socket?.soTimeout = 0

            // STEP 4: Standard AAP Message Loop
            val headerBuffer = ByteArray(4)
            while (running.get()) {
                dataIn.readFully(headerBuffer)

                val channel = headerBuffer[0].toInt() and 0xFF
                val flags = headerBuffer[1].toInt() and 0xFF
                val length = ((headerBuffer[2].toInt() and 0xFF) shl 8) or (headerBuffer[3].toInt() and 0xFF)

                if (length <= 0 || length > MAX_AAP_PAYLOAD) {
                    throw IllegalArgumentException("Invalid AAP frame length: $length bytes")
                }

                val payload = ByteArray(length)
                dataIn.readFully(payload)

                val decryptedPayload = if (isSslHandshakeComplete && (flags and 0x08 != 0)) {
                    val decrypted = sslContext?.decrypt(0, payload.size, payload)
                    if (decrypted != null) {
                        val plain = ByteArray(decrypted.limit)
                        System.arraycopy(decrypted.data, 0, plain, 0, decrypted.limit)
                        plain
                    } else {
                        payload
                    }
                } else {
                    payload
                }

                dispatchPacket(channel, decryptedPayload, output, sink)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Android Auto loop terminated: ${e.message}")
            sink.onSessionTerminated(e.message ?: "Connection closed")
        } finally {
            stop()
        }
    }

    private fun dispatchPacket(
        channel: Int,
        payload: ByteArray,
        output: OutputStream,
        sink: MediaSink
    ) {
        if (payload.size < 2) return

        val msgType = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

        when (channel) {
            CHANNEL_CONTROL -> {
                handleControlMessage(msgType, output)
            }

            CHANNEL_VIDEO -> {
                handleVideoMessage(msgType, payload, output, sink)
            }

            CHANNEL_AUDIO_MEDIA -> {
                handleAudioMessage(AudioChannel.MEDIA, payload, sink, 48000, 2)
            }

            CHANNEL_AUDIO_SPEECH, CHANNEL_AUDIO_SYSTEM -> {
                handleAudioMessage(AudioChannel.NAVIGATION, payload, sink, 16000, 1)
            }

            CHANNEL_INPUT -> {
                handleInputMessage(msgType, output)
            }
        }
    }

    private fun handleControlMessage(
        msgType: Int,
        output: OutputStream
    ) {
        when (msgType) {
            Control.ControlMsgType.MESSAGE_VERSION_REQUEST_VALUE -> {
                Log.i(TAG, "AAP Version Request received from Phone. Sending Version Response...")
                val responseBytes = byteArrayOf(0, 1, 0, 1, 0, 0)
                sendAapRawMessage(output, CHANNEL_CONTROL, Control.ControlMsgType.MESSAGE_VERSION_RESPONSE_VALUE, responseBytes)
            }

            Control.ControlMsgType.MESSAGE_AUTH_COMPLETE_VALUE -> {
                Log.i(TAG, "AAP Phone Authentication Complete acknowledged!")
            }

            Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE -> {
                Log.i(TAG, "AAP Service Discovery Request received. Delivering display & audio capabilities...")
                val response = AapServiceDiscovery.buildResponse()
                sendAapProtoMessage(output, CHANNEL_CONTROL, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, response)
            }

            Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE -> {
                Log.i(TAG, "AAP Channel Open Request received. Granting channel open...")
                val response = Control.ChannelOpenResponse.newBuilder()
                    .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                    .build()
                sendAapProtoMessage(output, CHANNEL_CONTROL, Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE, response)
            }

            Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE -> {
                val response = Control.PingResponse.newBuilder()
                    .setTimestamp(System.nanoTime())
                    .build()
                sendAapProtoMessage(output, CHANNEL_CONTROL, Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, response)
            }

            Control.ControlMsgType.MESSAGE_NAV_FOCUS_REQUEST_VALUE -> {
                val response = Control.NavFocusNotification.newBuilder()
                    .setFocusType(Control.NavFocusType.NAV_FOCUS_2)
                    .build()
                sendAapProtoMessage(output, CHANNEL_CONTROL, Control.ControlMsgType.MESSAGE_NAV_FOCUS_NOTIFICATION_VALUE, response)
            }
        }
    }

    private fun handleVideoMessage(
        msgType: Int,
        payload: ByteArray,
        output: OutputStream,
        sink: MediaSink
    ) {
        when (msgType) {
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> {
                Log.i(TAG, "AAP Video Focus Request received. Granting PROJECTED focus...")
                val response = Media.VideoFocusNotification.newBuilder()
                    .setMode(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED)
                    .setUnsolicited(false)
                    .build()
                sendAapProtoMessage(output, CHANNEL_VIDEO, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, response)
            }

            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                Log.i(TAG, "AAP Video Stream Start confirmed")
            }

            Media.MsgType.MEDIA_MESSAGE_DATA_VALUE -> {
                // Video frame payload (offset 2 skips msgType)
                if (payload.size > 2) {
                    val nalOffset = 2
                    val nalLength = payload.size - 2
                    sink.onVideoNal(payload, nalOffset, nalLength, System.nanoTime() / 1000)
                }
            }
        }
    }

    private fun handleAudioMessage(
        channel: AudioChannel,
        payload: ByteArray,
        sink: MediaSink,
        sampleRate: Int,
        channels: Int
    ) {
        if (payload.size > 2) {
            val audioPcm = ByteArray(payload.size - 2)
            System.arraycopy(payload, 2, audioPcm, 0, audioPcm.size)
            sink.onAudioData(channel, audioPcm, sampleRate, channels)
        }
    }

    private fun handleInputMessage(msgType: Int, output: OutputStream) {
        if (msgType == Input.MsgType.BINDINGREQUEST_VALUE) {
            Log.i(TAG, "AAP Input Binding Request received. Responding success...")
            val response = Input.BindingResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                .build()
            sendAapProtoMessage(output, CHANNEL_INPUT, Input.MsgType.BINDINGRESPONSE_VALUE, response)
        }
    }

    private fun sendAapProtoMessage(
        output: OutputStream,
        channel: Int,
        type: Int,
        proto: com.google.protobuf.MessageLite
    ) {
        val protoBytes = proto.toByteArray()
        sendAapRawMessage(output, channel, type, protoBytes)
    }

    private fun sendAapRawMessage(
        output: OutputStream,
        channel: Int,
        type: Int,
        body: ByteArray
    ) {
        try {
            val payloadLength = 2 + body.size
            val frame = ByteArray(4 + payloadLength)

            // Header (4B): [Channel(1B)][Flags(1B)][PayloadLen(2B)]
            frame[0] = channel.toByte()
            val baseFlags = 0x03 // First & Last frame
            frame[1] = baseFlags.toByte()
            frame[2] = ((payloadLength shr 8) and 0xFF).toByte()
            frame[3] = (payloadLength and 0xFF).toByte()

            // Type (2B)
            frame[4] = ((type shr 8) and 0xFF).toByte()
            frame[5] = (type and 0xFF).toByte()

            System.arraycopy(body, 0, frame, 6, body.size)

            val ssl = sslContext
            if (isSslHandshakeComplete && ssl != null) {
                // Encrypt payload (frame[4..])
                val encrypted = ssl.encrypt(4, payloadLength, frame)
                if (encrypted != null) {
                    val encData = encrypted.data
                    encData[0] = channel.toByte()
                    encData[1] = (baseFlags or 0x08).toByte() // Mark encrypted
                    Utils.intToBytes(encrypted.limit - 4, 2, encData)
                    synchronized(output) {
                        output.write(encData, 0, encrypted.limit)
                        output.flush()
                    }
                    return
                }
            }

            synchronized(output) {
                output.write(frame, 0, frame.size)
                output.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send AAP message (channel=$channel, type=$type): ${e.message}")
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
        sslContext = null
        isSslHandshakeComplete = false
    }

    override fun sendTouchEvent(action: Int, x: Float, y: Float) {
        val transport = currentTransport ?: return
        if (!running.get()) return

        try {
            val pointerAction = when (action) {
                android.view.MotionEvent.ACTION_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                android.view.MotionEvent.ACTION_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
                else -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
            }

            val touchEvent = Input.TouchEvent.newBuilder().apply {
                addPointerData(
                    Input.TouchEvent.Pointer.newBuilder().apply {
                        pointerId = 0
                        this.x = x.toInt()
                        this.y = y.toInt()
                    }
                )
                actionIndex = 0
                this.action = pointerAction
            }.build()

            val inputReport = Input.InputReport.newBuilder().apply {
                timestamp = System.nanoTime()
                this.touchEvent = touchEvent
            }.build()

            sendAapProtoMessage(transport.outputStream, CHANNEL_INPUT, Input.MsgType.EVENT_VALUE, inputReport)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send AAP touch: ${e.message}")
        }
    }

    override fun sendKeyEvent(keyCode: Int, isDown: Boolean) {
        val transport = currentTransport ?: return
        if (!running.get()) return

        try {
            val keyEvent = Input.KeyEvent.newBuilder().apply {
                addKeys(
                    Input.Key.newBuilder().apply {
                        this.keycode = keyCode
                        this.down = isDown
                        this.longpress = false
                        this.metastate = 0
                    }
                )
            }.build()

            val inputReport = Input.InputReport.newBuilder().apply {
                timestamp = System.nanoTime()
                this.keyEvent = keyEvent
            }.build()

            sendAapProtoMessage(transport.outputStream, CHANNEL_INPUT, Input.MsgType.EVENT_VALUE, inputReport)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send AAP key: ${e.message}")
        }
    }

    /**
     * Adapter wrapping TransportChannel into OpenHeadUnit's AccessoryConnection
     * so that AapSslContext can perform the TLS handshake seamlessly.
     */
    private class TransportAccessoryConnection(
        private val transport: TransportChannel
    ) : AccessoryConnection {
        private val dataInput = DataInputStream(transport.inputStream)
        private val rawSocket = (transport as? SocketTransportChannel)?.socket

        override val isSingleMessage: Boolean get() = false

        override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
            return try {
                transport.outputStream.write(buf, 0, length)
                transport.outputStream.flush()
                length
            } catch (e: Exception) {
                Log.w(TAG, "sendBlocking failed: ${e.message}")
                -1
            }
        }

        override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
            return try {
                try { rawSocket?.soTimeout = timeout } catch (_: Exception) {}
                if (readFully) {
                    dataInput.readFully(buf, 0, length)
                    length
                } else {
                    dataInput.read(buf, 0, length)
                }
            } catch (e: Exception) {
                -1
            }
        }

        override val isConnected: Boolean get() = rawSocket?.isConnected ?: true
        override suspend fun connect(): Boolean = true
        override fun disconnect() {
            try { transport.close() } catch (_: Exception) {}
        }
    }
}
