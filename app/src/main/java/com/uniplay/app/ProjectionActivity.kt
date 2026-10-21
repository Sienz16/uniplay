package com.uniplay.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.app.Activity
import android.content.pm.PackageManager
import android.animation.ObjectAnimator
import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.app.AlertDialog
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
        private const val ACTION_USB_PERMISSION = "com.uniplay.app.USB_PERMISSION"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var guideOverlay: View
    private lateinit var badgeWifiIp: TextView
    private lateinit var badgeBluetooth: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private var dotPulse: ObjectAnimator? = null
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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = findViewById(R.id.projection_surface)
        guideOverlay = findViewById(R.id.guide_overlay)
        badgeWifiIp = findViewById(R.id.badge_wifi_ip)
        badgeBluetooth = findViewById(R.id.badge_bluetooth)
        statusText = findViewById(R.id.text_status)
        statusDot = findViewById(R.id.status_dot)
        startDotPulse()
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
                statusDot.backgroundTintList = ColorStateList.valueOf(color)
            }
        }

        // Populate live network info into guide badges
        val localIp = NetworkUtils.getLocalIpAddress(this)
        val btName = sessionController.getBluetoothDeviceName()
        badgeWifiIp.text = "Wi-Fi  $localIp:${WirelessServer.PORT_MAIN}"
        badgeBluetooth.text = "Bluetooth  $btName"

        checkAndRequestPermissions()
        sessionController.startWirelessServices(localIp, btName)
        findViewById<View>(R.id.btn_hotspot_settings).setOnClickListener { showHotspotDialog() }
        if (!sessionController.hotspot.isConfigured) showHotspotDialog()

        // One-tap direct Hotspot connect button
        val gatewayIp = NetworkUtils.getGatewayIp(this)
        btnConnectPhone.text = "Connect to phone hotspot ($gatewayIp)"
        btnConnectPhone.setOnClickListener {
            val currentGatewayIp = NetworkUtils.getGatewayIp(this)
            sessionController.connectDirectToPhone(
                manual = true,
                gatewayIp = currentGatewayIp,
                hasStreamStarted = hasStreamStarted
            )
        }

        registerUsbPermissionReceiver()
        handleUsbIntent(intent)

        Log.i(TAG, "ProjectionActivity initialized. ${memoryGuard.dumpMemoryStats()}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) return
            usbDeviceFrom(intent)?.let { sessionController.onUsbDeviceAttached(it) }
        }
    }

    private fun registerUsbPermissionReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceFrom(intent: Intent): UsbDevice? =
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    /** A phone plugged in: use it if we may, otherwise ask Android for permission first. */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = usbDeviceFrom(intent) ?: return
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usb.hasPermission(device)) {
            sessionController.onUsbDeviceAttached(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            usb.requestPermission(device, pending)
        }
    }

    /** Slow breathing on the status dot: the screen is alive, not frozen. */
    private fun startDotPulse() {
        dotPulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.3f).apply {
            duration = 1400
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
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
            // Bluetooth just became usable, so the handoff can start for real now.
            sessionController.startAndroidAutoHandoff(NetworkUtils.getLocalIpAddress(this))
        }
    }

    /** Asks for the head unit hotspot name and password the phone must join. */
    private fun showHotspotDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val ssid = EditText(this).apply {
            hint = "Hotspot name"
            setText(sessionController.hotspot.ssid)
            setSingleLine()
        }
        val psk = EditText(this).apply {
            hint = "Hotspot password (8+ characters)"
            setText(sessionController.hotspot.psk)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(ssid)
            addView(psk)
        }
        AlertDialog.Builder(this)
            .setTitle("Head unit hotspot")
            .setMessage("Enter the Wi-Fi hotspot this head unit is sharing. Your phone is told to join it over Bluetooth.")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                sessionController.hotspot.save(ssid.text.toString(), psk.text.toString())
                sessionController.startAndroidAutoHandoff(NetworkUtils.getLocalIpAddress(this))
            }
            .show()
    }

    private fun hideGuideOverlay() {
        if (guideOverlay.visibility != View.GONE) {
            guideOverlay.animate().alpha(0f).setDuration(250).withEndAction {
                guideOverlay.visibility = View.GONE
                dotPulse?.cancel()
            }.start()
            hasStreamStarted = true
            Log.i(TAG, "First video frame rendered. Connection guide overlay hidden.")
        }
    }

    private fun onSessionTerminated(reason: String) {
        runOnUiThread {
            hasStreamStarted = false
            sessionController.onSessionTerminated()
            guideOverlay.animate().cancel()
            guideOverlay.alpha = 1f
            guideOverlay.visibility = View.VISIBLE
            if (dotPulse?.isRunning != true) startDotPulse()
            statusText.text = "Disconnected: $reason. Waiting for your phone"
            statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F07C7C"))
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

        // Only single-finger input is mapped; a second finger would send the phone a
        // pointer-down it has no pointer data for.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP
        ) return true

        // SurfaceView renders the 1280x720 AAP buffer stretched to its bounds.
        val videoX = (event.x / viewW * 1280f).coerceIn(0f, 1279f)
        val videoY = (event.y / viewH * 720f).coerceIn(0f, 719f)

        sessionController.sendTouchEvent(event.actionMasked, videoX, videoY)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (hasStreamStarted && event?.repeatCount == 0) sessionController.sendKeyEvent(keyCode, true)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (hasStreamStarted) sessionController.sendKeyEvent(keyCode, false)
        return super.onKeyUp(keyCode, event)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "System low memory signal received!")
        memoryGuard.checkAndCompactMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        dotPulse?.cancel()
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: IllegalArgumentException) {
        }
        sessionController.stop()
        mediaRenderer.stop()
    }
}
