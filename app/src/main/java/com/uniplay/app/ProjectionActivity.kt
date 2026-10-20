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
import com.uniplay.core.*

/**
 * Universal projection activity.
 * Hosts a single SurfaceView for hardware-accelerated rendering,
 * displays an interactive first-boot wireless connection guide,
 * and coordinates the projection session with the Android lifecycle.
 */
class ProjectionActivity : Activity() {

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

    private lateinit var mediaRenderer: ProjectionMediaRenderer
    private lateinit var sessionController: ProjectionSessionController
    private lateinit var memoryGuard: MemoryGuard

    private var hasStreamStarted = false

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

        mediaRenderer = ProjectionMediaRenderer(
            onFirstFrameRendered = { width, height ->
                Log.i(TAG, "First video frame rendered (${width}x$height). Projection is live.")
                runOnUiThread { hideGuideOverlay() }
            },
            onSessionEnded = ::onSessionTerminated
        )
        surfaceView.holder.addCallback(mediaRenderer)

        memoryGuard = MemoryGuard(this)
        sessionController = ProjectionSessionController(this, mediaRenderer) { status, color ->
            runOnUiThread {
                statusText.text = status
                statusText.setTextColor(color)
            }
        }

        // Populate live network info into guide badges
        val localIp = NetworkUtils.getLocalIpAddress(this)
        val btName = sessionController.getBluetoothDeviceName()
        badgeWifiIp.text = "Wi-Fi IP: $localIp:${WirelessServer.PORT_MAIN}"
        badgeBluetooth.text = "Bluetooth: $btName"

        checkAndRequestPermissions()
        sessionController.startWirelessServices(localIp, btName)

        // One-tap direct Hotspot connect button
        val gatewayIp = NetworkUtils.getGatewayIp(this)
        btnConnectPhone.text = "⚡ Connect Directly to Phone ($gatewayIp)"
        btnConnectPhone.setOnClickListener {
            val currentGatewayIp = NetworkUtils.getGatewayIp(this)
            sessionController.connectDirectToPhone(
                manual = true,
                gatewayIp = currentGatewayIp,
                hasStreamStarted = hasStreamStarted
            )
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
            val btName = sessionController.getBluetoothDeviceName()
            sessionController.startWirelessServices(localIp, btName)
        }
    }

    private fun hideGuideOverlay() {
        if (guideOverlay.visibility != View.GONE) {
            guideOverlay.visibility = View.GONE
            hasStreamStarted = true
            Log.i(TAG, "First video frame rendered. Connection guide overlay hidden.")
        }
    }

    private fun onSessionTerminated(reason: String) {
        runOnUiThread {
            hasStreamStarted = false
            sessionController.onSessionTerminated()
            guideOverlay.visibility = View.VISIBLE
            statusText.text = "Disconnected: $reason\nWaiting for phone reconnect..."
            statusText.setTextColor(Color.parseColor("#FF6B6B"))
        }
    }

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

        sessionController.sendTouchEvent(event.actionMasked, videoX, videoY)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        sessionController.sendKeyEvent(keyCode, true)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        sessionController.sendKeyEvent(keyCode, false)
        return super.onKeyUp(keyCode, event)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "System low memory signal received!")
        memoryGuard.checkAndCompactMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionController.stop()
        mediaRenderer.stop()
    }
}
