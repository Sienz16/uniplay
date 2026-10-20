package com.uniplay.androidauto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Auto Official Wireless Bluetooth Handshake Server.
 * Listens on the standardized Android Auto RFCOMM UUID: 4de17a00-52cb-11e6-bdf4-0800200c9a66.
 * Automatically exchanges Wi-Fi credentials with Android phones so they switch on Wi-Fi
 * and connect to the Uniplay TCP server.
 */
class AndroidAutoWirelessManager(
    private val context: Context,
    private val onPhoneHandoffInitiated: (phoneName: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AAWirelessManager"
        // Official Google Android Auto Wireless Bluetooth RFCOMM UUID
        val AA_BT_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    private var serverThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(ssid: String, psk: String, localIp: String, port: Int = 5000) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled. Android Auto RFCOMM handshake unavailable.")
            return
        }

        if (isRunning.getAndSet(true)) return

        serverThread = Thread({
            runRfcommServer(ssid, psk, localIp, port)
        }, "AA-Wireless-BT-Server").apply {
            isDaemon = true
            start()
        }

        // Also proactively poke any already-paired phones to wake their Android Auto service
        pokeBondedDevices(ssid, psk, localIp, port)
    }

    @SuppressLint("MissingPermission")
    fun pokeBondedDevices(ssid: String, psk: String, localIp: String, port: Int = 5000) {
        val adapter = bluetoothAdapter ?: return
        Thread({
            try {
                val bonded = adapter.bondedDevices ?: return@Thread
                for (device in bonded) {
                    if (tryWakeBondedDevice(device, ssid, psk, localIp, port)) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error enumerating bonded devices: ${e.message}")
            }
        }, "AA-Wireless-BT-Poke").apply {
            isDaemon = true
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryWakeBondedDevice(
        device: BluetoothDevice,
        ssid: String,
        psk: String,
        localIp: String,
        port: Int
    ): Boolean {
        return try {
            val deviceName = try { device.name } catch (_: Exception) { "Paired Device" }
            Log.i(TAG, "Proactively waking paired device: $deviceName (${device.address})...")
            val socket = device.createRfcommSocketToServiceRecord(AA_BT_UUID)
            socket.connect()
            Log.i(TAG, "Connected to $deviceName! Sending Wi-Fi handoff...")
            onPhoneHandoffInitiated(deviceName)
            handleBluetoothHandshake(socket, ssid, psk, localIp, port)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Device ${device.address} did not answer active poke: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun runRfcommServer(ssid: String, psk: String, localIp: String, port: Int) {
        try {
            val server = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("Android Auto Wireless", AA_BT_UUID)
            serverSocket = server
            Log.i(TAG, "Android Auto Wireless RFCOMM server active on UUID: $AA_BT_UUID")

            while (isRunning.get()) {
                val socket = server?.accept() ?: break
                val remoteName = try { socket.remoteDevice.name ?: "Android Device" } catch (_: Exception) { "Android Device" }
                Log.i(TAG, "Accepted Bluetooth Android Auto connection from: $remoteName (${socket.remoteDevice.address})")

                onPhoneHandoffInitiated(remoteName)
                handleBluetoothHandshake(socket, ssid, psk, localIp, port)
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "RFCOMM server loop error: ${e.message}")
            }
        } finally {
            stop()
        }
    }

    private fun handleBluetoothHandshake(
        socket: BluetoothSocket,
        ssid: String,
        psk: String,
        localIp: String,
        port: Int
    ) {
        try {
            val output = socket.outputStream

            // 1. Send WifiStartRequest (Type 1)
            Log.i(TAG, "Sending WifiStartRequest to phone (Target: $localIp:$port)...")
            val startRequest = Wireless.WifiStartRequest.newBuilder()
                .setIpAddress(localIp)
                .setPort(port)
                .build()
            sendWirelessProtobuf(output, 1, startRequest.toByteArray())

            // Brief delay to let the phone's Android Auto service process
            Thread.sleep(500)

            // 2. Send WifiInfoResponse (Type 3) with Wi-Fi credentials
            Log.i(TAG, "Delivering Wi-Fi credentials to phone: SSID=$ssid...")
            val infoResponse = Wireless.WifiInfoResponse.newBuilder()
                .setSsid(ssid)
                .setKey(psk)
                .setBssid("")
                .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
                .setAccessPointType(Wireless.AccessPointType.DYNAMIC)
                .build()
            sendWirelessProtobuf(output, 3, infoResponse.toByteArray())

            Log.i(TAG, "Wi-Fi credentials delivered! Phone will now associate to Wi-Fi and connect to TCP port $port.")
        } catch (e: Exception) {
            Log.w(TAG, "Error during Bluetooth handshake with phone: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendWirelessProtobuf(output: OutputStream, type: Short, payload: ByteArray) {
        // Wireless Protobuf framing: [Length(2B)][Type(2B)][Payload]
        val totalLength = 2 + payload.size
        val buffer = ByteArray(4 + payload.size)
        buffer[0] = ((totalLength shr 8) and 0xFF).toByte()
        buffer[1] = (totalLength and 0xFF).toByte()
        buffer[2] = ((type.toInt() shr 8) and 0xFF).toByte()
        buffer[3] = (type.toInt() and 0xFF).toByte()
        System.arraycopy(payload, 0, buffer, 4, payload.size)

        synchronized(output) {
            output.write(buffer)
            output.flush()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping Android Auto Wireless Bluetooth server...")
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }
}
