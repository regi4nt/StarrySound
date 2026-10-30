package com.fxsoundlite.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Sedikit area neon (cahaya pendek berekor) yang berputar mengelilingi cover kotak di kartu player. View ini diletakkan di belakang
 * cover (satu parent, lebih besar dari frame); bentuk cover dibaca dari [target].
 * Berdenyut pelan lewat [advance] (frame loop di NowPlayingActivity); tampil hanya saat [glowOn].
 */
class CoverWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** View cover yang dikelilingi gelombang. */
    var target: View? = null
    var waveColor: Int = 0xFFFFFFFF.toInt()
        set(v) { field = v; paint.color = v }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = waveColor
    }
    /** Corner radius of the square cover (px); the waves keep it plus their own growth, so every
     *  ring stays concentric with the cover's rounded corners. Set by NowPlayingActivity. */
    var cornerRadius: Float = 0f

    private val rect = RectF()
    private val path = Path()
    private val seg = Path()
    private val measure = PathMeasure()

    private fun drawSegment(canvas: Canvas, start: Float, end: Float, len: Float) {
        // The outline is a closed loop: a piece that crosses the start point is drawn in two parts.
        val s0 = ((start % len) + len) % len
        val e0 = s0 + (end - start)
        seg.rewind()
        if (e0 <= len) {
            measure.getSegment(s0, e0, seg, true)
        } else {
            measure.getSegment(s0, len, seg, true)
            measure.getSegment(0f, e0 - len, seg, true)
        }
        canvas.drawPath(seg, paint)
    }
    private var phase = 0f
    /** Neon shown only for the square cover with animation on. */
    var glowOn: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Advances the waves by [dtSec] seconds (one lap around the cover takes [LAP_SECONDS]) and redraws.
     *  Driven by the caller's frame loop instead of a ValueAnimator, so it keeps working when the
     *  system "animator duration scale" is 0 or battery saver disables animators. */
    fun advance(dtSec: Float) {
        phase = (phase + dtSec / LAP_SECONDS) % 1f
        invalidate()
    }

    fun reset() {
        phase = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = target ?: return
        if (!glowOn || t.width == 0 || t.visibility != VISIBLE) return
        // Ruang maksimum gelombang = jarak cover ke tepi view terdekat.
        // Cover bounds in this view's own coordinates (this view may extend past the frame).
        val cl = (t.left - left).toFloat(); val ct = (t.top - top).toFloat()
        val cr = (t.right - left).toFloat(); val cb = (t.bottom - top).toFloat()
        // Neon only on a SHORT stretch: a light "comet" runs around the cover's rounded outline,
        // a bright head with a tail that fades out; the rest of the border stays dark. The tail is
        // cut into slices of falling brightness, each drawn as a glow (wide + faint) plus a thin core.
        val offset = 2f * density
        rect.set(cl - offset, ct - offset, cr + offset, cb + offset)
        val r = cornerRadius + offset
        path.rewind()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        measure.setPath(path, true)
        val len = measure.length
        if (len <= 0f) return
        val head = phase * len
        val tailLen = len * TAIL_FRACTION
        val sliceLen = tailLen / SLICES
        for (j in 0 until SLICES) {
            val end = head - j * sliceLen
            val start = end - sliceLen * 1.02f // tiny overlap so slices join without gaps
            val f = 1f - j.toFloat() / SLICES
            val k = f * f
            for (pass in 0 until 3) {
                paint.strokeWidth = (when (pass) { 0 -> 16f; 1 -> 9f; else -> 2.5f }) * density
                paint.alpha = (k * when (pass) { 0 -> 38f; 1 -> 80f; else -> 255f }).toInt()
                drawSegment(canvas, start, end, len)
            }
        }
    }

    private companion object {
        const val LAP_SECONDS = 10f
        const val TAIL_FRACTION = 0.45f // length of the lit stretch, as a share of the outline
        const val SLICES = 28 // more slices for the longer tail, so the fade stays smooth
    }
}
