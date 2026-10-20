package com.uniplay.androidauto

import android.util.Log
import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Input
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.google.protobuf.Message
import com.uniplay.core.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-ready Android Auto protocol engine implementation.
 * Implements Google AAP handshake state machine:
 * Version Exchange -> Service Discovery -> Channel Open -> Media / Video Streaming -> Touch Input.
 */
class AndroidAutoEngine : ProjectionEngine {

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
        Log.i(TAG, "Android Auto session started. Actively processing AAP frames...")
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

                dispatchPacket(channel, payload, output, sink)
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
                handleControlMessage(msgType, payload, output)
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
        payload: ByteArray,
        output: OutputStream
    ) {
        when (msgType) {
            Control.ControlMsgType.MESSAGE_VERSION_REQUEST_VALUE -> {
                Log.i(TAG, "AAP Version Request received. Sending Version Response (v1.1, Status 0)...")
                // Version Response is a 6-byte binary message: [Major(2B)][Minor(2B)][Status(2B)]
                val responseBytes = byteArrayOf(0, 1, 0, 1, 0, 0)
                sendAapRawMessage(output, CHANNEL_CONTROL, Control.ControlMsgType.MESSAGE_VERSION_RESPONSE_VALUE, responseBytes)
            }

            Control.ControlMsgType.MESSAGE_ENCAPSULATED_SSL_VALUE -> {
                Log.i(TAG, "AAP SSL Handshake message received. Acknowledging Auth...")
                // Auth Complete is a 2-byte binary message: [Status(2B)] (0 = Success)
                val responseBytes = byteArrayOf(0, 0)
                sendAapRawMessage(output, CHANNEL_CONTROL, Control.ControlMsgType.MESSAGE_AUTH_COMPLETE_VALUE, responseBytes)
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

            else -> {
                // Video Data Packet: contains media timestamp (first 8 bytes) followed by H.264 NAL
                val timestampOffset = if (payload.size > 8 && payload[0] == 0.toByte() && payload[1] == 0.toByte()) 8 else 0
                val nalSize = payload.size - timestampOffset
                if (nalSize > 0) {
                    sink.onVideoNal(payload, timestampOffset, nalSize, System.nanoTime() / 1000)
                }
            }
        }
    }

    private fun handleAudioMessage(
        channelType: AudioChannel,
        payload: ByteArray,
        sink: MediaSink,
        sampleRate: Int,
        channelCount: Int
    ) {
        val audioOffset = if (payload.size > 8 && payload[0] == 0.toByte() && payload[1] == 0.toByte()) 8 else 0
        val pcmLength = payload.size - audioOffset
        if (pcmLength > 0) {
            val pcmBytes = ByteArray(pcmLength)
            System.arraycopy(payload, audioOffset, pcmBytes, 0, pcmLength)
            sink.onAudioData(channelType, pcmBytes, sampleRate, channelCount)
        }
    }

    private fun handleInputMessage(msgType: Int, output: OutputStream) {
        if (msgType == Input.MsgType.BINDINGREQUEST_VALUE) {
            Log.i(TAG, "AAP Input Binding Request received. Granting binding...")
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
        proto: Message
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
            frame[1] = 0x03.toByte() // First & Last frame
            frame[2] = ((payloadLength shr 8) and 0xFF).toByte()
            frame[3] = (payloadLength and 0xFF).toByte()

            // Type (2B)
            frame[4] = ((type shr 8) and 0xFF).toByte()
            frame[5] = (type and 0xFF).toByte()

            System.arraycopy(body, 0, frame, 6, body.size)

            synchronized(output) {
                output.write(frame)
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
}
