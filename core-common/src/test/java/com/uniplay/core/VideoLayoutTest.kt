package com.uniplay.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLayoutTest {

    @Test
    fun sixteenByNineNeedsNoMargins() {
        assertEquals(VideoLayout.Margins(0, 0), VideoLayout.marginsFor(1920, 1080))
        assertEquals(VideoLayout.Margins(0, 0), VideoLayout.marginsFor(1280, 720))
    }

    @Test
    fun ultraWideTrimsHeight() {
        val m = VideoLayout.marginsFor(1280, 480)
        assertEquals(0, m.width)
        assertEquals(480, m.visibleHeight)
    }

    @Test
    fun squarerScreenTrimsWidth() {
        val m = VideoLayout.marginsFor(1024, 768)
        assertEquals(0, m.height)
        assertEquals(960, m.visibleWidth)
    }

    @Test
    fun orientationDoesNotMatter() {
        assertEquals(VideoLayout.marginsFor(1024, 600), VideoLayout.marginsFor(600, 1024))
    }

    @Test
    fun visibleAreaMatchesScreenShape() {
        val m = VideoLayout.marginsFor(1024, 600)
        val screen = 1024.0 / 600
        val visible = m.visibleWidth.toDouble() / m.visibleHeight
        assertEquals(screen, visible, 0.02)
    }

    @Test
    fun absurdShapesAreClamped() {
        val m = VideoLayout.marginsFor(4000, 100)
        assertEquals(360, m.visibleHeight)
    }

    @Test
    fun surfaceOverflowsParentByTheMargins() {
        val m = VideoLayout.Margins(320, 0) // 960 of 1280 visible
        val (w, h) = VideoLayout.surfaceSize(1024, 768, m)
        assertEquals(1365, w)
        assertEquals(768, h)
    }

    @Test
    fun letterboxKeepsSixteenByNine() {
        val (w, h) = VideoLayout.surfaceSize(1024, 768, VideoLayout.Margins(0, 0), letterbox = true)
        assertEquals(1024, w)
        assertEquals(576, h)
        val (w2, h2) = VideoLayout.surfaceSize(1280, 480, VideoLayout.Margins(0, 0), letterbox = true)
        assertEquals(853, w2)
        assertEquals(480, h2)
    }

    @Test
    fun badScreenSizeGivesNoMargins() {
        assertEquals(VideoLayout.Margins(0, 0), VideoLayout.marginsFor(0, 0))
    }
}
