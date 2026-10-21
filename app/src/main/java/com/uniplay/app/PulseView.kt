package com.uniplay.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Rings radiating from a phone glyph: the idle screen's "listening" animation.
 * Faster and brighter while a phone is connecting, green once it is live.
 */
class PulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFFF4F7FC.toInt()
    }
    private val phone = RectF()
    private val arcPath = Path()

    private var phase = 0f
    private var accent = 0xFF8FB2FF.toInt()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        duration = SLOW_MS
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    /** Ring colour (the idle blue, or green when connected). */
    fun setAccent(color: Int) {
        accent = color
        invalidate()
    }

    /** Speeds the rings up while a connection is being set up. */
    fun setBusy(busy: Boolean) {
        val target = if (busy) FAST_MS else SLOW_MS
        if (animator.duration == target) return
        animator.duration = target
        if (animator.isRunning) {
            animator.cancel()
            animator.start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f
        val coreR = maxR * 0.34f

        // Three rings, each a third of a cycle behind the last, growing and fading out.
        for (i in 0 until RINGS) {
            val t = (phase + i.toFloat() / RINGS) % 1f
            val r = coreR + (maxR - coreR) * t
            ringPaint.color = accent
            ringPaint.alpha = ((1f - t) * 150).toInt()
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        fillPaint.color = 0xFF141822.toInt()
        canvas.drawCircle(cx, cy, coreR, fillPaint)
        ringPaint.color = accent
        ringPaint.alpha = 255
        canvas.drawCircle(cx, cy, coreR, ringPaint)

        // Phone glyph.
        val pw = coreR * 0.62f
        val ph = coreR * 1.05f
        phone.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
        canvas.drawRoundRect(phone, 4f * density, 4f * density, glyphPaint)
        arcPath.reset()
        arcPath.moveTo(cx - pw * 0.15f, phone.bottom - 5f * density)
        arcPath.lineTo(cx + pw * 0.15f, phone.bottom - 5f * density)
        canvas.drawPath(arcPath, glyphPaint)
    }

    private companion object {
        const val RINGS = 3
        const val SLOW_MS = 3200L
        const val FAST_MS = 1400L
    }
}
