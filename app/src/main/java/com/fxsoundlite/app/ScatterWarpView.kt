package com.fxsoundlite.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * "Scatter" for a photo / video background: while the finger is down the picture's texture is pushed away from it
 * (a mesh warp, the same push as the particles of the animated backgrounds) and eases back after release.
 * It draws a center-cropped copy of the background over it and hides itself again once everything is back in place.
 * Feed it with [setBitmap] (the still / a video frame) and [setPush]; it never takes touches.
 * [shake] is the "Shake" touch effect: the whole texture vibrates for a moment (a damped, position-dependent wobble
 * of the mesh, strongest around the finger) and then settles.
 */
class ScatterWarpView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val SHAKE_MS = 600f
        const val COLS = 22
        const val ROWS = 34
    }

    private var source: Bitmap? = null
    private var cropped: Bitmap? = null
    private val base = FloatArray((COLS + 1) * (ROWS + 1) * 2)
    private val verts = FloatArray(base.size)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var pushX = 0f
    private var pushY = 0f
    private var now = 0f      // eased 0..1
    private var target = 0f   // 1 while touching
    private var lastFrame = 0L

    private var shakeAt = 0L        // uptime of the last shake trigger, 0 = none
    private var shakeStrength = 0f
    private var shakeX = 0f
    private var shakeY = 0f

    fun hasBitmap(): Boolean = source != null

    fun setBitmap(b: Bitmap?) {
        source = b
        cropped = null
        now = 0f
        lastFrame = 0L
        shakeAt = 0L
        rebuild()
    }

    /** Vibrates the texture (needs a bitmap). [strength] 0..1; (x, y) = where the finger is, the wobble is strongest there. */
    fun shake(x: Float, y: Float, strength: Float) {
        if (source == null) return
        shakeAt = SystemClock.uptimeMillis()
        shakeStrength = strength
        shakeX = x; shakeY = y
        invalidate()
    }

    fun setPush(x: Float, y: Float, active: Boolean) {
        pushX = x; pushY = y
        target = if (active) 1f else 0f
        if (source != null) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    /** Center-crop the source to the view's aspect ratio (like the ImageView / video under it) and lay out the mesh. */
    private fun rebuild() {
        val s = source ?: return
        if (width <= 0 || height <= 0) return
        val viewAspect = width.toFloat() / height
        val srcAspect = s.width.toFloat() / s.height
        val cw: Int
        val ch: Int
        if (srcAspect > viewAspect) { ch = s.height; cw = (s.height * viewAspect).toInt().coerceIn(1, s.width) }
        else { cw = s.width; ch = (s.width / viewAspect).toInt().coerceIn(1, s.height) }
        cropped = try {
            Bitmap.createBitmap(s, (s.width - cw) / 2, (s.height - ch) / 2, cw, ch)
        } catch (e: Exception) { null }
        var i = 0
        for (r in 0..ROWS) for (c in 0..COLS) {
            base[i++] = width * c / COLS.toFloat()
            base[i++] = height * r / ROWS.toFloat()
        }
    }

    private fun finish() {
        source = null
        cropped = null
        lastFrame = 0L
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = cropped ?: return
        val t = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16L else (t - lastFrame).coerceIn(0L, 64L)
        lastFrame = t
        // In quickly under the finger, back out slower after release.
        now += (target - now) * (dt / (if (target > 0f) 80f else 170f)).coerceAtMost(1f)
        val shakeT = if (shakeAt == 0L) 2f else (t - shakeAt) / SHAKE_MS
        val shaking = shakeT in 0f..1f
        if (target == 0f && now < 0.01f && !shaking) { finish(); return }
        val dens = resources.displayMetrics.density
        val tau = (2.0 * Math.PI).toFloat()
        val shakeK = if (shaking) shakeStrength * (1f - shakeT) * kotlin.math.sqrt(1f - shakeT) else 0f
        val amp = 6f * dens * shakeK
        val wr = min(width, height) * 0.6f

        val r = min(width, height) * 0.3f
        var i = 0
        while (i < base.size) {
            val x = base[i]; val y = base[i + 1]
            val dx = x - pushX; val dy = y - pushY
            val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (d >= r || d < 0.5f) { verts[i] = x; verts[i + 1] = y }
            else {
                val f = 1f - d / r
                val k = f * f * r * 0.6f * now
                verts[i] = x + dx / d * k
                verts[i + 1] = y + dy / d * k
            }
            if (shaking) {
                val idx = i / 2
                val c = idx % (COLS + 1); val rw = idx / (COLS + 1)
                if (c != 0 && c != COLS && rw != 0 && rw != ROWS) { // the border stays put so no gaps show
                    val near = 1f - (hypot((x - shakeX).toDouble(), (y - shakeY).toDouble()).toFloat() / wr).coerceIn(0f, 1f)
                    val a = amp * (0.45f + 0.55f * near)
                    verts[i] += sin(shakeT * tau * 13f + y * 0.025f) * a
                    verts[i + 1] += sin(shakeT * tau * 17f + x * 0.025f + 1.3f) * a * 0.75f
                }
            }
            i += 2
        }
        canvas.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, null, 0, paint)
        postInvalidateOnAnimation()
    }
}
