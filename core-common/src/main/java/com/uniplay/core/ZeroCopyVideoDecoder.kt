package com.uniplay.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * High-performance, zero-copy hardware video decoder.
 * Feeds NAL units directly into MediaCodec and outputs directly to a Surface.
 * Avoids any intermediate JVM ByteBuffers or bitmap allocations to stay < 10MB RAM.
 */
class ZeroCopyVideoDecoder(
    private val surface: Surface,
    private val onCodecError: (Exception) -> Unit = {}
) {
    companion object {
        private const val TAG = "ZeroCopyDecoder"
        private const val MIME_TYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 5000L
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
    }

    private var codec: MediaCodec? = null
    @Volatile
    var isConfigured: Boolean = false
        private set

    private val bufferInfo = MediaCodec.BufferInfo()
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null

    @Synchronized
    fun configure(sps: ByteArray, pps: ByteArray, width: Int = 1280, height: Int = 720) {
        try {
            stop()
            val format = MediaFormat.createVideoFormat(MIME_TYPE_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                // Low latency flags for automotive head units
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            val newCodec = MediaCodec.createDecoderByType(MIME_TYPE_AVC)
            newCodec.configure(format, surface, null, 0)
            newCodec.start()

            codec = newCodec
            isConfigured = true
            Log.i(TAG, "Hardware MediaCodec successfully configured ($width x $height)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure MediaCodec", e)
            onCodecError(e)
        }
    }

    /**
     * Feeds NAL unit into MediaCodec.
     * Automatically inspects unconfigured streams for inline SPS/PPS to eliminate black screen.
     */
    @Synchronized
    fun feedNalUnit(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        if (!isConfigured) {
            // Attempt auto-recovery: search for inline SPS and PPS
            extractInlineSpsPps(data, offset, length)
            val sps = pendingSps
            val pps = pendingPps
            if (sps != null && pps != null) {
                Log.i(TAG, "Auto-detected inline SPS/PPS. Initializing decoder on the fly!")
                configure(sps, pps)
                pendingSps = null
                pendingPps = null
            } else {
                return // Still waiting for SPS/PPS
            }
        }

        val decoder = codec ?: return

        try {
            val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(data, offset, length)
                    decoder.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        length,
                        timestampUs,
                        0
                    )
                }
            }

            // Drain output frames directly to surface
            drainOutput(decoder)
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding frame to MediaCodec: ${e.message}")
        }
    }

    private fun extractInlineSpsPps(data: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length - 4
        while (i < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val nalType = (data[i + 4].toInt() and 0x1F)
                if (nalType == NAL_TYPE_SPS && pendingSps == null) {
                    val nextStart = findNextStartCode(data, i + 4, offset + length)
                    val spsLen = nextStart - i
                    val sps = ByteArray(spsLen)
                    System.arraycopy(data, i, sps, 0, spsLen)
                    pendingSps = sps
                    Log.d(TAG, "Discovered inline SPS (${spsLen}b)")
                } else if (nalType == NAL_TYPE_PPS && pendingPps == null) {
                    val nextStart = findNextStartCode(data, i + 4, offset + length)
                    val ppsLen = nextStart - i
                    val pps = ByteArray(ppsLen)
                    System.arraycopy(data, i, pps, 0, ppsLen)
                    pendingPps = pps
                    Log.d(TAG, "Discovered inline PPS (${ppsLen}b)")
                }
            }
            i++
        }
    }

    private fun findNextStartCode(data: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i < end - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() || (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))) {
                return i
            }
            i++
        }
        return end
    }

    private fun drainOutput(decoder: MediaCodec) {
        try {
            var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            while (outputBufferIndex >= 0) {
                // Render directly to surface (true = render immediately)
                decoder.releaseOutputBuffer(outputBufferIndex, true)
                outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Output buffer drain notice: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Exception during decoder release", e)
        } finally {
            codec = null
            isConfigured = false
        }
    }
}
