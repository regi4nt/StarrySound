package com.fxsoundlite.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Self-contained, code-only animated backgrounds for the Screensaver page --
 * an alternative to picking a photo/video in Settings. Everything here is
 * drawn on a [Canvas] each frame using paths, gradients and simple particle
 * lists; there are no bitmap/video assets, so this adds ~0 KB to APK size
 * and never needs storage/media permissions.
 *
 * Three sleep-friendly [Style]s are supported (Rain, Night Sky, Starfield). A single self-scheduling
 * [ValueAnimator] drives all of them (see [start]/[stop]) so switching
 * styles at runtime never leaves a second loop running underneath.
 *
 * Usage: add this View to a layout, then from the hosting Fragment/Activity
 * call [start] in onResume() and [stop] in onPause(), mirroring how
 * ScreensaverFragment already starts/pauses its VideoView.
 */
class SleepBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Style { RAIN, NIGHT_SKY, STARFIELD }

    private companion object {
        /** The ONE motion speed (px/second) shared by Rain (falling drops) and
         *  Starfield (drifting glints), so both themes always move at the same
         *  calm pace. Each particle gets MIN + random * SPAN. Change these two
         *  numbers to make both themes faster or slower together. */
        const val FLOW_SPEED_MIN = 110f
        const val FLOW_SPEED_SPAN = 90f
    }

    var style: Style = Style.NIGHT_SKY
        set(value) {
            if (field == value) return
            field = value
            bgShaderKey = null // background colors differ per style -- rebuild
            invalidate()
        }

    // --- shared animation driver --------------------------------------------
    // Frame clock driven directly off Choreographer.postFrameCallback rather
    // than a ValueAnimator. A ValueAnimator's tick rate is scaled by the
    // system's "Animator duration scale" developer setting -- many devices
    // (battery-saver modes, some OEM skins, some emulators) ship with that
    // scale set to 0, which makes a ValueAnimator resolve instantly with no
    // intermediate onAnimationUpdate calls at all, freezing elapsedMs and
    // making this view's animation appear completely static. Choreographer
    // callbacks are tied to vsync and are not affected by that setting, so
    // this keeps animating regardless of the user's animation-scale settings.
    // Using the actual per-frame delta (instead of assuming a flat 16ms) also
    // keeps motion speed consistent on 90Hz/120Hz screens and after dropped
    // frames, rather than assuming a fixed 60Hz cadence.
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameTimeNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameTimeNanos != 0L) {
                val deltaMs = ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000L)
                    .coerceIn(0L, 100L) // clamp huge gaps (e.g. after being backgrounded)
                elapsedMs += deltaMs
                when (style) {
                    Style.RAIN -> advanceRain(deltaMs)
                    Style.STARFIELD -> advanceStarfield(deltaMs)
                    else -> {}
                }
                invalidate()
            }
            lastFrameTimeNanos = frameTimeNanos
            choreographer.postFrameCallback(this)
        }
    }
    private var elapsedMs = 0L
    private var seeded = false

    fun start() {
        if (width > 0 && height > 0 && !seeded) seedAll()
        if (!running) {
            running = true
            lastFrameTimeNanos = 0L
            choreographer.postFrameCallback(frameCallback)
        }
    }

    fun stop() {
        running = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgShaderKey = null
        if (w > 0 && h > 0) seedAll() // re-seed so particle counts match the new size
    }

    private fun seedAll() {
        seedRain()
        seedNightSky()
        seedStarfield()
        seeded = true
    }

    // --- Rain ----------------------------------------------------------------
    // Thin streaks falling with a slight leftward drift, each looping back
    // above the top once it exits the bottom. Speeds are in px/second and
    // scaled by the real per-frame time delta (advanceRain(deltaMs)) rather
    // than a fixed per-frame step, so motion stays calm and consistent
    // regardless of the display's refresh rate -- tuned to read as gentle,
    // steady rain seen through a window rather than a fast-falling storm.

    private class Drop(var x: Float, var y: Float, var len: Float, var speed: Float, var alpha: Int)
    private val drops = mutableListOf<Drop>()
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CFE8FF")
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
    }

    private fun seedRain() {
        drops.clear()
        val count = ((width * height) / 9000f).toInt().coerceIn(60, 220)
        repeat(count) {
            drops += Drop(
                x = Random.nextFloat() * width * 1.2f - width * 0.1f,
                y = Random.nextFloat() * height,
                len = 14f + Random.nextFloat() * 20f,
                speed = FLOW_SPEED_MIN + Random.nextFloat() * FLOW_SPEED_SPAN, // px/sec -- same pace as Starfield
                alpha = 70 + Random.nextInt(110)
            )
        }
    }

    private fun advanceRain(deltaMs: Long) {
        if (drops.isEmpty()) return
        val dt = deltaMs / 1000f
        for (d in drops) {
            d.y += d.speed * dt
            d.x -= d.speed * 0.12f * dt
            if (d.y - d.len > height) {
                d.y = -d.len - Random.nextFloat() * height * 0.3f
                d.x = Random.nextFloat() * width * 1.2f - width * 0.1f
            }
        }
    }

    private fun drawRain(canvas: Canvas) {
        drawBackground(canvas, "#0B1420", "#1C2E40")
        for (d in drops) {
            rainPaint.alpha = d.alpha
            canvas.drawLine(d.x, d.y, d.x - d.len * 0.18f, d.y + d.len, rainPaint)
        }
    }

    // --- Night sky & shared moon/stars ----------------------------------------
    // Static star field (positions never change) with each star's brightness
    // driven by its own sine phase for a gentle, staggered twinkle, plus a
    // moon with a soft radial glow.

    private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Float)
    private val stars = mutableListOf<Star>()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F1E4") }
    private val moonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var moonGlowKey: String? = null

    private fun seedNightSky() {
        stars.clear()
        val count = ((width * height) / 6000f).toInt().coerceIn(80, 260)
        repeat(count) {
            stars += Star(
                x = Random.nextFloat() * width,
                y = Random.nextFloat() * height * 0.85f,
                r = 1f + Random.nextFloat() * 1.8f,
                phase = Random.nextFloat() * (Math.PI * 2).toFloat(),
                speed = 0.0008f + Random.nextFloat() * 0.0015f
            )
        }
    }

    /** Draws stars whose seeded y falls above [maxY] (default: all of them),
     *  every [stride]-th star only (for a sparser look), tinted by [tint]
     *  instead of pure white. */
    private fun drawStars(canvas: Canvas, maxY: Float = Float.MAX_VALUE, stride: Int = 1, tint: Int = Color.WHITE) {
        stars.forEachIndexed { i, s ->
            if (stride > 1 && i % stride != 0) return@forEachIndexed
            if (s.y > maxY) return@forEachIndexed
            val twinkle = 0.5f + 0.5f * sin(s.phase + elapsedMs * s.speed)
            starPaint.color = tint
            starPaint.alpha = (100 + twinkle * 155).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.r, starPaint)
        }
    }

    /** Moon disc + soft radial glow at an arbitrary position -- the glow
     *  shader is cached by position/radius/color so it's only rebuilt when
     *  one of those actually changes, not on every frame. [glowColor] lets
     *  the moon's mood be tuned per scene (default: cool cyan). */
    private fun drawMoon(canvas: Canvas, moonX: Float, moonY: Float, moonR: Float, glowColor: String = "#40E9EEFF") {
        val glowKey = "$moonX:$moonY:$moonR:$glowColor"
        if (moonGlowKey != glowKey) {
            moonGlowPaint.shader = RadialGradient(
                moonX, moonY, moonR * 4.2f,
                Color.parseColor(glowColor), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            moonGlowKey = glowKey
        }
        canvas.drawCircle(moonX, moonY, moonR * 4.2f, moonGlowPaint)
        canvas.drawCircle(moonX, moonY, moonR, moonPaint)
    }

    private fun drawNightSky(canvas: Canvas) {
        drawBackground(canvas, "#050912", "#101B33")
        drawStars(canvas)
        drawMoon(canvas, width * 0.78f, height * 0.2f, minOf(width, height) * 0.07f)
    }

    // --- Starfield (deep space) -----------------------------------------------
    // A dense, mostly-static speckle of tiny stars on pure black -- no moon,
    // no gradient, no horizon -- plus a pool of short "glints" that
    // continuously spawn, fade in, fade out, and vanish, then reappear after
    // a random pause at a brand new random position. Modeled on the
    // reference recording: the base star dots barely move, but the little
    // streak-like flickers are constantly appearing/disappearing in
    // different spots rather than sitting still in one place.
    //
    // Frame-by-frame analysis of the reference recording (stacking frames
    // and fitting lines through the transient bright streaks) showed those
    // streaks are not randomly angled -- each one's direction lines up with
    // the ray from the screen's center through its own position (~2-3
    // degrees of error on average, far tighter than chance). It reads as a
    // gentle "flying through a starfield" drift: every glint is a little
    // piece of outward radial motion away from the center of the view,
    // not an arbitrary diagonal twinkle. respawnGlint() below computes each
    // glint's angle from its own position for exactly that reason.
    //
    // Each glint also physically drifts along that outward heading while it is
    // visible, at the same px/second range as Rain's drops (FLOW_SPEED_MIN/SPAN),
    // so Starfield and Rain share one consistent, calm motion speed.

    private class Speck(val x: Float, val y: Float, val r: Float, val baseAlpha: Int, val phase: Float, val speed: Float)
    private class Glint(
        var x: Float = 0f, var y: Float = 0f,
        var dx: Float = 0f, var dy: Float = 0f,   // the streak itself (length + direction)
        var ux: Float = 0f, var uy: Float = 0f,   // unit heading it drifts along
        var speed: Float = 0f,                    // px/second, same range as Rain
        var peakAlpha: Int = 0,
        var age: Float = 0f,      // ms since this cycle started; negative = still waiting to appear
        var lifespan: Float = 0f  // ms the glint stays visible once age >= 0
    )
    private val specks = mutableListOf<Speck>()
    private val glints = mutableListOf<Glint>()
    private val speckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
    }

    private fun seedStarfield() {
        specks.clear()
        val speckCount = ((width * height) / 2800f).toInt().coerceIn(180, 480)
        repeat(speckCount) {
            specks += Speck(
                x = Random.nextFloat() * width,
                y = Random.nextFloat() * height,
                r = 0.6f + Random.nextFloat() * 1.1f,
                baseAlpha = 110 + Random.nextInt(121),
                phase = Random.nextFloat() * (Math.PI * 2).toFloat(),
                speed = 0.0004f + Random.nextFloat() * 0.0008f // slow, faint sparkle
            )
        }

        glints.clear()
        // A modest pool of slots reused over time -- each one independently
        // cycles through wait -> fade in -> fade out -> wait again, so at
        // any given moment only some of them are visible, at ever-changing
        // positions (never in the same spot twice in a row).
        val glintSlots = ((width * height) / 42000f).toInt().coerceIn(10, 20)
        repeat(glintSlots) {
            val g = Glint()
            respawnGlint(g)
            // stagger first cycle so slots don't all light up in sync
            g.age = Random.nextFloat() * (g.lifespan + 1200f) - 1200f
            glints += g
        }
    }

    /** Rolls a brand new position/length/angle/duration for one glint slot
     *  and puts it into its "waiting" phase (age < 0) for a random pause
     *  before it becomes visible again. */
    private fun respawnGlint(g: Glint) {
        val len = 24f + Random.nextFloat() * 60f
        // Spawn biased toward the outer area (away from the centre) so the
        // streaks fly out toward the screen edges instead of clustering mid-screen.
        val cx = width / 2f
        val cy = height / 2f
        val maxR = hypot(cx.toDouble(), cy.toDouble()).toFloat().coerceAtLeast(1f)
        var tries = 0
        do {
            g.x = Random.nextFloat() * width
            g.y = Random.nextFloat() * height
            tries++
        } while (tries < 8 && hypot((g.x - cx).toDouble(), (g.y - cy).toDouble()).toFloat() / maxR < 0.45f)
        // Radial angle: straight out from the center of the view through
        // this glint's own position, so it reads as a brief piece of outward
        // motion (like drifting past a star) rather than a random diagonal.
        // A few degrees of jitter keeps a whole frame of glints from looking
        // like a perfectly mechanical starburst.
        val baseAngle = atan2((g.y - cy).toDouble(), (g.x - cx).toDouble())
        val jitter = Math.toRadians((Random.nextFloat() * 12f - 6f).toDouble())
        val angle = baseAngle + jitter
        g.ux = cos(angle).toFloat()
        g.uy = sin(angle).toFloat()
        g.dx = len * g.ux
        g.dy = len * g.uy
        // Drifts outward at the same px/s as Rain's drops (FLOW_SPEED_*).
        g.speed = FLOW_SPEED_MIN + Random.nextFloat() * FLOW_SPEED_SPAN
        g.peakAlpha = 120 + Random.nextInt(116)
        g.lifespan = 650f + Random.nextFloat() * 600f // visible for ~0.5-1.0s so the drift reads
        g.age = -(150f + Random.nextFloat() * 950f)   // then hidden for ~0.15-1.1s
    }

    private fun advanceStarfield(deltaMs: Long) {
        for (g in glints) {
            g.age += deltaMs
            if (g.age > g.lifespan) respawnGlint(g)
        }
    }

    private fun drawStarfield(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        for (s in specks) {
            val twinkle = 0.8f + 0.2f * sin(s.phase + elapsedMs * s.speed)
            speckPaint.alpha = (s.baseAlpha * twinkle).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.r, speckPaint)
        }
        for (g in glints) {
            if (g.age < 0f || g.age > g.lifespan) continue // currently waiting -- invisible
            val t = g.age / g.lifespan
            // quick fade in, brief hold, slightly longer fade out
            val envelope = when {
                t < 0.25f -> t / 0.25f
                t > 0.6f -> ((1f - t) / 0.4f).coerceAtLeast(0f)
                else -> 1f
            }
            streakPaint.alpha = (g.peakAlpha * envelope).toInt().coerceIn(0, 255)
            val travelled = g.speed * (g.age / 1000f) // px moved since it appeared
            val sx = g.x + g.ux * travelled
            val sy = g.y + g.uy * travelled
            canvas.drawLine(sx, sy, sx + g.dx, sy + g.dy, streakPaint)
        }
    }

    // --- shared helpers ------------------------------------------------------

    private val bgPaint = Paint()
    private var bgShaderKey: String? = null

    /** Caches the vertical gradient Shader per (style, size) so it's only
     *  rebuilt when the color pair or the view's dimensions actually change,
     *  instead of allocating a new Shader on every single frame. */
    private fun drawBackground(canvas: Canvas, topColor: String, bottomColor: String) {
        val key = "$topColor:$bottomColor:$width:$height"
        if (bgShaderKey != key) {
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.parseColor(topColor), Color.parseColor(bottomColor),
                Shader.TileMode.CLAMP
            )
            bgShaderKey = key
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        if (!seeded) seedAll()
        when (style) {
            Style.RAIN -> drawRain(canvas)
            Style.NIGHT_SKY -> drawNightSky(canvas)
            Style.STARFIELD -> drawStarfield(canvas)
        }
    }
}
