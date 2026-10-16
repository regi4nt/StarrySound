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

    fun apply(view: ImageView, art: Bitmap?, saver: Boolean, placeholderPadDp: Int) {
        val shown = custom(view.context) ?: if (!saver) art else null
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
