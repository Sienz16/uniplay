package com.uniplay.androidauto

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
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
import java.util.concurrent.ConcurrentHashMap
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
        const val CHANNEL_SENSOR = 1
        const val CHANNEL_VIDEO = 2
        const val CHANNEL_INPUT = 3
        const val CHANNEL_AUDIO_SPEECH = 4
        const val CHANNEL_AUDIO_SYSTEM = 5
        const val CHANNEL_AUDIO_MEDIA = 6
        const val CHANNEL_MIC = 7
        const val CHANNEL_MEDIA_PLAYBACK = 9
        const val CHANNEL_NAVIGATION_STATUS = 10

        private const val MAX_AAP_PAYLOAD = 4 * 1024 * 1024 // 4MB maximum frame size

        // AAP video framing flags.
        /** A whole access unit in one message. */
        private const val FLAG_SINGLE = 11

        /** The first fragment of an access unit. Carries the message type. */
        private const val FLAG_FIRST = 9

        /** A fragment that is neither the first nor the last. Raw access-unit bytes. */
        private const val FLAG_MIDDLE = 8

        /** The last fragment of an access unit. Raw access-unit bytes. */
        private const val FLAG_LAST = 10

        /** Payload offset of the access unit when the message carries a timestamp indication. */
        private const val OFFSET_TIMESTAMP_INDICATION = 10

        /** Payload offset of the access unit when the message carries only a media indication. */
        private const val OFFSET_MEDIA_INDICATION = 2

        /** How far into an access unit the SPS/PPS are looked for. */
        private const val VIDEO_CONFIG_SCAN_LIMIT = 16 * 1024

        /** How often the video throughput summary is logged. */
        private const val VIDEO_STATS_INTERVAL_MS = 5000L
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
    private val sessionIds = ConcurrentHashMap<Int, Int>()
    private val videoReassemblyBuffer = java.io.ByteArrayOutputStream(256 * 1024)
    private val inputExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** True while a fragmented access unit is being assembled. */
    private var videoAssembling = false

    /** The last SPS handed to the sink, so a changed one can be pushed again. */
    private var lastVideoSps: ByteArray? = null

    // Video throughput counters, reported periodically so "is the stream arriving?"
    // is answerable from the log alone.
    private var videoFramesReceived = 0L
    private var videoBytesReceived = 0L
    private var lastVideoStatsMs = 0L

    @Volatile
    private var videoConfigSent = false

    // Negotiated video size. Must match AapServiceDiscovery's 1280x720 config.
    private val videoWidth = 1280
    private val videoHeight = 720

    override fun start(transport: TransportChannel, sink: MediaSink) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Android Auto session already active. Restarting...")
            stop()
            running.set(true)
        }

        currentTransport = transport
        currentSink = sink
        isSslHandshakeComplete = false
        sessionIds.clear()
        videoConfigSent = false

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
            exchangeAapVersion(conn)
            performTlsHandshake(conn)

            // Reset socket timeout to non-blocking stream mode
            (transport as? SocketTransportChannel)?.socket?.soTimeout = 0
            receiveAapMessages(dataIn, output, sink)
        } catch (e: Exception) {
            Log.w(TAG, "Android Auto loop terminated", e)
            sink.onSessionTerminated(e.message ?: "Connection closed")
        } finally {
            stop()
        }
    }

    private fun exchangeAapVersion(conn: TransportAccessoryConnection) {
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
    }

    private fun performTlsHandshake(conn: TransportAccessoryConnection) {
        val ctx = context
        if (ctx == null) {
            Log.w(TAG, "Context unavailable; skipping TLS handshake.")
            return
        }

        Log.i(TAG, "Handshake: Initializing SSL Context & Keys...")
        val keyManager = SingleKeyKeyManager(ctx)
        val ssl = AapSslContext(keyManager)
        sslContext = ssl

        Log.i(TAG, "Handshake: Executing TLS 1.2 Authentication with Phone...")
        if (ssl.performHandshake(conn)) {
            isSslHandshakeComplete = true
            ssl.postHandshakeReset()
            Log.i(TAG, "Handshake: TLS Handshake SUCCESSFUL! Sending Status OK to Phone...")
            val statusOk = Messages.statusOk
            conn.sendBlocking(statusOk, statusOk.size, 2000)
            Log.i(TAG, "Handshake: Status OK sent! Awaiting Service Discovery Request...")
        } else {
            Log.w(TAG, "Handshake: TLS Handshake failed, proceeding with fallback...")
        }
    }

    private fun receiveAapMessages(dataIn: DataInputStream, output: OutputStream, sink: MediaSink) {
        val headerBuffer = ByteArray(4)

        while (running.get()) {
            dataIn.readFully(headerBuffer)

            val channel = headerBuffer[0].toInt() and 0xFF
            val flags = headerBuffer[1].toInt() and 0xFF
            val length = ((headerBuffer[2].toInt() and 0xFF) shl 8) or (headerBuffer[3].toInt() and 0xFF)

            // Critical AAP Wire Protocol: When flags has 0x09 (FLAG_FIRST_FRAGMENT), the wire contains 4 bytes of total unfragmented length right after the header!
            if (flags == 0x09) {
                val declaredTotal = dataIn.readInt()
            }

            if (length <= 0 || length > MAX_AAP_PAYLOAD) {
                throw IllegalArgumentException("Invalid AAP frame length: $length bytes (channel=$channel, flags=0x${flags.toString(16)})")
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

            dispatchPacket(channel, flags, decryptedPayload, output, sink)
        }
    }

    private fun dispatchPacket(
        channel: Int,
        flags: Int,
        payload: ByteArray,
        output: OutputStream,
        sink: MediaSink
    ) {
        if (payload.isEmpty()) return

        // Video is dispatched first and entirely on its own terms. The continuation
        // fragments of an access unit carry no message type, so the generic type
        // handling below must never get to look at their first two bytes: doing so
        // both loses the fragment and puts a bogus message on the wire.
        if (channel == CHANNEL_VIDEO) {
            handleVideoMessage(flags, payload, output, sink)
            return
        }

        if (payload.size < 2) return
        val msgType = readMsgType(payload)

        // Channel Open Request (type 7) arrives on the channel it is about.
        if (msgType == Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE) {
            grantChannelOpen(channel, output)
            return
        }

        when (channel) {
            CHANNEL_CONTROL -> {
                handleControlMessage(msgType, output)
            }

            CHANNEL_AUDIO_MEDIA -> {
                handleAudioMessage(AudioChannel.MEDIA, CHANNEL_AUDIO_MEDIA, msgType, payload, output, sink, 48000, 2)
            }

            CHANNEL_AUDIO_SPEECH -> {
                handleAudioMessage(AudioChannel.NAVIGATION, CHANNEL_AUDIO_SPEECH, msgType, payload, output, sink, 16000, 1)
            }

            CHANNEL_AUDIO_SYSTEM -> {
                handleAudioMessage(AudioChannel.NAVIGATION, CHANNEL_AUDIO_SYSTEM, msgType, payload, output, sink, 16000, 1)
            }

            CHANNEL_INPUT -> {
                handleInputMessage(msgType, output)
            }
        }
    }

    /** The two-byte message type at the head of an AAP payload. */
    private fun readMsgType(payload: ByteArray): Int {
        return ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    }

    /**
     * Answers a Channel Open Request and announces an unrestricted driving status on
     * the sensor channel, which the phone waits for before it starts sending.
     */
    private fun grantChannelOpen(channel: Int, output: OutputStream) {
        Log.i(TAG, "AAP Channel Open Request received on channel $channel. Granting channel open...")
        val response = Control.ChannelOpenResponse.newBuilder()
            .setStatus(Common.MessageStatus.STATUS_SUCCESS)
            .build()
        sendAapProtoMessage(output, channel, Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE, response)

        if (channel == CHANNEL_SENSOR) {
            Log.i(TAG, "AAP Sensor channel opened: announcing UNRESTRICTED driving status")
            val sensorBatch = Sensors.SensorBatch.newBuilder()
                .addDrivingStatus(
                    Sensors.SensorBatch.DrivingStatusData.newBuilder()
                        .setStatus(Sensors.SensorBatch.DrivingStatusData.Status.UNRESTRICTED_VALUE)
                )
                .build()
            sendAapProtoMessage(output, CHANNEL_SENSOR, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, sensorBatch)
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
        flags: Int,
        payload: ByteArray,
        output: OutputStream,
        sink: MediaSink
    ) {
        // AAP video framing: 11 = whole access unit, 9 = first, 8 = middle, 10 = last.
        // Only the first and the single-frame messages carry a 2-byte message type; the
        // continuation fragments are raw access-unit bytes with no type of their own.
        // Control messages (channel open, etc.) arrive with bit 0x04 set, e.g. 0x0F.
        val isControlMessage = (flags and 0x04) != 0
        val hasType = isControlMessage || flags == FLAG_FIRST || flags == FLAG_SINGLE
        val msgType = if (hasType && payload.size >= 2) readMsgType(payload) else -1

        if (hasType && handleVideoControlMessage(msgType, payload, output)) return
        if (!handleVideoFrameFragment(flags, payload, sink)) return

        // Acknowledge the data and codec-config messages, the way every other head
        // unit does. The window the phone keeps open is counted in messages, so a
        // frame that is never acked stops the stream instead of slowing it down.
        if (hasType && (msgType == Media.MsgType.MEDIA_MESSAGE_DATA_VALUE ||
                msgType == Media.MsgType.MEDIA_MESSAGE_CODEC_CONFIG_VALUE)
        ) {
            sendVideoAck(output)
        }
    }

    private fun handleVideoControlMessage(msgType: Int, payload: ByteArray, output: OutputStream): Boolean {
        when (msgType) {
            Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE -> {
                grantChannelOpen(CHANNEL_VIDEO, output)
                return true
            }

            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                Log.i(TAG, "AAP Video Setup Request. Replying with Media Config (maxUnacked=12)...")
                val config = Media.Config.newBuilder()
                    .setStatus(Media.Config.ConfigStatus.HEADUNIT)
                    .setMaxUnacked(12)
                    .addConfigurationIndices(0)
                    .build()
                sendAapProtoMessage(output, CHANNEL_VIDEO, Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, config)

                Log.i(TAG, "AAP Announcing Video Focus PROJECTED...")
                val focusNotification = Media.VideoFocusNotification.newBuilder()
                    .setMode(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED)
                    .setUnsolicited(false)
                    .build()
                sendAapProtoMessage(output, CHANNEL_VIDEO, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, focusNotification)
                return true
            }

            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                val sessionId = if (payload.size >= 4) {
                    ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                } else 0
                sessionIds[CHANNEL_VIDEO] = sessionId
                Log.i(TAG, "AAP Video Stream Start confirmed (sessionId=$sessionId)")
                return true
            }

            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> {
                Log.i(TAG, "AAP Video Focus Request received. Granting PROJECTED focus...")
                val response = Media.VideoFocusNotification.newBuilder()
                    .setMode(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED)
                    .setUnsolicited(false)
                    .build()
                sendAapProtoMessage(output, CHANNEL_VIDEO, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, response)
                return true
            }
        }
        return false
    }

    private fun handleVideoFrameFragment(flags: Int, payload: ByteArray, sink: MediaSink): Boolean {
        return when (flags) {
            FLAG_SINGLE -> handleSingleVideoFrame(payload, sink)
            FLAG_FIRST -> startVideoFrameAssembly(payload)
            FLAG_MIDDLE -> appendVideoFrameFragment(flags, payload)
            FLAG_LAST -> finishVideoFrameAssembly(flags, payload, sink)
            else -> {
                Log.w(TAG, "Unknown video message flags 0x${flags.toString(16)}; ignoring")
                false
            }
        }
    }

    private fun handleSingleVideoFrame(payload: ByteArray, sink: MediaSink): Boolean {
        val payloadOffset = findVideoPayloadOffset(payload)
        if (payloadOffset < 0) {
            Log.w(TAG, "Single video message has no start code (len=${payload.size}); dropping it")
            return false
        }
        deliverVideoFrame(payload.copyOfRange(payloadOffset, payload.size), sink)
        return true
    }

    private fun startVideoFrameAssembly(payload: ByteArray): Boolean {
        val payloadOffset = findVideoPayloadOffset(payload)
        if (payloadOffset < 0) {
            Log.w(TAG, "First video fragment has no start code (len=${payload.size}); dropping the frame")
            videoAssembling = false
            videoReassemblyBuffer.reset()
            return false
        }
        videoAssembling = true
        videoReassemblyBuffer.reset()
        videoReassemblyBuffer.write(payload, payloadOffset, payload.size - payloadOffset)
        return true
    }

    private fun appendVideoFrameFragment(flags: Int, payload: ByteArray): Boolean {
        if (!videoAssembling) {
            Log.w(TAG, "Orphaned video fragment (flags=$flags); dropping it")
            return false
        }
        videoReassemblyBuffer.write(payload, 0, payload.size)
        return true
    }

    private fun finishVideoFrameAssembly(flags: Int, payload: ByteArray, sink: MediaSink): Boolean {
        if (!videoAssembling) {
            Log.w(TAG, "Orphaned video fragment (flags=$flags); dropping it")
            return false
        }
        videoReassemblyBuffer.write(payload, 0, payload.size)
        videoAssembling = false
        val frame = videoReassemblyBuffer.toByteArray()
        videoReassemblyBuffer.reset()
        if (frame.isNotEmpty()) deliverVideoFrame(frame, sink)
        return true
    }

    private fun sendVideoAck(output: OutputStream) {
        val sessionId = sessionIds[CHANNEL_VIDEO] ?: 0
        val ack = Media.Ack.newBuilder()
            .setSessionId(sessionId)
            .setAck(1)
            .build()
        sendAapProtoMessage(output, CHANNEL_VIDEO, Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, ack)
    }

    /** Hands a complete access unit to the decoder, parameter sets first. */
    private fun deliverVideoFrame(frame: ByteArray, sink: MediaSink) {
        sendVideoConfigIfFound(frame, sink)
        sink.onVideoNal(frame, 0, frame.size, System.nanoTime() / 1000)

        videoFramesReceived++
        videoBytesReceived += frame.size
        reportVideoStats()
    }

    /** Periodic one-line summary of the video stream. */
    private fun reportVideoStats() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastVideoStatsMs == 0L) {
            lastVideoStatsMs = now
            return
        }
        val elapsed = now - lastVideoStatsMs
        if (elapsed < VIDEO_STATS_INTERVAL_MS) return
        lastVideoStatsMs = now
        Log.i(
            TAG,
            "Video stream: ${videoFramesReceived} access units, ${videoBytesReceived / 1024} KB total " +
                "(${(videoBytesReceived / elapsed)} B/s)"
        )
    }

    /**
     * Extracts SPS/PPS from the first decoded access unit and hands them to the
     * sink so MediaCodec can be configured with csd-0/csd-1. Without this the
     * codec decodes nothing and the projection stays black.
     */
    private fun sendVideoConfigIfFound(data: ByteArray, sink: MediaSink) {
        // The parameter sets live in the first few hundred bytes of a keyframe, so a
        // bounded scan is enough and keeps the per-frame cost off the transport.
        val scanLength = minOf(data.size, VIDEO_CONFIG_SCAN_LIMIT)
        val sets = H264ParameterSets.find(data, 0, scanLength) ?: return

        val previous = lastVideoSps
        if (videoConfigSent && previous != null && previous.contentEquals(sets.sps)) return

        videoConfigSent = true
        lastVideoSps = sets.sps

        // Prefer the size the SPS reports: the phone may send something other than
        // the size we negotiated, and a decoder configured for the wrong size shows
        // nothing at all on some components.
        val width = if (sets.width > 0) sets.width else videoWidth
        val height = if (sets.height > 0) sets.height else videoHeight
        Log.i(
            TAG,
            "Video config detected: SPS=${sets.sps.size}b PPS=${sets.pps.size}b " +
                "size=${width}x$height. Configuring decoder."
        )
        sink.onVideoConfig(sets.sps, sets.pps, width, height)
    }

    /**
     * Offset of the access unit inside a first or single video message.
     *
     * The protocol puts the frame at offset 10 when the message carries a timestamp
     * indication and at offset 2 when it carries only a media indication. When
     * neither holds there is no access unit in this message at all, and -1 says so
     * rather than handing the codec a frame that lost its own first bytes.
     */
    private fun findVideoPayloadOffset(payload: ByteArray): Int {
        if (H264ParameterSets.payloadStartsAt(payload, OFFSET_TIMESTAMP_INDICATION, payload.size)) {
            return OFFSET_TIMESTAMP_INDICATION
        }
        if (H264ParameterSets.payloadStartsAt(payload, OFFSET_MEDIA_INDICATION, payload.size)) {
            return OFFSET_MEDIA_INDICATION
        }
        return -1
    }

    private fun handleAudioMessage(
        audioChannelType: AudioChannel,
        channelId: Int,
        msgType: Int,
        payload: ByteArray,
        output: OutputStream,
        sink: MediaSink,
        sampleRate: Int,
        channels: Int
    ) {
        when (msgType) {
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                Log.i(TAG, "AAP Audio Setup Request on channel $channelId. Replying with Media Config...")
                val config = Media.Config.newBuilder()
                    .setStatus(Media.Config.ConfigStatus.HEADUNIT)
                    .setMaxUnacked(30)
                    .addConfigurationIndices(0)
                    .build()
                sendAapProtoMessage(output, channelId, Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, config)

                // Grant audio focus unsolicited
                val focusNotification = Control.AudioFocusNotification.newBuilder()
                    .setFocusState(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN)
                    .setUnsolicited(true)
                    .build()
                sendAapProtoMessage(output, CHANNEL_CONTROL, Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE, focusNotification)
            }

            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                val sessionId = if (payload.size >= 4) {
                    ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                } else 0
                sessionIds[channelId] = sessionId
                Log.i(TAG, "AAP Audio Stream Start confirmed on channel $channelId (sessionId=$sessionId)")
            }

            Media.MsgType.MEDIA_MESSAGE_DATA_VALUE -> {
                if (payload.size > 2) {
                    val audioPcm = ByteArray(payload.size - 2)
                    System.arraycopy(payload, 2, audioPcm, 0, audioPcm.size)
                    sink.onAudioData(audioChannelType, audioPcm, sampleRate, channels)

                    val sessionId = sessionIds[channelId] ?: 0
                    val ack = Media.Ack.newBuilder()
                        .setSessionId(sessionId)
                        .setAck(1)
                        .build()
                    sendAapProtoMessage(output, channelId, Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, ack)
                }
            }
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

            val isControl = (type in 1..26)
            val controlFlag = if (channel != CHANNEL_CONTROL && isControl) 0x04 else 0x00
            val baseFlags = 0x03 or controlFlag // First & Last frame | control bit

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
            Log.w(TAG, "Failed to send AAP message (channel=$channel, type=$type)", e)
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping Android Auto engine...")
        try {
            workerThread?.interrupt()
            workerThread = null
            sslContext?.release()
            sslContext = null
            isSslHandshakeComplete = false
            currentTransport?.close()
            currentTransport = null
            currentSink = null
            sessionIds.clear()
            videoReassemblyBuffer.reset()
            videoConfigSent = false
            lastVideoSps = null
            videoAssembling = false
            videoFramesReceived = 0L
            videoBytesReceived = 0L
            lastVideoStatsMs = 0L
        } catch (e: Exception) {
            Log.w(TAG, "Error while stopping engine: ${e.message}")
        }
    }

    override fun sendTouchEvent(action: Int, x: Float, y: Float) {
        val transport = currentTransport ?: return
        if (!running.get() || !isSslHandshakeComplete) return

        inputExecutor.execute {
            try {
                val pointerAction = pointerActionFor(action) ?: return@execute

                val pointer = Input.TouchEvent.Pointer.newBuilder()
                    .setPointerId(0)
                    .setX(x.toInt())
                    .setY(y.toInt())
                    .build()

                val touchEvent = Input.TouchEvent.newBuilder()
                    .setAction(pointerAction)
                    .setActionIndex(0)
                    .addPointerData(pointer)
                    .build()

                // AAP input timestamps are uptime nanoseconds, not wall-clock time.
                val timestampNs = SystemClock.elapsedRealtime() * 1_000_000L
                val inputReport = Input.InputReport.newBuilder()
                    .setTimestamp(timestampNs)
                    .setTouchEvent(touchEvent)
                    .build()

                if (pointerAction == Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN ||
                    pointerAction == Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
                ) {
                    Log.i(TAG, "AAP touch $pointerAction at (${x.toInt()}, ${y.toInt()}) ts=$timestampNs")
                }

                sendAapProtoMessage(transport.outputStream, CHANNEL_INPUT, Input.MsgType.EVENT_VALUE, inputReport)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send AAP touch", e)
            }
        }
    }

    private fun pointerActionFor(action: Int): Input.TouchEvent.PointerAction? = when (action) {
        MotionEvent.ACTION_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
        MotionEvent.ACTION_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
        MotionEvent.ACTION_MOVE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
        MotionEvent.ACTION_CANCEL -> Input.TouchEvent.PointerAction.TOUCH_ACTION_CANCEL
        MotionEvent.ACTION_OUTSIDE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_OUTSIDE
        MotionEvent.ACTION_POINTER_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
        MotionEvent.ACTION_POINTER_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP
        else -> null
    }

    override fun sendKeyEvent(keyCode: Int, isDown: Boolean) {
        val transport = currentTransport ?: return
        if (!running.get() || !isSslHandshakeComplete) return

        inputExecutor.execute {
            try {
                val key = Input.Key.newBuilder()
                    .setKeycode(keyCode)
                    .setDown(isDown)
                    .setLongpress(false)
                    .setMetastate(0)
                    .build()

                val keyEvent = Input.KeyEvent.newBuilder()
                    .addKeys(key)
                    .build()

                val inputReport = Input.InputReport.newBuilder()
                    .setTimestamp(SystemClock.elapsedRealtime() * 1_000_000L)
                    .setKeyEvent(keyEvent)
                    .build()

                sendAapProtoMessage(transport.outputStream, CHANNEL_INPUT, Input.MsgType.EVENT_VALUE, inputReport)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send AAP key", e)
            }
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
