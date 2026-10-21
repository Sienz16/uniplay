package com.uniplay.androidauto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AapFramingTest {

    @Test
    fun headerCarriesChannelFlagsLengthAndType() {
        val frame = AapFraming.build(channel = 3, type = 0x8001, body = byteArrayOf(9, 8, 7))

        assertArrayEquals(
            byteArrayOf(3, 0x03, 0, 5, 0x80.toByte(), 0x01, 9, 8, 7),
            frame
        )
    }

    @Test
    fun controlTypeOnDataChannelSetsControlBit() {
        val frame = AapFraming.build(channel = 2, type = 7, body = ByteArray(0))
        assertEquals(0x07, frame[1].toInt())
    }

    @Test
    fun controlChannelNeverSetsControlBit() {
        val frame = AapFraming.build(channel = 0, type = 7, body = ByteArray(0))
        assertEquals(0x03, frame[1].toInt())
    }

    @Test
    fun emptyBodyStillHasTypeBytes() {
        val frame = AapFraming.build(channel = 1, type = 0x8000, body = ByteArray(0))
        assertEquals(6, frame.size)
        assertEquals(2, frame[3].toInt())
    }
}
