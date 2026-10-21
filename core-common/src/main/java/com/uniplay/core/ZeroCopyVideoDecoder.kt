package com.uniplay.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
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
    private val onFirstFrameRendered: (Int, Int) -> Unit = { _, _ -> },
    /** Called when frames were lost and the picture needs a fresh keyframe to recover. */
    private val onNeedKeyframe: () -> Unit = {}
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
        private const val OUTPUT_WAIT_US = 10_000L
        private const val KEYFRAME_REQUEST_GAP_MS = 1000L

        /** Decoded frames ready at once before the stale ones are skipped. */
        private const val CATCHUP_DEPTH = 3
        private const val MAX_READY = 8
    }

    private class Frame(val data: ByteArray, val timestampUs: Long)

    @Volatile
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null

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
    @Volatile
    private var hasRenderedFrame = false
    /** True after frames were lost: inter frames are useless until the next keyframe. */
    @Volatile
    private var awaitingKeyframe = false
    private var lastKeyframeRequestMs = 0L
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

        val keyframe = H264ParameterSets.isKeyframe(data, offset, length)
        if (awaitingKeyframe) {
            // Frames that reference a lost one only smear the picture; wait for a clean start.
            if (!keyframe) {
                droppedFrames++
                return
            }
            awaitingKeyframe = false
        }

        val copy = data.copyOfRange(offset, offset + length)
        if (!frameQueue.offer(Frame(copy, timestampUs))) {
            // The decoder is behind. Dropping any single frame breaks every frame after it,
            // so discard the backlog and resume at the next keyframe instead.
            frameQueue.clear()
            droppedFrames++
            if (keyframe) frameQueue.offer(Frame(copy, timestampUs)) else markFramesLost()
        }
    }

    private fun markFramesLost() {
        awaitingKeyframe = true
        frameQueue.clear()
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestMs < KEYFRAME_REQUEST_GAP_MS) return
        lastKeyframeRequestMs = now
        Log.w(TAG, "Frames lost; asking the phone for a keyframe")
        onNeedKeyframe()
    }

    @Synchronized
    private fun ensureFeedThread() {
        if (running && feedThread != null) return
        running = true
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            feedLoop()
        }, "UniplayVideoDecoder")
        feedThread = thread
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
            startOutputThread(newCodec)
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
                markFramesLost()
                return
            }
            val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
            if (inputBuffer == null) {
                droppedFrames++
                markFramesLost()
                return
            }
            inputBuffer.clear()
            inputBuffer.put(frame.data, 0, frame.data.size)
            decoder.queueInputBuffer(inputBufferIndex, 0, frame.data.size, frame.timestampUs, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding frame to MediaCodec: ${e.message}")
        }
    }

    /**
     * Output has its own thread. Draining only after each input put a decoded frame
     * behind the next one to arrive (a full frame interval of added latency, and
     * uneven pacing); here a frame is shown the moment the codec has it.
     */
    private fun startOutputThread(owner: MediaCodec) {
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            outputLoop(owner)
        }, "UniplayVideoOutput")
        outputThread = thread
        thread.start()
    }

    private fun outputLoop(owner: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val ready = IntArray(MAX_READY)
        try {
            while (running && codec === owner) {
                val first = owner.dequeueOutputBuffer(info, OUTPUT_WAIT_US)
                if (first == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    logOutputFormat(owner)
                    continue
                }
                if (first < 0) continue

                // Collect whatever else is already decoded. A burst after a quiet link
                // would otherwise be replayed frame by frame in slow motion. Decoded
                // frames are not references for later input, so skipping is free.
                ready[0] = first
                var count = 1
                while (count < MAX_READY) {
                    val next = owner.dequeueOutputBuffer(info, 0L)
                    if (next >= 0) ready[count++] = next else break
                }
                val skipFrom = if (count >= CATCHUP_DEPTH) count - 1 else count
                for (i in 0 until count) {
                    renderOutputBuffer(owner, ready[i], render = i >= skipFrom || count < CATCHUP_DEPTH)
                }
            }
        } catch (e: Exception) {
            // Thrown when the codec is stopped under this thread; that is the normal exit.
            if (running && codec === owner) Log.w(TAG, "Output loop ended: ${e.message}")
        }
    }

    private fun renderOutputBuffer(decoder: MediaCodec, bufferIndex: Int, render: Boolean) {
        decoder.releaseOutputBuffer(bufferIndex, render)
        if (!render) {
            droppedFrames++
            return
        }
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
        val old = codec
        // Clearing the field first tells the output thread to leave before the codec
        // is stopped underneath it.
        codec = null
        val out = outputThread
        outputThread = null
        if (out != null && out !== Thread.currentThread()) {
            try {
                out.join(200)
            } catch (_: InterruptedException) {
            }
        }
        try {
            old?.stop()
        } catch (_: Exception) {
        }
        try {
            old?.release()
        } catch (_: Exception) {
        }
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
