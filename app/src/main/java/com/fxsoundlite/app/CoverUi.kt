package com.fxsoundlite.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.TypedValue
import android.widget.ImageView
import java.io.File

/**
 * Cover art for the mini player and the full player. A custom cover photo picked in Settings
 * (see [setCustom]) wins over everything. Otherwise, in saver mode no cover is shown at all (same
 * as the song list): a plain music-note placeholder is drawn instead, which also saves the data and
 * work of fetching / decoding artwork.
 */
object CoverUi {
    private const val FILE_NAME = "custom_player_cover.jpg"
    private const val MAX_SIDE = 1024

    // Decoded copy kept in memory; reloaded when the file's timestamp changes.
    private var cached: Bitmap? = null
    private var cachedStamp = -1L

    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE_NAME)

    fun hasCustom(c: Context): Boolean = file(c).exists()

    /** The user's custom cover, or null when none is set. */
    fun custom(c: Context): Bitmap? {
        val f = file(c)
        if (!f.exists()) { cached = null; cachedStamp = -1L; return null }
        if (cached == null || cachedStamp != f.lastModified()) {
            cached = try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null }
            cachedStamp = f.lastModified()
        }
        return cached
    }

    /** Copies the picked photo into app storage (downscaled), so it keeps working without any
     *  persistable Uri grant. Returns false when the photo can't be read. */
    fun setCustom(c: Context, uri: Uri): Boolean {
        return try {
            val resolver = c.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            val tmp = File(file(c).parentFile, "$FILE_NAME.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!tmp.renameTo(file(c))) { tmp.delete(); return false }
            cached = bmp
            cachedStamp = file(c).lastModified()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearCustom(c: Context) {
        file(c).delete()
        cached = null
        cachedStamp = -1L
    }

    /** True while the song's own thumbnail is shown instead of the custom cover (toggled by tapping the
     *  cover in the full player). Shared by the full player and the mini player; in memory, so it
     *  resets to the custom cover when the app process restarts. */
    @Volatile var showThumb = false

    // Where the full player really draws its cover (screen y of its top edge + square size, in px). The Quick page
    // copies it so both covers sit at exactly the same place. Saved, so it also works right after a restart.
    private const val GEO_PREFS = "cover_geo"
    @Volatile private var geoTop = -1
    @Volatile private var geoSize = -1

    fun saveFullGeometry(c: Context, topOnScreen: Int, size: Int) {
        if (size <= 0 || (topOnScreen == geoTop && size == geoSize)) return
        geoTop = topOnScreen; geoSize = size
        c.applicationContext.getSharedPreferences(GEO_PREFS, Context.MODE_PRIVATE)
            .edit().putInt("top", topOnScreen).putInt("size", size).apply()
    }

    /** (top on screen, size) of the full player's cover, or null until the full player has been opened once. */
    fun fullGeometry(c: Context): Pair<Int, Int>? {
        if (geoSize <= 0) {
            val p = c.applicationContext.getSharedPreferences(GEO_PREFS, Context.MODE_PRIVATE)
            geoTop = p.getInt("top", -1); geoSize = p.getInt("size", -1)
        }
        return if (geoSize > 0 && geoTop >= 0) geoTop to geoSize else null
    }

    /** True when tapping the cover can switch to the thumbnail: a custom cover is set and a thumbnail exists. */
    fun canToggle(c: Context, songArt: Bitmap?): Boolean = hasCustom(c) && songArt != null && !MusicPrefs.saver(c)

    /** The picture to show: the thumbnail while [thumb] is on (and one is available), else custom, else artwork. */
    fun pick(c: Context, art: Bitmap?, saver: Boolean, thumb: Boolean): Bitmap? =
        if (thumb && !saver && art != null && hasCustom(c)) art
        else custom(c) ?: if (!saver) art else null

    fun apply(view: ImageView, art: Bitmap?, saver: Boolean, placeholderPadDp: Int, thumb: Boolean = showThumb) {
        val shown = pick(view.context, art, saver, thumb)
        if (shown != null) {
            view.setPadding(0, 0, 0, 0)
            view.imageTintList = null
            view.setImageBitmap(shown)
            return
        }
        val pad = (placeholderPadDp * view.resources.displayMetrics.density).toInt()
        view.setPadding(pad, pad, pad, pad)
        view.setImageResource(R.drawable.ic_music)
        val tv = TypedValue()
        view.context.theme.resolveAttribute(R.attr.colorTextSecondary, tv, true)
        view.imageTintList = ColorStateList.valueOf(tv.data)
    }
}
