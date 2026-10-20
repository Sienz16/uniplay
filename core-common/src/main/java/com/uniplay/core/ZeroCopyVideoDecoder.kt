package com.uniplay.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware video decoder that renders straight into a [Surface].
 *
 * Two rules this class exists to enforce, both learned the hard way:
 *
 *  1. **The codec is only ever created with real `csd-0` / `csd-1`.** An AVC decoder
 *     configured without the parameter sets starts happily, accepts every buffer and
 *     never emits a frame. That is the "connected to Android Auto, black screen"
 *     failure, so the codec is not created at all until an SPS and a PPS have been
 *     seen in the stream.
 *  2. **The transport thread is never blocked.** Feeding a decoder means waiting for
 *     a free input buffer, and the transport thread carries video, audio, control and
 *     touch. A decoder thread with a small queue keeps the link moving; when the
 *     decoder falls behind, the oldest frame is dropped rather than the newest, so
 *     latency does not grow without bound.
 */
class ZeroCopyVideoDecoder(
    private val surface: Surface,
    private val onCodecError: (Exception) -> Unit = {},
    private val onFirstFrameRendered: (Int, Int) -> Unit = { _, _ -> }
) {

    companion object {
        private const val TAG = "ZeroCopyDecoder"
        private const val MIME_TYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 10_000L
        private const val QUEUE_CAPACITY = 8
        private const val POLL_MS = 50L
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val MAX_INPUT_SIZE = 2 * 1024 * 1024
    }

    private class Frame(val data: ByteArray, val timestampUs: Long)

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var running = false
    private var feedThread: Thread? = null
    private val frameQueue = ArrayBlockingQueue<Frame>(QUEUE_CAPACITY)

    @Volatile
    private var pendingSps: ByteArray? = null
    @Volatile
    private var pendingPps: ByteArray? = null
    @Volatile
    private var streamWidth = DEFAULT_WIDTH
    @Volatile
    private var streamHeight = DEFAULT_HEIGHT
    private var configuredSps: ByteArray? = null
    private var hasRenderedFrame = false
    private var droppedFrames = 0L
    private var renderedFrames = 0L

    /** True once the codec exists and carries the parameter sets. */
    @Volatile
    var isConfigured: Boolean = false
        private set

    /**
     * Hands the parameter sets to the decoder.
     *
     * Safe to call from any thread: the codec is (re)built on the decoder thread, not
     * here, so a UI-thread call cannot stall the transport.
     */
    @Synchronized
    fun configure(sps: ByteArray, pps: ByteArray, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT) {
        pendingSps = sps
        pendingPps = pps
        if (width > 0 && height > 0) {
            streamWidth = width
            streamHeight = height
        }
        ensureFeedThread()
        Log.i(TAG, "Parameter sets received (SPS=${sps.size}b PPS=${pps.size}b) for ${streamWidth}x$streamHeight")
    }

    /**
     * Queues one access unit for decoding.
     *
     * Returns immediately: the buffer is copied (the caller reuses its own) and handed
     * to the decoder thread.
     */
    fun feedNalUnit(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        if (length <= 0) return
        ensureFeedThread()

        // Until the codec exists there is nothing to decode into, so this is the one
        // moment the parameter sets can still be picked up out of the stream itself.
        if (!isConfigured) {
            val sets = H264ParameterSets.find(data, offset, length)
            if (sets != null) {
                val w = if (sets.width > 0) sets.width else streamWidth
                val h = if (sets.height > 0) sets.height else streamHeight
                Log.i(TAG, "Inline SPS/PPS found (${sets.sps.size}b/${sets.pps.size}b) size=${w}x$h")
                configure(sets.sps, sets.pps, w, h)
            }
        }

        val copy = data.copyOfRange(offset, offset + length)
        if (!frameQueue.offer(Frame(copy, timestampUs))) {
            // The decoder is behind. Drop the oldest frame so the picture stays live.
            frameQueue.poll()
            frameQueue.offer(Frame(copy, timestampUs))
            droppedFrames++
        }
    }

    @Synchronized
    private fun ensureFeedThread() {
        if (running && feedThread != null) return
        running = true
        val thread = Thread({ feedLoop() }, "UniplayVideoDecoder")
        feedThread = thread
        thread.priority = Thread.MAX_PRIORITY
        thread.start()
        Log.i(TAG, "Decoder thread started")
    }

    private fun feedLoop() {
        try {
            while (running) {
                buildCodecIfNeeded()
                val frame = try {
                    frameQueue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                feedFrame(frame)
            }
        } finally {
            // Only tear the codec down if this is still the live decoder thread: a
            // replacement thread may already have been started after a stop().
            if (feedThread === Thread.currentThread()) releaseCodec()
            Log.i(TAG, "Decoder thread stopped (rendered=$renderedFrames dropped=$droppedFrames)")
        }
    }

    /**
     * Creates the codec once the parameter sets are known.
     *
     * Rebuilt when the SPS changes, because a phone that switches resolution sends a
     * new SPS and the old codec would keep decoding for the old size.
     */
    private fun buildCodecIfNeeded() {
        val sps = pendingSps ?: return
        val pps = pendingPps ?: return
        val current = configuredSps
        if (current != null && current.contentEquals(sps) && codec != null) return

        releaseCodec()
        try {
            if (!surface.isValid) {
                Log.w(TAG, "Surface is not valid; skipping codec creation")
                return
            }

            val format = MediaFormat.createVideoFormat(MIME_TYPE_AVC, streamWidth, streamHeight).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            }
            // Deliberately no KEY_LOW_LATENCY / KEY_PRIORITY: the Spreadtrum
            // OMX.sprd.h264.decoder rejects low-latency with ERROR_UNSUPPORTED
            // (-1010), which aborts configure() and leaves the screen black.

            val newCodec = MediaCodec.createDecoderByType(MIME_TYPE_AVC)
            newCodec.configure(format, surface, null, 0)
            try {
                newCodec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            } catch (_: Exception) {
                // Not every component implements it.
            }
            newCodec.start()

            codec = newCodec
            configuredSps = sps
            isConfigured = true
            hasRenderedFrame = false
            Log.i(TAG, "MediaCodec configured with SPS/PPS for ${streamWidth}x$streamHeight")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure MediaCodec with SPS/PPS", e)
            isConfigured = false
            onCodecError(e)
        }
    }

    private fun feedFrame(frame: Frame) {
        val decoder = codec ?: return
        try {
            val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex < 0) {
                droppedFrames++
                return
            }
            val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
            if (inputBuffer == null) {
                droppedFrames++
                return
            }
            inputBuffer.clear()
            inputBuffer.put(frame.data, 0, frame.data.size)
            decoder.queueInputBuffer(inputBufferIndex, 0, frame.data.size, frame.timestampUs, 0)
            drainOutput(decoder)
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding frame to MediaCodec: ${e.message}")
        }
    }

    private fun drainOutput(decoder: MediaCodec) {
        try {
            while (true) {
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outputBufferIndex >= 0 -> renderOutputBuffer(decoder, outputBufferIndex)
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(decoder)
                    else -> return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Output buffer drain notice: ${e.message}")
        }
    }

    private fun renderOutputBuffer(decoder: MediaCodec, bufferIndex: Int) {
        decoder.releaseOutputBuffer(bufferIndex, true)
        renderedFrames++
        if (hasRenderedFrame) return

        hasRenderedFrame = true
        Log.i(TAG, "First frame rendered to surface (${streamWidth}x$streamHeight)")
        onFirstFrameRendered(streamWidth, streamHeight)
    }

    private fun logOutputFormat(decoder: MediaCodec) {
        val format = decoder.outputFormat
        Log.i(
            TAG,
            "Output format changed: ${format.getInteger(MediaFormat.KEY_WIDTH)}x" +
                format.getInteger(MediaFormat.KEY_HEIGHT)
        )
    }

    @Synchronized
    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        configuredSps = null
        isConfigured = false
    }

    @Synchronized
    fun stop() {
        running = false
        frameQueue.clear()
        try {
            feedThread?.interrupt()
        } catch (_: Exception) {
        }
        feedThread = null
        releaseCodec()
    }
}
