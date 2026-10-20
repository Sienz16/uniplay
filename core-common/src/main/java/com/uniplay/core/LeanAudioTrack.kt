package com.uniplay.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Lean AudioTrack wrapper for low-RAM automotive playback.
 * Uses small buffers to minimize latency (< 40ms) and keep memory < 2MB.
 */
class LeanAudioTrack(
    private val channel: AudioChannel,
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 2
) {
    companion object {
        private const val TAG = "LeanAudioTrack"
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    @Synchronized
    fun start() {
        if (isPlaying) return

        val channelConfig = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        val usage = when (channel) {
            AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            AudioChannel.SPEECH_SIRI_ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
            AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isPlaying = true
        Log.i(TAG, "AudioTrack started for $channel ($sampleRate Hz, buffer: ${bufferSize}b)")
    }

    @Synchronized
    fun write(pcmData: ByteArray, offset: Int = 0, length: Int = pcmData.size) {
        if (!isPlaying || audioTrack == null) {
            start()
        }
        audioTrack?.write(pcmData, offset, length)
    }

    @Synchronized
    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
            isPlaying = false
        }
    }
}
