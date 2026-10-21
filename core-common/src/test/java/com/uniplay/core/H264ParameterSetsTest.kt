package com.uniplay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264ParameterSetsTest {

    private val start4 = byteArrayOf(0, 0, 0, 1)
    private val start3 = byteArrayOf(0, 0, 1)

    private fun nal(startCode: ByteArray, type: Int, vararg payload: Int) =
        startCode + byteArrayOf(type.toByte()) + payload.map { it.toByte() }.toByteArray()

    @Test
    fun startCodeLengthRecognisesBothForms() {
        assertEquals(4, H264ParameterSets.startCodeLength(start4 + byteArrayOf(0x65), 0, 5))
        assertEquals(3, H264ParameterSets.startCodeLength(start3 + byteArrayOf(0x65), 0, 4))
        assertEquals(0, H264ParameterSets.startCodeLength(byteArrayOf(1, 2, 3, 4), 0, 4))
    }

    @Test
    fun idrSliceIsAKeyframe() {
        val unit = nal(start4, 0x65, 1, 2, 3)
        assertTrue(H264ParameterSets.isKeyframe(unit, 0, unit.size))
    }

    @Test
    fun leadingSpsMarksKeyframe() {
        val unit = nal(start4, 0x67, 1, 2) + nal(start4, 0x68, 3) + nal(start4, 0x65, 4)
        assertTrue(H264ParameterSets.isKeyframe(unit, 0, unit.size))
    }

    @Test
    fun deltaFrameIsNotAKeyframe() {
        val unit = nal(start4, 0x41, 1, 2, 3, 4)
        assertFalse(H264ParameterSets.isKeyframe(unit, 0, unit.size))
    }

    @Test
    fun keyframeFoundAfterNonZeroOffset() {
        val unit = byteArrayOf(9, 9) + nal(start3, 0x65, 1)
        assertTrue(H264ParameterSets.isKeyframe(unit, 2, unit.size - 2))
    }

    @Test
    fun truncatedStartCodeDoesNotThrow() {
        assertFalse(H264ParameterSets.isKeyframe(byteArrayOf(0, 0, 0, 1), 0, 4))
        assertFalse(H264ParameterSets.isKeyframe(ByteArray(0), 0, 0))
    }

    @Test
    fun findReturnsNullWithoutBothSets() {
        val onlySps = nal(start4, 0x67, 0x42, 0, 0x1f)
        assertEquals(null, H264ParameterSets.find(onlySps, 0, onlySps.size))
    }

    @Test
    fun findLocatesSpsAndPps() {
        val unit = nal(start4, 0x67, 0x42, 0x00, 0x1f, 0xE9, 0x40, 0x50, 0x1E) +
            nal(start4, 0x68, 0xCE, 0x3C, 0x80) + nal(start4, 0x65, 1, 2)
        assertNotNull(H264ParameterSets.find(unit, 0, unit.size))
    }
}
