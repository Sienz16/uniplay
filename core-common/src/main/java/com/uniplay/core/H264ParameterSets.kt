package com.uniplay.core

/**
 * Minimal H.264 Annex-B helpers.
 *
 * Two jobs, both needed before a MediaCodec can decode anything:
 *
 *  - find the SPS and the PPS inside a stream, so the decoder can be configured with
 *    `csd-0` / `csd-1`. An AVC decoder started without them accepts input and never
 *    produces a frame, which is exactly the "connected but black screen" symptom.
 *  - read the picture size out of the SPS, so the decoder is configured with the size
 *    the phone is really sending rather than the size we hoped it would send.
 */
object H264ParameterSets {

    const val NAL_TYPE_SPS = 7
    const val NAL_TYPE_PPS = 8

    /** SPS and PPS, each including its start code and NAL header. */
    data class ParameterSets(
        val sps: ByteArray,
        val pps: ByteArray,
        val width: Int,
        val height: Int
    )

    /**
     * Length of the Annex-B start code at [index], or 0 when there is none.
     * Handles both the 3-byte (00 00 01) and the 4-byte (00 00 00 01) form.
     */
    fun startCodeLength(data: ByteArray, index: Int, end: Int): Int {
        if (index + 3 > end) return 0
        if (data[index].toInt() != 0 || data[index + 1].toInt() != 0) return 0
        if (data[index + 2].toInt() == 1) return 3
        if (index + 4 <= end && data[index + 2].toInt() == 0 && data[index + 3].toInt() == 1) return 4
        return 0
    }

    /**
     * Whether an access unit is a keyframe: an IDR slice (NAL 5) or the parameter sets
     * that lead one (NAL 7) within its first [scanLimit] bytes. A decoder that lost
     * frames can only restart cleanly from one of these.
     */
    fun isKeyframe(data: ByteArray, offset: Int, length: Int, scanLimit: Int = 256): Boolean {
        val end = (offset + minOf(length, scanLimit)).coerceAtMost(data.size)
        var i = offset
        while (i + 3 < end) {
            val sc = startCodeLength(data, i, end)
            if (sc == 0) {
                i++
                continue
            }
            if (i + sc >= end) return false
            val nalType = data[i + sc].toInt() and 0x1F
            if (nalType == 5 || nalType == NAL_TYPE_SPS) return true
            i += sc
        }
        return false
    }

    /** Whether an access unit starts with a start code at [offset] and has payload behind it. */
    fun payloadStartsAt(data: ByteArray, offset: Int, end: Int): Boolean {
        val scLen = startCodeLength(data, offset, end)
        return scLen > 0 && end > offset + scLen
    }

    /**
     * Scans an Annex-B buffer for the first SPS/PPS pair.
     *
     * Returns null until both parameter sets have been seen in the same buffer, which is
     * the case for every keyframe Android Auto sends.
     */
    fun find(data: ByteArray, offset: Int, length: Int): ParameterSets? {
        val end = (offset + length).coerceAtMost(data.size)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var i = offset

        while (i + 3 < end) {
            val startCodeLen = startCodeLength(data, i, end)
            if (startCodeLen == 0) {
                i++
                continue
            }
            val nalStart = i
            val header = i + startCodeLen
            if (header >= end) break
            val nalType = data[header].toInt() and 0x1F

            var next = header + 1
            var nalEnd = end
            while (next + 3 <= end) {
                if (startCodeLength(data, next, end) > 0) {
                    nalEnd = next
                    break
                }
                next++
            }

            if (nalType == NAL_TYPE_SPS && sps == null) {
                sps = data.copyOfRange(nalStart, nalEnd)
            } else if (nalType == NAL_TYPE_PPS && pps == null) {
                pps = data.copyOfRange(nalStart, nalEnd)
            }

            if (sps != null && pps != null) break
            i = nalEnd
        }

        val s = sps ?: return null
        val p = pps ?: return null
        val dims = parseDimensions(s)
        return ParameterSets(s, p, dims?.first ?: 0, dims?.second ?: 0)
    }

    /**
     * Reads width and height out of an SPS, or null when the bitstream does not parse.
     *
     * The SPS passed in must include its start code and NAL header, which is how
     * [find] returns it.
     */
    fun parseDimensions(sps: ByteArray): Pair<Int, Int>? {
        return try {
            val end = sps.size
            val scLen = startCodeLength(sps, 0, end)
            // Skip the start code (when present) and the one-byte NAL header; the
            // RBSP the syntax below describes starts after it.
            val rbspStart = (if (scLen > 0) scLen else 0) + 1
            if (rbspStart >= end) return null

            val r = BitReader(sps, rbspStart, end)

            val profileIdc = r.readBits(8)
            r.readBits(8) // constraint flags + reserved
            r.readBits(8) // level_idc
            r.readUnsignedExpGolomb() // seq_parameter_set_id
            val chromaFormatIdc = readChromaFormat(r, profileIdc)

            r.readUnsignedExpGolomb() // log2_max_frame_num_minus4
            if (!skipPictureOrderCountParameters(r)) return null
            r.readUnsignedExpGolomb() // max_num_ref_frames
            r.readBit() // gaps_in_frame_num_value_allowed_flag

            readPictureDimensions(r, chromaFormatIdc)
        } catch (_: Exception) {
            null
        }
    }

    private fun readPictureDimensions(reader: BitReader, chromaFormatIdc: Int): Pair<Int, Int>? {
        val picWidthInMbsMinus1 = reader.readUnsignedExpGolomb()
        val picHeightInMapUnitsMinus1 = reader.readUnsignedExpGolomb()
        val frameMbsOnlyFlag = reader.readBit()
        if (frameMbsOnlyFlag == 0) reader.readBit() // mb_adaptive_frame_field_flag
        reader.readBit() // direct_8x8_inference_flag

        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (reader.readBit() == 1) {
            cropLeft = reader.readUnsignedExpGolomb()
            cropRight = reader.readUnsignedExpGolomb()
            cropTop = reader.readUnsignedExpGolomb()
            cropBottom = reader.readUnsignedExpGolomb()
        }

        val subWidthC = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
        val subHeightC = if (chromaFormatIdc == 1) 2 else 1
        val cropUnitX = if (chromaFormatIdc == 0) 1 else subWidthC
        val cropUnitY = (if (chromaFormatIdc == 0) 1 else subHeightC) * (2 - frameMbsOnlyFlag)

        val width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * cropUnitX
        val height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16 -
            (cropTop + cropBottom) * cropUnitY

        return if (width <= 0 || height <= 0 || width > 16384 || height > 16384) null else width to height
    }

    private fun readChromaFormat(reader: BitReader, profileIdc: Int): Int {
        if (!usesHighProfileSyntax(profileIdc)) return 1

        val chromaFormatIdc = reader.readUnsignedExpGolomb()
        if (chromaFormatIdc == 3) reader.readBit() // separate_colour_plane_flag
        reader.readUnsignedExpGolomb() // bit_depth_luma_minus8
        reader.readUnsignedExpGolomb() // bit_depth_chroma_minus8
        reader.readBit() // qpprime_y_zero_transform_bypass_flag

        if (reader.readBit() == 1) skipScalingMatrices(reader, chromaFormatIdc)
        return chromaFormatIdc
    }

    private fun usesHighProfileSyntax(profileIdc: Int): Boolean = when (profileIdc) {
        100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135 -> true
        else -> false
    }

    private fun skipScalingMatrices(reader: BitReader, chromaFormatIdc: Int) {
        val listCount = if (chromaFormatIdc != 3) 8 else 12
        for (listIndex in 0 until listCount) {
            if (reader.readBit() == 1) {
                skipScalingList(reader, if (listIndex < 6) 16 else 64)
            }
        }
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (index in 0 until size) {
            if (nextScale != 0) {
                val delta = reader.readSignedExpGolomb()
                nextScale = (lastScale + delta + 256) % 256
            }
            if (nextScale != 0) lastScale = nextScale
        }
    }

    private fun skipPictureOrderCountParameters(reader: BitReader): Boolean {
        when (val picOrderCntType = reader.readUnsignedExpGolomb()) {
            0 -> reader.readUnsignedExpGolomb() // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                reader.readBit() // delta_pic_order_always_zero_flag
                reader.readSignedExpGolomb() // offset_for_non_ref_pic
                reader.readSignedExpGolomb() // offset_for_top_to_bottom_field
                val cycle = reader.readUnsignedExpGolomb()
                for (index in 0 until cycle) reader.readSignedExpGolomb()
            }
            else -> if (picOrderCntType < 0) return false
        }
        return true
    }

    /** Bit reader over the SPS RBSP. */
    private class BitReader(
        private val data: ByteArray,
        private var byteIndex: Int,
        private val end: Int
    ) {
        private var bitIndex = 0

        fun readBit(): Int {
            if (byteIndex >= end) throw IllegalStateException("SPS bitstream overrun")
            val bit = (data[byteIndex].toInt() shr (7 - bitIndex)) and 1
            bitIndex++
            if (bitIndex == 8) {
                bitIndex = 0
                byteIndex++
            }
            return bit
        }

        fun readBits(count: Int): Int {
            var value = 0
            for (i in 0 until count) value = (value shl 1) or readBit()
            return value
        }

        fun readUnsignedExpGolomb(): Int {
            var leadingZeros = 0
            while (readBit() == 0) {
                leadingZeros++
                if (leadingZeros > 31) throw IllegalStateException("invalid exp-golomb code")
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + readBits(leadingZeros)
        }

        fun readSignedExpGolomb(): Int {
            val code = readUnsignedExpGolomb()
            return if (code % 2 == 0) -(code / 2) else (code + 1) / 2
        }
    }
}
