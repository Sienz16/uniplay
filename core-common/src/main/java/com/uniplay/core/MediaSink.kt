package com.uniplay.core

/**
 * Universal media sink seam for zero-copy rendering.
 * Feeds directly into hardware MediaCodec and AudioTrack.
 */
interface MediaSink {
    fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int)
    fun onVideoNal(nalUnit: ByteArray, offset: Int, length: Int, timestampUs: Long)
    fun onAudioData(channel: AudioChannel, pcmData: ByteArray, sampleRate: Int, channels: Int)
    fun onSessionTerminated(reason: String)
}
