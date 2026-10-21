package com.uniplay.androidauto

/** Builds the plaintext AAP frames the head unit sends. Pure, so it can be unit-tested. */
object AapFraming {

    private const val CHANNEL_CONTROL = 0
    private const val FLAG_FIRST_AND_LAST = 0x03
    private const val FLAG_CONTROL = 0x04

    /** Message types 1..26 are control messages when sent on a non-control channel. */
    private val CONTROL_TYPES = 1..26

    /** Header (4B): [Channel][Flags][PayloadLen(2B)], then Type (2B) and the body. */
    fun build(channel: Int, type: Int, body: ByteArray): ByteArray {
        val payloadLength = 2 + body.size
        val frame = ByteArray(4 + payloadLength)
        val controlFlag = if (channel != CHANNEL_CONTROL && type in CONTROL_TYPES) FLAG_CONTROL else 0

        frame[0] = channel.toByte()
        frame[1] = (FLAG_FIRST_AND_LAST or controlFlag).toByte()
        frame[2] = ((payloadLength shr 8) and 0xFF).toByte()
        frame[3] = (payloadLength and 0xFF).toByte()
        frame[4] = ((type shr 8) and 0xFF).toByte()
        frame[5] = (type and 0xFF).toByte()
        System.arraycopy(body, 0, frame, 6, body.size)
        return frame
    }
}
