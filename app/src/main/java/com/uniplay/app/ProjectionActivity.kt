package com.uniplay.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import com.uniplay.core.*

/**
 * Universal projection activity.
 * Hosts a single SurfaceView for hardware-accelerated rendering and binds touch/audio pipelines.
 */
class ProjectionActivity : Activity(), SurfaceHolder.Callback, MediaSink {

    companion object {
        private const val TAG = "ProjectionActivity"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private var videoDecoder: ZeroCopyVideoDecoder? = null
    private var audioTrackMedia: LeanAudioTrack? = null
    private var audioTrackNav: LeanAudioTrack? = null

    private lateinit var deviceRouter: DeviceRouter
    private lateinit var memoryGuard: MemoryGuard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projection)

        // Make true fullscreen (immersive mode)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        surfaceView = findViewById(R.id.projection_surface)
        statusText = findViewById(R.id.text_status)

        surfaceView.holder.addCallback(this)

        deviceRouter = DeviceRouter(this)
        memoryGuard = MemoryGuard(this)

        Log.i(TAG, "ProjectionActivity initialized. ${memoryGuard.dumpMemoryStats()}")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created. Initializing ZeroCopyVideoDecoder...")
        videoDecoder = ZeroCopyVideoDecoder(holder.surface) { error ->
            Log.e(TAG, "Decoder error encountered", error)
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

    // MediaSink implementation
    override fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        runOnUiThread {
            statusText.visibility = View.GONE
            videoDecoder?.configure(sps, pps, width, height)
        }
    }

    override fun onVideoNal(nalUnit: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        videoDecoder?.feedNalUnit(nalUnit, offset, length, timestampUs)
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
        runOnUiThread {
            statusText.visibility = View.VISIBLE
            statusText.text = "Disconnected: $reason\nWaiting for phone..."
        }
    }

    // Touch & Key Dispatching to Active Engine
    override fun onTouchEvent(event: MotionEvent): Boolean {
        deviceRouter.getActiveEngine()?.sendTouchEvent(event.action, event.x, event.y)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        deviceRouter.getActiveEngine()?.sendKeyEvent(keyCode, true)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        deviceRouter.getActiveEngine()?.sendKeyEvent(keyCode, false)
        return super.onKeyUp(keyCode, event)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "System low memory signal received!")
        memoryGuard.checkAndCompactMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceRouter.stopActiveSession()
        videoDecoder?.stop()
        audioTrackMedia?.stop()
        audioTrackNav?.stop()
    }
}
