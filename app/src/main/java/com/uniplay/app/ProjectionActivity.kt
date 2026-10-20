package com.uniplay.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.TextView
import com.uniplay.androidauto.AndroidAutoWirelessManager
import com.uniplay.core.*

/**
 * Universal projection activity.
 * Hosts a single SurfaceView for hardware-accelerated rendering,
 * displays an interactive first-boot wireless connection guide,
 * and manages the background high-speed wireless projection server.
 */
class ProjectionActivity : Activity(), SurfaceHolder.Callback, MediaSink {

    companion object {
        private const val TAG = "ProjectionActivity"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var guideOverlay: View
    private lateinit var badgeWifiIp: TextView
    private lateinit var badgeBluetooth: TextView
    private lateinit var statusText: TextView

    private var videoDecoder: ZeroCopyVideoDecoder? = null
    private var audioTrackMedia: LeanAudioTrack? = null
    private var audioTrackNav: LeanAudioTrack? = null

    private lateinit var deviceRouter: DeviceRouter
    private lateinit var memoryGuard: MemoryGuard
    private lateinit var bluetoothBeacon: BluetoothBeacon
    private lateinit var wirelessServer: WirelessServer
    private lateinit var aaWirelessManager: AndroidAutoWirelessManager

    private var hasStreamStarted = false

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
        guideOverlay = findViewById(R.id.guide_overlay)
        badgeWifiIp = findViewById(R.id.badge_wifi_ip)
        badgeBluetooth = findViewById(R.id.badge_bluetooth)
        statusText = findViewById(R.id.text_status)

        surfaceView.holder.addCallback(this)

        deviceRouter = DeviceRouter(this)
        memoryGuard = MemoryGuard(this)
        bluetoothBeacon = BluetoothBeacon(this)

        // Populate live network info into guide badges
        val localIp = NetworkUtils.getLocalIpAddress(this)
        val btName = bluetoothBeacon.getDeviceName()
        badgeWifiIp.text = "Wi-Fi IP: $localIp:${WirelessServer.PORT_MAIN}"
        badgeBluetooth.text = "Bluetooth: $btName"

        bluetoothBeacon.startAdvertising()

        // Start official Android Auto Wireless RFCOMM Handshake Server
        aaWirelessManager = AndroidAutoWirelessManager(this) { phoneName ->
            runOnUiThread {
                statusText.text = "Bluetooth handshake with $phoneName! Handing off to Wi-Fi..."
                statusText.setTextColor(Color.parseColor("#50E3C2"))
            }
        }
        aaWirelessManager.start(
            ssid = btName,
            psk = "12345678",
            localIp = localIp,
            port = WirelessServer.PORT_MAIN
        )

        // Initialize and start background high-speed wireless projection server
        wirelessServer = WirelessServer(deviceRouter, this) { status, isConnected ->
            runOnUiThread {
                statusText.text = status
                if (isConnected) {
                    statusText.setTextColor(Color.parseColor("#4E9FFF"))
                } else {
                    statusText.setTextColor(Color.parseColor("#00FF9D"))
                }
            }
        }
        wirelessServer.start()

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
            hideGuideOverlay()
            videoDecoder?.configure(sps, pps, width, height)
        }
    }

    override fun onVideoNal(nalUnit: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        if (!hasStreamStarted) {
            runOnUiThread { hideGuideOverlay() }
        }
        videoDecoder?.feedNalUnit(nalUnit, offset, length, timestampUs)
    }

    private fun hideGuideOverlay() {
        if (guideOverlay.visibility != View.GONE) {
            guideOverlay.visibility = View.GONE
            hasStreamStarted = true
            Log.i(TAG, "First video frame rendered. Connection guide overlay hidden.")
        }
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
            hasStreamStarted = false
            guideOverlay.visibility = View.VISIBLE
            statusText.text = "Disconnected: $reason\nWaiting for phone reconnect..."
            statusText.setTextColor(Color.parseColor("#FF6B6B"))
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
        aaWirelessManager.stop()
        wirelessServer.stop()
        bluetoothBeacon.stopAdvertising()
        deviceRouter.stopActiveSession()
        videoDecoder?.stop()
        audioTrackMedia?.stop()
        audioTrackNav?.stop()
    }
}
