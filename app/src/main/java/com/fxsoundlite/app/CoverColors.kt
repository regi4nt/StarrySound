package com.fxsoundlite.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import java.lang.ref.WeakReference

/**
 * "Material You"-style accent taken from the cover of the song that is playing.
 * No Palette dependency: the cover is shrunk to 32x32, pixels are grouped into 36 hue bins and the
 * bin with the most vivid, bright colour wins. The result is then tuned so it stays readable in the
 * current light / dark mode.
 */
object CoverColors {

    private var lastBmp: WeakReference<Bitmap>? = null
    private var lastRaw: Int? = null

    fun isNight(c: Context): Boolean =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Accent for the cover that is actually on screen, or null when the feature is off / that cover has no
     * usable colour (grey, black&white...). A custom player cover (CoverUi) wins over the song's artwork, so
     * the colours then stay steady and match the picture shown -- and need no artwork, so data saver is fine.
     */
    fun accentFor(c: Context, songArt: Bitmap?): Int? {
        if (!MusicPrefs.coverTheme(c)) return null
        val bmp = CoverUi.custom(c) ?: (if (MusicPrefs.saver(c)) null else songArt)
        if (bmp == null || bmp.isRecycled) return null
        val raw: Int? = synchronized(this) {
            if (lastBmp?.get() === bmp) lastRaw
            else extract(bmp).also { lastBmp = WeakReference(bmp); lastRaw = it }
        }
        return raw?.let { tune(it, isNight(c)) }
    }

    /** Accent of one specific picture (e.g. an opened playlist's cover); null when the feature is off or it has no colour. */
    fun accentOf(c: Context, bmp: Bitmap?): Int? {
        if (!MusicPrefs.coverTheme(c) || bmp == null || bmp.isRecycled) return null
        return extract(bmp)?.let { tune(it, isNight(c)) }
    }

    private fun extract(src: Bitmap): Int? {
        val n = 32
        val px = IntArray(n * n)
        try {
            // Centre crop: YouTube thumbnails often carry black bars (top / bottom) that would only add "near-black".
            val dx = (src.width * 0.08f).toInt()
            val dy = (src.height * 0.14f).toInt()
            val cw = src.width - 2 * dx
            val ch = src.height - 2 * dy
            val crop = if (cw >= 8 && ch >= 8) Bitmap.createBitmap(src, dx, dy, cw, ch) else src
            val small = Bitmap.createScaledBitmap(crop, n, n, true)
            small.getPixels(px, 0, n, 0, 0, n, n)
            if (small !== crop) small.recycle()
            if (crop !== src) crop.recycle()
        } catch (e: Exception) {
            return null // hardware bitmap or similar: keep the normal accent
        }
        // Vivid covers (album art) are decided by the strict pass. Video thumbnails (faces, dark scenes, washed-out
        // frames) often have no colour that strong, so a second, relaxed pass still picks their dominant hue.
        return dominant(px, minSat = 0.25f, minVal = 0.18f, minMass = 0.02f)
            ?: dominant(px, minSat = 0.09f, minVal = 0.12f, minMass = 0.01f)
    }

    private fun dominant(px: IntArray, minSat: Float, minVal: Float, minMass: Float): Int? {
        val bins = 36
        val w = FloatArray(bins)
        val sumH = FloatArray(bins)
        val sumS = FloatArray(bins)
        val sumV = FloatArray(bins)
        val hsv = FloatArray(3)
        for (p in px) {
            if (Color.alpha(p) < 200) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < minSat || hsv[2] < minVal) continue // greys and near-black say nothing
            val weight = hsv[1] * (0.4f + 0.6f * hsv[2])
            val b = (hsv[0] / 10f).toInt().coerceIn(0, bins - 1)
            w[b] += weight
            sumH[b] += hsv[0] * weight
            sumS[b] += hsv[1] * weight
            sumV[b] += hsv[2] * weight
        }
        var best = -1
        var bestW = 0f
        for (i in 0 until bins) if (w[i] > bestW) { bestW = w[i]; best = i }
        if (best < 0 || bestW < px.size * minMass) return null // practically a grey cover
        return Color.HSVToColor(floatArrayOf(sumH[best] / bestW, sumS[best] / bestW, sumV[best] / bestW))
    }

    /** Bright + soft on dark backgrounds, deeper on light ones, so text / icons on it stay legible. */
    private fun tune(raw: Int, night: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(raw, hsv)
        val s = hsv[1]
        if (night) {
            // A washed-out cover (s < 0.35) keeps a gentler tint, but never less than a visible one.
            hsv[1] = s.coerceIn(minOf(0.35f, maxOf(s * 2f, 0.3f)), 0.85f)
            hsv[2] = hsv[2].coerceIn(0.75f, 1f)
        } else {
            hsv[1] = s.coerceIn(minOf(0.5f, maxOf(s * 2f, 0.35f)), 0.95f)
            hsv[2] = hsv[2].coerceIn(0.45f, 0.72f)
        }
        return Color.HSVToColor(hsv)
    }

    /** Black or white, whichever reads better on [bg]. */
    fun onColor(bg: Int): Int = if (ColorUtils.calculateLuminance(bg) > 0.45) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    /** [base] with a light wash of [accent] (the tinted player background). */
    fun wash(base: Int, accent: Int, amount: Float): Int = ColorUtils.blendARGB(base, accent, amount)

    /** Page background tinted by the cover: clearly visible, yet still dark / light enough for the normal text colours. */
    fun pageBg(c: Context, accent: Int): Int =
        wash(themeColor(c, R.attr.colorBg), accent, if (isNight(c)) 0.34f else 0.26f)

    /** Mini player bar background: a bit stronger than [pageBg] so it stands out from the page behind it. */
    fun barBg(c: Context, accent: Int): Int =
        wash(themeColor(c, R.attr.colorBg), accent, if (isNight(c)) 0.5f else 0.4f)

    /** Cover accent of the song that is playing right now (null: feature off / no usable colour). */
    fun currentAccent(c: Context): Int? = accentFor(c, MusicService.instance?.artwork)

    fun themeColor(c: Context, attr: Int): Int {
        val tv = TypedValue()
        c.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
