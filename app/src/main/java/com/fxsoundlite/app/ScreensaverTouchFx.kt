package com.fxsoundlite.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Touch effect of the in-app Screensaver page (NOT the automatic charging screensaver, which closes on any touch).
 * The effect is picked in Settings > Screensaver ([ScreensaverPrefs.touchFx]); each touch only shows it for a moment.
 */
object ScreensaverTouchFx {
    enum class Mode(val labelRes: Int) {
        OFF(R.string.screensaver_fx_off),
        RIPPLE(R.string.screensaver_fx_ripple),
        SPARKLE(R.string.screensaver_fx_sparkle),
        /** Pushes the particles of the animated background (rain / stars) away from the finger. */
        SCATTER(R.string.screensaver_fx_scatter)
    }
}

/**
 * Transparent overlay above the Screensaver's background that draws the touch effect.
 * Feed it every touch with [onTouch]; it never consumes or blocks touches by itself.
 */
class TouchFxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** [scale] 1 = tap, smaller = the little wavelets left behind by a dragging finger. */
    private class Ripple(val x: Float, val y: Float, val born: Long, val scale: Float = 1f)
    private class Spark(
        var x: Float, var y: Float, val vx: Float, val vy: Float,
        val born: Long, val life: Long, val size: Float, val white: Boolean
    )

    private val ripples = ArrayList<Ripple>()
    private val sparks = ArrayList<Spark>()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var accent = Color.WHITE
    private var fingerX = 0f
    private var fingerY = 0f
    private var down = false
    private var lastSparkAt = 0L
    private var lastRippleAt = 0L
    private val density = resources.displayMetrics.density
    private var fxMode = ScreensaverTouchFx.Mode.RIPPLE

    private fun ScreensaverTouchFx.Mode.hasRipple() = this == ScreensaverTouchFx.Mode.RIPPLE

    fun setAccent(color: Int) { accent = color }

    /** Clears everything on screen (mode switched off / page left). */
    fun reset() {
        ripples.clear(); sparks.clear(); down = false
        invalidate()
    }

    fun onTouch(ev: MotionEvent) {
        val mode = ScreensaverPrefs.touchFx(context)
        fxMode = mode
        val now = SystemClock.uptimeMillis()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                down = true
                fingerX = ev.x; fingerY = ev.y
                // Scatter draws nothing here (no ring): it only pushes the background particles away from the finger.
                if (mode.hasRipple()) {
                    ripples += Ripple(ev.x, ev.y, now)
                    lastRippleAt = now
                }
            }
            MotionEvent.ACTION_MOVE -> {
                fingerX = ev.x; fingerY = ev.y
                if (mode == ScreensaverTouchFx.Mode.SPARKLE && now - lastSparkAt > 24L) {
                    lastSparkAt = now
                    emitSparks(ev.x, ev.y, now, 2)
                }
                // A finger dragged through water leaves a trail of smaller, softer wavelets.
                if (mode.hasRipple() && now - lastRippleAt > 130L) {
                    lastRippleAt = now
                    ripples += Ripple(ev.x, ev.y, now, 0.55f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                down = false
                if (mode == ScreensaverTouchFx.Mode.SPARKLE && ev.actionMasked == MotionEvent.ACTION_UP) emitSparks(ev.x, ev.y, now, 10)
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && mode == ScreensaverTouchFx.Mode.SPARKLE) emitSparks(ev.x, ev.y, now, 6)
        if (ripples.size > 10) ripples.removeAt(0)
        if (sparks.size > 160) repeat(sparks.size - 160) { sparks.removeAt(0) }
        invalidate()
    }

    private fun emitSparks(x: Float, y: Float, now: Long, n: Int) {
        repeat(n) {
            val a = Random.nextFloat() * (Math.PI * 2).toFloat()
            val v = (30f + Random.nextFloat() * 130f) * density / 1000f // px per ms
            sparks += Spark(
                x, y, cos(a) * v, sin(a) * v - 0.02f * density, now,
                500L + Random.nextLong(600L), (1.6f + Random.nextFloat() * 2.8f) * density, Random.nextInt(3) == 0
            )
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.rgb(
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var alive = false

        // Water ripples: a drop touches the surface and a few concentric wave rings spread out one after another.
        // Each ring is a soft wide body + a bright crest + a dark trough just inside it (that is what makes the
        // surface look like it is bending light), and it widens, slows down and fades as it travels.
        val crest = mix(accent, Color.WHITE, 0.6f)
        val ringCount = 3
        val ringDelay = 190f
        val duration = 1900f
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val age = (now - r.born).toFloat()
            if (age >= duration + ringDelay * (ringCount - 1)) { it.remove(); continue }
            alive = true
            val maxR = 130f * density * r.scale

            // The first instant: a small dimple right where the finger landed.
            val tp = age / 320f
            if (tp in 0f..1f) {
                fillPaint.color = crest
                fillPaint.alpha = ((1f - tp) * 70 * r.scale).toInt().coerceIn(0, 255)
                canvas.drawCircle(r.x, r.y, (4f + 14f * tp) * density * r.scale, fillPaint)
            }

            for (k in 0 until ringCount) {
                val t = (age - k * ringDelay) / duration
                if (t <= 0f || t >= 1f) continue
                val eased = 1f - (1f - t) * (1f - t) * (1f - t) // fast at first, then drifts to a stop
                val radius = maxR * (0.12f + 0.88f * eased) * (1f - 0.12f * k)
                val fade = (1f - t) * (1f - t) * (1f - 0.28f * k) * (if (k == 0) 1f else 0.85f)
                val w = (2.2f + 5f * t) * density * (0.7f + 0.3f * r.scale)

                // soft body of the wave
                ringPaint.color = accent
                ringPaint.alpha = (fade * 55).toInt().coerceIn(0, 255)
                ringPaint.strokeWidth = w * 3.2f
                canvas.drawCircle(r.x, r.y, radius, ringPaint)

                // dark trough just inside the crest
                ringPaint.color = Color.BLACK
                ringPaint.alpha = (fade * 70).toInt().coerceIn(0, 255)
                ringPaint.strokeWidth = w * 1.1f
                canvas.drawCircle(r.x, r.y, (radius - w * 0.9f).coerceAtLeast(0f), ringPaint)

                // bright crest
                ringPaint.color = crest
                ringPaint.alpha = (fade * 190).toInt().coerceIn(0, 255)
                ringPaint.strokeWidth = w * 0.7f
                canvas.drawCircle(r.x, r.y, radius, ringPaint)
            }
        }

        // Sparkles: little dots flying out and fading.
        val si = sparks.iterator()
        while (si.hasNext()) {
            val s = si.next()
            val age = now - s.born
            if (age >= s.life) { si.remove(); continue }
            alive = true
            val t = age / s.life.toFloat()
            val px = s.x + s.vx * age
            val py = s.y + s.vy * age + 0.00006f * density * age * age // slight fall
            dotPaint.color = if (s.white) Color.WHITE else accent
            dotPaint.alpha = ((1f - t) * 230).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, s.size * (1f - 0.5f * t), dotPaint)
        }

        if (alive || down) postInvalidateOnAnimation()
    }
}
