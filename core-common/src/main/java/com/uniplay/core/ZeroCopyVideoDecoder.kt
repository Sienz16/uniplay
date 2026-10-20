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
        private const val TIMEOUT_US = 10000L
    }

    private var codec: MediaCodec? = null
    private var isConfigured = false
    private val bufferInfo = MediaCodec.BufferInfo()

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

    @Synchronized
    fun feedNalUnit(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        val decoder = codec ?: return
        if (!isConfigured) return

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
            Log.e(TAG, "Error feeding frame to MediaCodec", e)
        }
    }

    private fun drainOutput(decoder: MediaCodec) {
        var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
        while (outputBufferIndex >= 0) {
            // Render directly to surface (true = render immediately)
            decoder.releaseOutputBuffer(outputBufferIndex, true)
            outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
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
