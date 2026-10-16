package com.fxsoundlite.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Random

/**
 * Spotify-style share card: a 1080x1920 PNG with a gradient taken from the cover colour, the cover in
 * a big rounded square, title + artist, a "sound code" bar strip and the app name. It is shared as an
 * image together with the song link as text.
 */
object SongShareCard {
    private const val W = 1080
    private const val H = 1920

    /**
     * [art] = the cover already in memory (may be null); the track's own artUrl is downloaded when it is.
     * Falls back to sharing plain text if the image cannot be made.
     */
    fun share(activity: Activity, t: Track, art: Bitmap?, link: String, chooserTitle: String) {
        val app = activity.applicationContext
        val name = if (t.artist.isBlank()) t.title else "${t.title} - ${QuickFeed.cleanArtist(t.artist)}"
        val text = listOf(name, link).filter { it.isNotBlank() }.joinToString("\n")
        Thread {
            val file = try {
                val cover = art?.takeIf { !it.isRecycled }
                    ?: t.artUrl.takeIf { it.isNotBlank() }?.let { Http.bitmap(YouTubeApi.upgradeArt(it)) }
                render(app, t, cover)
            } catch (e: Exception) { null }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
                if (file != null) {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                    send.setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri("", uri)
                } else {
                    send.setType("text/plain")
                }
                activity.startActivity(Intent.createChooser(send, chooserTitle))
            }
        }.start()
    }

    private fun render(ctx: Context, t: Track, cover: Bitmap?): File {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val accent = accentOf(cover)

        // Background: cover colour fading into near-black, like Spotify's cards.
        p.shader = LinearGradient(
            0f, 0f, 0f, H.toFloat(),
            intArrayOf(accent, darken(accent, 0.55f), 0xFF121212.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = null

        // Cover: big rounded square with a soft shadow.
        val size = 840f
        val left = (W - size) / 2f
        val top = 280f
        val rect = RectF(left, top, left + size, top + size)
        val radius = 36f
        p.color = Color.BLACK
        p.setShadowLayer(70f, 0f, 28f, 0x99000000.toInt())
        c.drawRoundRect(rect, radius, radius, p)
        p.clearShadowLayer()
        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        if (cover != null) {
            c.drawBitmap(cover, squareCrop(cover), rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            p.color = lighten(accent, 0.25f)
            c.drawRect(rect, p)
            val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66FFFFFF; textSize = 380f; textAlign = Paint.Align.CENTER
            }
            c.drawText("\u266A", rect.centerX(), rect.centerY() + 130f, note)
        }
        c.restore()

        // Title + artist, left-aligned under the cover.
        val textLeft = left
        val textWidth = size.toInt()
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 66f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleLayout = StaticLayout.Builder.obtain(t.title, 0, t.title.length, title, textWidth)
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        var y = top + size + 80f
        c.save(); c.translate(textLeft, y); titleLayout.draw(c); c.restore()
        y += titleLayout.height + 14f

        val artistText = QuickFeed.cleanArtist(t.artist)
        if (artistText.isNotBlank()) {
            val artist = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 44f }
            val line = TextUtils.ellipsize(artistText, artist, textWidth.toFloat(), TextUtils.TruncateAt.END).toString()
            c.drawText(line, textLeft, y + 44f, artist)
        }

        // "Sound code": a strip of rounded bars, deterministic per song.
        val rnd = Random(t.key.hashCode().toLong())
        val bars = 38
        val barW = 12f
        val gap = (size - bars * barW) / (bars - 1)
        val baseY = 1640f
        p.color = Color.WHITE
        for (i in 0 until bars) {
            val h = 36f + rnd.nextInt(130)
            val x = left + i * (barW + gap)
            c.drawRoundRect(RectF(x, baseY - h / 2f, x + barW, baseY + h / 2f), barW / 2f, barW / 2f, p)
        }

        // App name at the bottom.
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 46f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.12f
        }
        c.drawText("\u266A  StarrySound", W / 2f, 1820f, brand)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest card is kept
        val file = File(dir, "song_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    /** Centre square of the cover; YouTube's 4:3 thumbnails carry black bars, so those are cut away too. */
    private fun squareCrop(b: Bitmap): android.graphics.Rect {
        val ratio = b.width / b.height.toFloat()
        val side = if (ratio in 1.3f..1.4f) (b.height * 0.75f).toInt() else minOf(b.width, b.height)
        val x = (b.width - side) / 2
        val y = (b.height - side) / 2
        return android.graphics.Rect(x, y, x + side, y + side)
    }

    /** Average colour of the cover, pushed toward a rich, fairly dark tone so white text stays readable. */
    private fun accentOf(cover: Bitmap?): Int {
        if (cover == null) return 0xFF3A2D6B.toInt()
        val avg = try { Bitmap.createScaledBitmap(cover, 1, 1, true).getPixel(0, 0) } catch (e: Exception) { return 0xFF3A2D6B.toInt() }
        val hsv = FloatArray(3)
        Color.colorToHSV(avg, hsv)
        hsv[1] = if (hsv[1] < 0.08f) 0f else (hsv[1] * 1.25f).coerceIn(0.35f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.38f, 0.62f)
        return Color.HSVToColor(hsv)
    }

    private fun darken(c: Int, f: Float): Int =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    private fun lighten(c: Int, f: Float): Int =
        Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
            (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
            (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
        )
}
