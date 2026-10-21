package com.uniplay.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Lean AudioTrack wrapper for low-RAM automotive playback.
 * Uses small buffers to minimize latency (< 40ms) and keep memory < 2MB.
 */
class LeanAudioTrack(
    val channel: AudioChannel,
    val sampleRate: Int = 48000,
    val channelCount: Int = 2
) {
    companion object {
        private const val TAG = "LeanAudioTrack"

        /** About a second of 10 ms packets; beyond that the oldest audio is dropped. */
        private const val QUEUE_CAPACITY = 100
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    // AudioTrack.write blocks once its buffer is full. Called straight from the
    // transport thread that stalled video, touch and acks behind every audio packet,
    // so writes are queued here and played by a thread of their own.
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var writerThread: Thread? = null

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
            .setContentType(
                if (channel == AudioChannel.MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC
                else AudioAttributes.CONTENT_TYPE_SPEECH
            )
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()
        audioTrack = track
        track.play()
        isPlaying = true

        queue.clear()
        writerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            writeLoop(track)
        }, "UniplayAudio-$channel").apply { start() }
        Log.i(TAG, "AudioTrack started for $channel ($sampleRate Hz, buffer: ${bufferSize}b)")
    }

    /** Queues [pcmData] for playback and returns at once. The array must not be reused. */
    fun write(pcmData: ByteArray) {
        if (!isPlaying) start()
        if (!queue.offer(pcmData)) {
            // Playback fell behind; keep the audio current rather than late.
            queue.poll()
            queue.offer(pcmData)
        }
    }

    private fun writeLoop(track: AudioTrack) {
        try {
            while (true) {
                val pcm = queue.poll(200, TimeUnit.MILLISECONDS)
                if (pcm == null) {
                    if (audioTrack !== track) return
                    continue
                }
                if (track.write(pcm, 0, pcm.size) < 0) return
            }
        } catch (_: Exception) {
            // Track was stopped or released.
        }
    }

    @Synchronized
    fun stop() {
        val track = audioTrack
        audioTrack = null
        writerThread = null
        queue.clear()
        try {
            track?.stop()
            track?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack", e)
        } finally {
            isPlaying = false
        }
    }
}
