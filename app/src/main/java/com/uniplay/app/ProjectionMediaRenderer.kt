package com.uniplay.app

import android.util.Log
import android.view.SurfaceHolder
import com.uniplay.core.*

class ProjectionMediaRenderer(
    private val onFirstFrameRendered: (width: Int, height: Int) -> Unit,
    private val onSessionEnded: (reason: String) -> Unit
) : SurfaceHolder.Callback, MediaSink {

    companion object {
        private const val TAG = "ProjectionMediaRenderer"
    }

    /** Set by the session controller; asks the active engine for a video keyframe. */
    @Volatile
    var keyframeRequester: (() -> Unit)? = null

    @Volatile
    private var videoDecoder: ZeroCopyVideoDecoder? = null
    private var audioTrackMedia: LeanAudioTrack? = null
    private var audioTrackNav: LeanAudioTrack? = null

    // SPS/PPS may arrive before the Surface is ready; keep them for the decoder.
    @Volatile
    private var pendingSps: ByteArray? = null
    @Volatile
    private var pendingPps: ByteArray? = null
    @Volatile
    private var pendingWidth = 1280
    @Volatile
    private var pendingHeight = 720

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created. Initializing ZeroCopyVideoDecoder...")
        val decoder = ZeroCopyVideoDecoder(
            holder.surface,
            onCodecError = { error -> Log.e(TAG, "Decoder error encountered", error) },
            onFirstFrameRendered = { width, height ->
                onFirstFrameRendered(width, height)
            },
            onNeedKeyframe = { keyframeRequester?.invoke() }
        )
        videoDecoder = decoder

        // Parameter sets that arrived before the surface existed are applied now.
        val sps = pendingSps
        val pps = pendingPps
        if (sps != null && pps != null) {
            Log.i(TAG, "Applying SPS/PPS cached before surface was ready")
            decoder.configure(sps, pps, pendingWidth, pendingHeight)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "Surface changed: $width x $height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed. Stopping decoder...")
        videoDecoder?.stop()
        videoDecoder = null
    }

    override fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        pendingSps = sps
        pendingPps = pps
        pendingWidth = width
        pendingHeight = height
        Log.i(TAG, "Video config received (${width}x$height). Handing parameter sets to the decoder.")
        // configure() only stores the parameter sets; the codec is rebuilt on the
        // decoder thread, so this is safe to call from the UI thread.
        videoDecoder?.configure(sps, pps, width, height)
    }

    override fun onVideoNal(nalUnit: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        val decoder = videoDecoder
        if (decoder == null) {
            Log.w(TAG, "Video NAL arrived before surface was ready (${length}b), dropping")
            return
        }
        decoder.feedNalUnit(nalUnit, offset, length, timestampUs)
    }

    @Synchronized
    override fun onAudioData(channel: AudioChannel, pcmData: ByteArray, sampleRate: Int, channels: Int) {
        when (channel) {
            AudioChannel.MEDIA -> {
                val track = audioTrackMedia.matching(channel, sampleRate, channels)
                audioTrackMedia = track
                track.write(pcmData)
            }
            AudioChannel.NAVIGATION, AudioChannel.SPEECH_SIRI_ASSISTANT -> {
                val track = audioTrackNav.matching(channel, sampleRate, channels)
                audioTrackNav = track
                track.write(pcmData)
            }
        }
    }

    /** The existing track if it still fits the stream, otherwise a fresh one. */
    private fun LeanAudioTrack?.matching(channel: AudioChannel, sampleRate: Int, channels: Int): LeanAudioTrack {
        if (this != null && this.channel == channel && this.sampleRate == sampleRate && this.channelCount == channels) {
            return this
        }
        this?.stop()
        return LeanAudioTrack(channel, sampleRate, channels)
    }

    override fun onSessionTerminated(reason: String) {
        releaseAudio()
        onSessionEnded(reason)
    }

    @Synchronized
    private fun releaseAudio() {
        audioTrackMedia?.stop()
        audioTrackNav?.stop()
        audioTrackMedia = null
        audioTrackNav = null
    }

    fun stop() {
        videoDecoder?.stop()
        releaseAudio()
    }
}
