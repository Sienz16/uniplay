package com.uniplay.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import com.uniplay.androidauto.AndroidAutoWirelessManager
import com.uniplay.core.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Universal projection activity.
 * Hosts a single SurfaceView for hardware-accelerated rendering,
 * displays an interactive first-boot wireless connection guide,
 * and manages the background high-speed wireless projection server
 * as well as direct Hotspot auto-connecting.
 */
class ProjectionActivity : Activity(), SurfaceHolder.Callback, MediaSink {

    companion object {
        private const val TAG = "ProjectionActivity"
        private const val PERMISSION_REQ_CODE = 101
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var guideOverlay: View
    private lateinit var badgeWifiIp: TextView
    private lateinit var badgeBluetooth: TextView
    private lateinit var statusText: TextView
    private lateinit var btnConnectPhone: Button

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

    private lateinit var deviceRouter: DeviceRouter
    private lateinit var memoryGuard: MemoryGuard
    private lateinit var bluetoothBeacon: BluetoothBeacon
    private lateinit var wirelessServer: WirelessServer
    private lateinit var aaWirelessManager: AndroidAutoWirelessManager

    private var hasStreamStarted = false
    private val isConnectingDirect = AtomicBoolean(false)
    private val isWirelessInitialized = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projection)

        // Make true fullscreen (immersive mode)
        @Suppress("DEPRECATION")
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
        btnConnectPhone = findViewById(R.id.btn_connect_phone)

        surfaceView.holder.addCallback(this)

        deviceRouter = DeviceRouter(this)
        memoryGuard = MemoryGuard(this)
        bluetoothBeacon = BluetoothBeacon(this)

        // Populate live network info into guide badges
        val localIp = NetworkUtils.getLocalIpAddress(this)
        val btName = bluetoothBeacon.getDeviceName()
        badgeWifiIp.text = "Wi-Fi IP: $localIp:${WirelessServer.PORT_MAIN}"
        badgeBluetooth.text = "Bluetooth: $btName"

        checkAndRequestPermissions()

        initWirelessServices(localIp, btName)

        // One-tap direct Hotspot connect button
        val gatewayIp = NetworkUtils.getGatewayIp(this)
        btnConnectPhone.text = "⚡ Connect Directly to Phone ($gatewayIp)"
        btnConnectPhone.setOnClickListener {
            connectDirectToPhone(manual = true)
        }

        Log.i(TAG, "ProjectionActivity initialized. ${memoryGuard.dumpMemoryStats()}")
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (permissions.isNotEmpty()) {
                requestPermissions(permissions.toTypedArray(), PERMISSION_REQ_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE) {
            val localIp = NetworkUtils.getLocalIpAddress(this)
            val btName = bluetoothBeacon.getDeviceName()
            initWirelessServices(localIp, btName)
        }
    }

    private fun initWirelessServices(localIp: String, btName: String) {
        if (isWirelessInitialized.getAndSet(true)) return

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
    }

    private fun connectDirectToPhone(manual: Boolean) {
        if (hasStreamStarted || isConnectingDirect.getAndSet(true)) return

        val gatewayIp = NetworkUtils.getGatewayIp(this)
        if (manual) {
            statusText.text = "Attempting direct connection to phone at $gatewayIp..."
            statusText.setTextColor(Color.parseColor("#4E9FFF"))
        }

        Thread({
            var connectedSocket: Socket? = null
            // Standard Android Auto Headunit Server port is 5277, fallback 5000, 5288
            val portsToTry = intArrayOf(5277, 5000, 5288)

            for (port in portsToTry) {
                try {
                    val socket = Socket()
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(gatewayIp, port), 2500)
                    connectedSocket = socket
                    Log.i(TAG, "Successfully connected to Phone at $gatewayIp:$port!")
                    break
                } catch (_: Exception) {}
            }

            if (connectedSocket != null) {
                runOnUiThread {
                    statusText.text = "Connected to Phone! Initializing Android Auto stream..."
                    statusText.setTextColor(Color.parseColor("#00FF9D"))
                }
                val transport = SocketTransportChannel(connectedSocket, TransportType.WIRELESS_WIFI)
                deviceRouter.onManualSelect(ProjectionType.ANDROID_AUTO, transport, this@ProjectionActivity)
            } else {
                isConnectingDirect.set(false)
                if (manual) {
                    runOnUiThread {
                        statusText.text = "Could not find Phone on $gatewayIp.\nEnsure 'Start head unit server' is ON in Android Auto settings."
                        statusText.setTextColor(Color.parseColor("#FF9F4E"))
                    }
                }
            }
        }, "DirectPhoneConnector").apply {
            isDaemon = true
            start()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created. Initializing ZeroCopyVideoDecoder...")
        val decoder = ZeroCopyVideoDecoder(
            holder.surface,
            onCodecError = { error -> Log.e(TAG, "Decoder error encountered", error) },
            onFirstFrameRendered = { width, height ->
                Log.i(TAG, "First video frame rendered (${width}x$height). Projection is live.")
                runOnUiThread { hideGuideOverlay() }
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

    // MediaSink implementation
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
            isConnectingDirect.set(false)
            guideOverlay.visibility = View.VISIBLE
            statusText.text = "Disconnected: $reason\nWaiting for phone reconnect..."
            statusText.setTextColor(Color.parseColor("#FF6B6B"))
        }
    }

    // Touch & Key Dispatching to Active Engine.
    // Intercept at dispatch time so the SurfaceView can never swallow the gesture.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!hasStreamStarted) {
            return super.dispatchTouchEvent(event)
        }

        val viewW = surfaceView.width.toFloat()
        val viewH = surfaceView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) {
            return super.dispatchTouchEvent(event)
        }

        // SurfaceView renders the 1280x720 AAP buffer stretched to its bounds.
        val videoX = (event.x / viewW * 1280f).coerceIn(0f, 1279f)
        val videoY = (event.y / viewH * 720f).coerceIn(0f, 719f)

        deviceRouter.getActiveEngine()?.sendTouchEvent(event.actionMasked, videoX, videoY)
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
