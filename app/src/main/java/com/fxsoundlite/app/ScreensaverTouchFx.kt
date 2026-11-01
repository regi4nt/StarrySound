package com.fxsoundlite.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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
        GLOW(R.string.screensaver_fx_glow),
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

    private class Ripple(val x: Float, val y: Float, val born: Long)
    private class Spark(
        var x: Float, var y: Float, val vx: Float, val vy: Float,
        val born: Long, val life: Long, val size: Float, val white: Boolean
    )

    private val ripples = ArrayList<Ripple>()
    private val sparks = ArrayList<Spark>()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var accent = Color.WHITE
    private var fingerX = 0f
    private var fingerY = 0f
    private var down = false
    private var glow = 0f // 0..1, eased in while touching and out after release
    private var lastSparkAt = 0L
    private var lastFrame = 0L
    private val density = resources.displayMetrics.density
    private var fxMode = ScreensaverTouchFx.Mode.RIPPLE

    fun setAccent(color: Int) { accent = color }

    /** Clears everything on screen (mode switched off / page left). */
    fun reset() {
        ripples.clear(); sparks.clear(); down = false; glow = 0f
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
                if (mode == ScreensaverTouchFx.Mode.RIPPLE) {
                    ripples += Ripple(ev.x, ev.y, now)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                fingerX = ev.x; fingerY = ev.y
                if (mode == ScreensaverTouchFx.Mode.SPARKLE && now - lastSparkAt > 24L) {
                    lastSparkAt = now
                    emitSparks(ev.x, ev.y, now, 2)
                }
                if (mode == ScreensaverTouchFx.Mode.RIPPLE && ripples.lastOrNull()?.let { now - it.born > 260L } != false) {
                    ripples += Ripple(ev.x, ev.y, now)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                down = false
                if (mode == ScreensaverTouchFx.Mode.SPARKLE && ev.actionMasked == MotionEvent.ACTION_UP) emitSparks(ev.x, ev.y, now, 10)
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && mode == ScreensaverTouchFx.Mode.SPARKLE) emitSparks(ev.x, ev.y, now, 6)
        if (ripples.size > 14) ripples.removeAt(0)
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

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0L, 64L)
        lastFrame = now
        val mode = fxMode
        var alive = false

        // Ripples: a ring that grows and fades.
        val it = ripples.iterator()
        val maxR = 120f * density
        while (it.hasNext()) {
            val r = it.next()
            val t = (now - r.born) / 1100f
            if (t >= 1f) { it.remove(); continue }
            alive = true
            val eased = 1f - (1f - t) * (1f - t)
            ringPaint.color = accent
            ringPaint.alpha = ((1f - t) * 200).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = (3.5f - 2.2f * t) * density
            canvas.drawCircle(r.x, r.y, maxR * eased, ringPaint)
            ringPaint.alpha = ((1f - t) * 90).toInt().coerceIn(0, 255)
            canvas.drawCircle(r.x, r.y, maxR * 0.55f * eased, ringPaint)
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

        // Glow: a soft light under the finger.
        val glowOn = mode == ScreensaverTouchFx.Mode.GLOW && down
        val target = if (glowOn) 1f else 0f
        glow += (target - glow) * (dt / 120f).coerceAtMost(1f)
        if (glow < 0.01f && !glowOn) glow = 0f
        if (glow > 0f) {
            alive = true
            val r = 90f * density
            glowPaint.shader = RadialGradient(
                fingerX, fingerY, r,
                (accent and 0x00FFFFFF) or ((glow * 170).toInt().coerceIn(0, 255) shl 24),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(fingerX, fingerY, r, glowPaint)
        }

        if (alive || down) postInvalidateOnAnimation() else lastFrame = 0L
    }
}
