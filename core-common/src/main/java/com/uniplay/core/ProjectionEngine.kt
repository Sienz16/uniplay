package com.uniplay.core

import java.io.InputStream
import java.io.OutputStream

interface TransportChannel {
    val type: TransportType
    val inputStream: InputStream
    val outputStream: OutputStream
    fun close()
}

/**
 * Universal projection engine interface implemented by both
 * CarPlay and Android Auto protocol engines.
 */
interface ProjectionEngine {
    val type: ProjectionType
    val isRunning: Boolean

    fun start(transport: TransportChannel, sink: MediaSink)
    fun stop()

    fun sendTouchEvent(action: Int, x: Float, y: Float)
    fun sendKeyEvent(keyCode: Int, isDown: Boolean)
}
