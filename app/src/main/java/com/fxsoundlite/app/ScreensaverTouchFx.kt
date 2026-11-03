package com.fxsoundlite.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Touch effect of the in-app Screensaver page (NOT the automatic charging screensaver, which closes on any touch).
 * The effect is picked in Settings > Screensaver ([ScreensaverPrefs.touchFx]); each touch only shows it for a moment.
 */
object ScreensaverTouchFx {
    /** Order here is the order of the list in Settings > Touch effect (choices are saved by name, so it is safe to reorder). */
    enum class Mode(val labelRes: Int) {
        OFF(R.string.screensaver_fx_off),
        /** Default: the effect that suits the chosen background (see [ScreensaverPrefs.touchFx]); the user can override it. */
        AUTO(R.string.screensaver_fx_auto),
        SPARKLE(R.string.screensaver_fx_sparkle),
        /** Made for the night-sky background: twinkling star glints under the finger, a shooting star on release. */
        METEOR(R.string.screensaver_fx_meteor),
        /** Pushes the particles of the animated background (rain / stars) away from the finger. */
        SCATTER(R.string.screensaver_fx_scatter),
        RIPPLE(R.string.screensaver_fx_ripple)
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

    /** A four-point twinkling star that hangs in the air for a moment and drifts slowly. */
    private class Glint(
        val x: Float, val y: Float, val vx: Float, val vy: Float,
        val born: Long, val life: Long, val size: Float, val phase: Float
    )
    /** A shooting star: bright head, tapering tail, travels along (dx, dy) (unit vector) at [speed] px/ms. */
    private class Meteor(
        val x: Float, val y: Float, val dx: Float, val dy: Float,
        val speed: Float, val born: Long, val life: Long, val len: Float
    )

    private val glints = ArrayList<Glint>()
    private val meteors = ArrayList<Meteor>()
    private val starPath = Path()
    private var lastGlintAt = 0L
    private var lastMoveAt = 0L
    private var velX = 0f // finger velocity, px/ms (smoothed)
    private var velY = 0f
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
        ripples.clear(); sparks.clear(); glints.clear(); meteors.clear(); velX = 0f; velY = 0f; down = false
        invalidate()
    }

    fun onTouch(ev: MotionEvent) {
        val mode = ScreensaverPrefs.touchFx(context)
        fxMode = mode
        val now = SystemClock.uptimeMillis()
        if (mode == ScreensaverTouchFx.Mode.METEOR) onMeteorTouch(ev, now)
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

    private fun onMeteorTouch(ev: MotionEvent, now: Long) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velX = 0f; velY = 0f; lastMoveAt = now
                glints += Glint(ev.x, ev.y, 0f, 0f, now, 1100L, 15f * density, Random.nextFloat() * 6f)
                repeat(4) { addGlint(ev.x, ev.y, now, 18f) }
            }
            MotionEvent.ACTION_MOVE -> {
                val dt = (now - lastMoveAt).coerceAtLeast(1L)
                velX = velX * 0.6f + (ev.x - fingerX) / dt * 0.4f
                velY = velY * 0.6f + (ev.y - fingerY) / dt * 0.4f
                lastMoveAt = now
                if (now - lastGlintAt > 40L) {
                    lastGlintAt = now
                    addGlint(ev.x, ev.y, now, 8f)
                }
            }
            MotionEvent.ACTION_UP -> {
                // Fly off the way the finger was going; a plain tap sends it down and to the right.
                var dx = velX; var dy = velY
                var len = hypot(dx, dy)
                val tap = len < 0.08f * density
                if (tap) { dx = 0.8f; dy = 0.6f; len = 1f }
                dx /= len; dy /= len
                val speed = if (tap) 0.5f * density else hypot(velX, velY).coerceIn(0.35f * density, 0.8f * density)
                meteors += Meteor(ev.x, ev.y, dx, dy, speed, now, 1000L, 110f * density)
                glints += Glint(ev.x, ev.y, 0f, 0f, now, 700L, 12f * density, Random.nextFloat() * 6f)
                velX = 0f; velY = 0f
            }
        }
        if (glints.size > 120) repeat(glints.size - 120) { glints.removeAt(0) }
        if (meteors.size > 4) meteors.removeAt(0)
    }

    /** A little twinkle near (x, y): up to [spread] dp away, slowly drifting, living 0.7 - 1.4 s. */
    private fun addGlint(x: Float, y: Float, now: Long, spread: Float) {
        val a = Random.nextFloat() * (Math.PI * 2).toFloat()
        val d = Random.nextFloat() * spread * density
        val drift = 0.012f * density
        glints += Glint(
            x + cos(a) * d, y + sin(a) * d,
            (Random.nextFloat() - 0.5f) * drift, (Random.nextFloat() - 0.7f) * drift,
            now, 700L + Random.nextLong(700L), (3f + Random.nextFloat() * 5f) * density, Random.nextFloat() * 6f
        )
    }

    /** Four-point star with a thin waist, [r] = distance from the centre to a tip. */
    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        val w = r * 0.16f
        starPath.reset()
        starPath.moveTo(cx, cy - r); starPath.lineTo(cx + w, cy - w)
        starPath.lineTo(cx + r, cy); starPath.lineTo(cx + w, cy + w)
        starPath.lineTo(cx, cy + r); starPath.lineTo(cx - w, cy + w)
        starPath.lineTo(cx - r, cy); starPath.lineTo(cx - w, cy - w)
        starPath.close()
        fillPaint.color = color
        fillPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawPath(starPath, fillPaint)
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

        // Night sky: twinkling star glints (they shimmer while they fade).
        val starColor = mix(accent, Color.WHITE, 0.85f)
        val gi = glints.iterator()
        while (gi.hasNext()) {
            val g = gi.next()
            val age = now - g.born
            if (age >= g.life) { gi.remove(); continue }
            alive = true
            val t = age / g.life.toFloat()
            val twinkle = 0.65f + 0.35f * sin(age / 70f + g.phase)
            val cx = g.x + g.vx * age
            val cy = g.y + g.vy * age
            val grow = if (t < 0.15f) t / 0.15f else 1f - 0.35f * (t - 0.15f) // pops in, then slowly shrinks
            val r = g.size * grow
            dotPaint.color = starColor
            dotPaint.alpha = ((1f - t) * 60 * twinkle).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r * 0.55f, dotPaint) // soft glow
            drawStar(canvas, cx, cy, r, starColor, ((1f - t) * 255 * twinkle).toInt())
        }

        // Night sky: shooting stars, a bright head with a tail that tapers off behind it.
        val mi = meteors.iterator()
        while (mi.hasNext()) {
            val m = mi.next()
            val age = now - m.born
            if (age >= m.life) { mi.remove(); continue }
            alive = true
            val t = age / m.life.toFloat()
            val fade = 1f - t * t
            val dist = m.speed * age * (1f - 0.25f * t)
            val hx = m.x + m.dx * dist
            val hy = m.y + m.dy * dist
            val segs = 16
            ringPaint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until segs) {
                val a0 = i / segs.toFloat()
                val a1 = (i + 1) / segs.toFloat()
                val tail = m.len * (0.4f + 0.6f * (1f - t)) // the tail shortens as the star burns out
                ringPaint.color = if (i < 3) Color.WHITE else starColor
                ringPaint.alpha = ((1f - a0) * (1f - a0) * fade * 235).toInt().coerceIn(0, 255)
                ringPaint.strokeWidth = (3.4f * (1f - a0) + 0.6f) * density
                canvas.drawLine(hx - m.dx * tail * a0, hy - m.dy * tail * a0, hx - m.dx * tail * a1, hy - m.dy * tail * a1, ringPaint)
            }
            ringPaint.strokeCap = Paint.Cap.BUTT
            dotPaint.color = starColor
            dotPaint.alpha = (fade * 70).toInt().coerceIn(0, 255)
            canvas.drawCircle(hx, hy, 9f * density, dotPaint)
            drawStar(canvas, hx, hy, 6f * density, Color.WHITE, (fade * 255).toInt())
        }

        if (alive || down) postInvalidateOnAnimation()
    }
}
