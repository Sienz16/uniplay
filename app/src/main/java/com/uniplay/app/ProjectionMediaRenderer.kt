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
            }
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

    override fun onAudioData(channel: AudioChannel, pcmData: ByteArray, sampleRate: Int, channels: Int) {
        when (channel) {
            AudioChannel.MEDIA -> {
                if (audioTrackMedia == null) {
                    audioTrackMedia = LeanAudioTrack(AudioChannel.MEDIA, sampleRate, channels)
                }
                audioTrackMedia?.write(pcmData)
            }
            AudioChannel.NAVIGATION, AudioChannel.SPEECH_SIRI_ASSISTANT -> {
                if (audioTrackNav == null) {
                    audioTrackNav = LeanAudioTrack(channel, sampleRate, channels)
                }
                audioTrackNav?.write(pcmData)
            }
        }
    }

    override fun onSessionTerminated(reason: String) {
        onSessionEnded(reason)
    }

    fun stop() {
        videoDecoder?.stop()
        audioTrackMedia?.stop()
        audioTrackNav?.stop()
    }
}
