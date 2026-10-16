package com.fxsoundlite.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Small code-drawn battery glyph for the Screensaver: outline + terminal nub,
 * a fill proportional to the charge level, and a lightning bolt while charging.
 * Green while charging, red at 15% or less, white otherwise.
 */
class BatteryIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var level = 0
    private var charging = false

    private val density = resources.displayMetrics.density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xE6FFFFFF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE6FFFFFF.toInt()
    }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val boltShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x80000000.toInt()
    }

    private val body = RectF()
    private val inner = RectF()
    private val nub = RectF()
    private val bolt = Path()

    fun setState(percent: Int, isCharging: Boolean) {
        val p = percent.coerceIn(0, 100)
        if (p == level && isCharging == charging) return
        level = p
        charging = isCharging
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((26 * density).toInt(), widthMeasureSpec)
        val h = resolveSize((13 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = outlinePaint.strokeWidth
        val half = stroke / 2f
        val nubW = h * 0.14f
        val corner = h * 0.22f

        body.set(half, half, w - nubW - half, h - half)
        canvas.drawRoundRect(body, corner, corner, outlinePaint)

        nub.set(w - nubW, h * 0.33f, w, h * 0.67f)
        canvas.drawRoundRect(nub, nubW / 2f, nubW / 2f, nubPaint)

        val pad = stroke + 1f * density
        inner.set(body.left + pad, body.top + pad, body.right - pad, body.bottom - pad)
        if (level > 0 && inner.width() > 0f) {
            fillPaint.color = when {
                charging -> 0xFF4CD964.toInt()
                level <= 15 -> 0xFFFF5252.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            val fillRight = inner.left + inner.width() * level / 100f
            val r = (corner * 0.6f)
            canvas.drawRoundRect(inner.left, inner.top, fillRight, inner.bottom, r, r, fillPaint)
        }

        if (charging) {
            val cx = body.centerX()
            val cy = body.centerY()
            val s = body.height() * 0.85f
            bolt.reset()
            bolt.moveTo(cx + 0.15f * s, cy - 0.5f * s)
            bolt.lineTo(cx - 0.20f * s, cy + 0.08f * s)
            bolt.lineTo(cx - 0.02f * s, cy + 0.08f * s)
            bolt.lineTo(cx - 0.15f * s, cy + 0.5f * s)
            bolt.lineTo(cx + 0.20f * s, cy - 0.08f * s)
            bolt.lineTo(cx + 0.02f * s, cy - 0.08f * s)
            bolt.close()
            canvas.drawPath(bolt, boltShadow)
            canvas.drawPath(bolt, boltPaint)
        }
    }
}
