package com.fxsoundlite.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import java.util.Calendar

/**
 * Analog clock face for the Screensaver ("Analog" clock style in Settings).
 *
 * Drawn entirely in code -- a thin outer ring, 60 minute ticks (thicker at
 * every 5), hour / minute hands and a second hand in the app's accent color.
 * It has no timer of its own: ScreensaverFragment already ticks once per
 * second (aligned to the second boundary) and calls [setTime], so the hands
 * start/stop exactly when the page is visible.
 */
class AnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private var hourAngle = 0f
    private var minuteAngle = 0f
    private var secondAngle = 0f

    private val white = Color.WHITE
    private var accent = Color.parseColor("#FF6E40")

    private fun strokePaint(color: Int, widthDp: Float, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = alpha
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
        strokeCap = Paint.Cap.ROUND
    }

    private val ringPaint = strokePaint(white, 1.5f, 110)
    private val minuteTickPaint = strokePaint(white, 1f, 120)
    private val hourTickPaint = strokePaint(white, 3f, 255)
    private val hourHandPaint = strokePaint(white, 5f)
    private val minuteHandPaint = strokePaint(white, 3.5f)
    private val secondHandPaint = strokePaint(accent, 1.6f)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Follows whichever accent color the user picked in Settings.
        accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorAccent, accent)
        secondHandPaint.color = accent
    }

    fun setTime(now: Calendar) {
        val s = now.get(Calendar.SECOND)
        val m = now.get(Calendar.MINUTE)
        val h = now.get(Calendar.HOUR) // 0..11
        secondAngle = s * 6f
        minuteAngle = (m + s / 60f) * 6f
        hourAngle = (h + m / 60f) * 30f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 4f * density

        canvas.drawCircle(cx, cy, r, ringPaint)

        // Ticks, drawn pointing inward from the ring.
        for (i in 0 until 60) {
            val isHour = i % 5 == 0
            val len = if (isHour) r * 0.10f else r * 0.045f
            val paint = if (isHour) hourTickPaint else minuteTickPaint
            canvas.save()
            canvas.rotate(i * 6f, cx, cy)
            canvas.drawLine(cx, cy - r + 6f * density, cx, cy - r + 6f * density + len, paint)
            canvas.restore()
        }

        drawHand(canvas, cx, cy, hourAngle, r * 0.48f, r * 0.08f, hourHandPaint)
        drawHand(canvas, cx, cy, minuteAngle, r * 0.72f, r * 0.10f, minuteHandPaint)
        drawHand(canvas, cx, cy, secondAngle, r * 0.82f, r * 0.16f, secondHandPaint)

        capPaint.color = white
        canvas.drawCircle(cx, cy, 6f * density, capPaint)
        capPaint.color = accent
        canvas.drawCircle(cx, cy, 3f * density, capPaint)
    }

    /** A hand is a line through the center: [length] toward the tip, [tail] behind it. */
    private fun drawHand(
        canvas: Canvas, cx: Float, cy: Float, angle: Float,
        length: Float, tail: Float, paint: Paint
    ) {
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawLine(cx, cy + tail, cx, cy - length, paint)
        canvas.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Always square: use the smaller of what the parent offers.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val size = when {
            wMode != MeasureSpec.UNSPECIFIED && hMode != MeasureSpec.UNSPECIFIED -> minOf(w, h)
            wMode != MeasureSpec.UNSPECIFIED -> w
            hMode != MeasureSpec.UNSPECIFIED -> h
            else -> (240 * density).toInt()
        }
        setMeasuredDimension(size, size)
    }
}
