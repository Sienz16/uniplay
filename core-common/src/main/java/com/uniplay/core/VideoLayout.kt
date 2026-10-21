package com.uniplay.core

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Makes the phone's fixed 1280x720 picture fit any head-unit screen without stretching.
 *
 * The phone is told to leave a margin of the canvas empty (see the service discovery
 * response) so it lays its UI out for the screen's real shape. The head unit then shows
 * only the visible part of the canvas, scaled to fill the screen: nothing is distorted
 * and nothing is letterboxed.
 */
object VideoLayout {

    const val CANVAS_WIDTH = 1280
    const val CANVAS_HEIGHT = 720

    /** Never ask the phone for less than this share of the canvas in either direction. */
    private const val MIN_VISIBLE_RATIO = 0.5

    private const val ASPECT_TOLERANCE = 0.01

    /** Total margin, in canvas pixels, the phone keeps free (split across both sides). */
    data class Margins(val width: Int, val height: Int) {
        val visibleWidth: Int get() = CANVAS_WIDTH - width
        val visibleHeight: Int get() = CANVAS_HEIGHT - height
    }

    fun marginsFor(screenWidth: Int, screenHeight: Int): Margins {
        if (screenWidth <= 0 || screenHeight <= 0) return Margins(0, 0)
        val aspect = maxOf(screenWidth, screenHeight).toDouble() / minOf(screenWidth, screenHeight)
        val canvasAspect = CANVAS_WIDTH.toDouble() / CANVAS_HEIGHT

        return when {
            aspect > canvasAspect + ASPECT_TOLERANCE -> {
                // Wider than 16:9: keep the full width, trim the height.
                val visible = even(Math.round(CANVAS_WIDTH / aspect).toInt())
                    .coerceAtLeast((CANVAS_HEIGHT * MIN_VISIBLE_RATIO).toInt())
                Margins(0, CANVAS_HEIGHT - visible)
            }
            aspect < canvasAspect - ASPECT_TOLERANCE -> {
                // Narrower than 16:9: keep the full height, trim the width.
                val visible = even(Math.round(CANVAS_HEIGHT * aspect).toInt())
                    .coerceAtLeast((CANVAS_WIDTH * MIN_VISIBLE_RATIO).toInt())
                Margins(CANVAS_WIDTH - visible, 0)
            }
            else -> Margins(0, 0)
        }
    }

    /** Margins for this device's real screen, status and navigation bars included. */
    @Suppress("DEPRECATION")
    fun marginsFor(context: Context): Margins {
        val metrics = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        return marginsFor(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Size of the full canvas on screen, so that its visible part exactly fills a
     * [parentWidth] x [parentHeight] area. Larger than the parent when there are
     * margins; the overflow is cropped.
     */
    fun surfaceSize(parentWidth: Int, parentHeight: Int, margins: Margins): Pair<Int, Int> =
        Pair(
            (parentWidth.toLong() * CANVAS_WIDTH / margins.visibleWidth).toInt(),
            (parentHeight.toLong() * CANVAS_HEIGHT / margins.visibleHeight).toInt()
        )

    private fun even(value: Int) = value and 1.inv()
}
