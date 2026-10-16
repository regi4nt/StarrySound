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

/**
 * Spotify-Wrapped-style share card: a 1080x1920 PNG with a bold gradient, the period, the total listening
 * time, the top song (with its cover when there is one) and the top artists / songs in two columns.
 * It is shared as an image together with the recap text. All texts arrive already localized in [Data].
 */
object WrappedShareCard {
    private const val W = 1080
    private const val H = 1920
    private const val MARGIN = 72f

    private const val LIME = 0xFFC6FF3D.toInt()
    private const val INK = 0xFF1B1464.toInt()

    class Data(
        val period: String,
        val listened: String,
        val duration: String,
        val plays: String,
        val artistsTitle: String,
        val songsTitle: String,
        val topSongTitle: String,
        /** name to count text */
        val artists: List<Pair<String, String>>,
        /** title, artist, count text */
        val songs: List<Triple<String, String, String>>,
        val footer: String,
        /** Cover of the top song; may be blank. */
        val coverUrl: String
    )

    /** Falls back to sharing plain text if the image cannot be made. */
    fun share(activity: Activity, d: Data, text: String, chooserTitle: String) {
        val app = activity.applicationContext
        Thread {
            val file = try {
                val cover = d.coverUrl.takeIf { it.isNotBlank() }?.let { Http.bitmap(YouTubeApi.upgradeArt(it)) }
                render(app, d, cover)
            } catch (e: Throwable) { null }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
                if (file != null) {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                    send.setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, d.period)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri("", uri)
                } else {
                    send.setType("text/plain")
                }
                activity.startActivity(Intent.createChooser(send, chooserTitle))
            }
        }.start()
    }

    private fun tp(size: Float, color: Int = Color.WHITE, bold: Boolean = false, spacing: Float = 0f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = spacing
        }

    /** Shrinks [paint] until [text] fits in [maxW]. */
    private fun fit(text: String, paint: TextPaint, maxW: Float) {
        while (paint.measureText(text) > maxW && paint.textSize > 24f) paint.textSize -= 4f
    }

    private fun line(text: String, paint: TextPaint, maxW: Float): String =
        TextUtils.ellipsize(text, paint, maxW, TextUtils.TruncateAt.END).toString()

    private fun render(ctx: Context, d: Data, cover: Bitmap?): File {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val contentW = W - 2 * MARGIN

        // Background: indigo into violet into magenta, with a few soft discs.
        p.shader = LinearGradient(
            0f, 0f, W.toFloat(), H.toFloat(),
            intArrayOf(INK, 0xFF6A2BD9.toInt(), 0xFFE0218A.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = null
        p.color = 0x18FFFFFF
        c.drawCircle(940f, 240f, 330f, p)
        c.drawCircle(100f, 1560f, 430f, p)
        p.color = 0x22C6FF3D
        c.drawCircle(1010f, 1150f, 190f, p)

        // Brand + title + period.
        c.drawText("\u266A  StarrySound", MARGIN, 130f, tp(40f, 0xE6FFFFFF.toInt(), bold = true, spacing = 0.1f))
        val title = tp(170f, Color.WHITE, bold = true, spacing = 0.02f)
        fit("WRAPPED", title, contentW)
        c.drawText("WRAPPED", MARGIN, 310f, title)

        val periodPaint = tp(40f, INK, bold = true)
        val period = line(d.period.uppercase(), periodPaint, contentW - 64f)
        val pillW = periodPaint.measureText(period) + 64f
        p.color = LIME
        c.drawRoundRect(RectF(MARGIN, 350f, MARGIN + pillW, 424f), 37f, 37f, p)
        c.drawText(period, MARGIN + 32f, 402f, periodPaint)

        // Total listening time.
        c.drawText(line(d.listened, tp(44f), contentW), MARGIN, 530f, tp(44f, 0xD9FFFFFF.toInt()))
        val dur = tp(150f, LIME, bold = true)
        fit(d.duration, dur, contentW)
        c.drawText(d.duration, MARGIN, 530f + 40f + dur.textSize * 0.8f, dur)
        val playsPaint = tp(38f, 0xCCFFFFFF.toInt())
        c.drawText(line(d.plays, playsPaint, contentW), MARGIN, 530f + 40f + dur.textSize * 0.8f + 64f, playsPaint)

        // Top song: cover + title + artist.
        var y0 = 790f
        val top = d.songs.firstOrNull()
        if (top != null) {
            val size = 260f
            val rect = RectF(MARGIN, y0, MARGIN + size, y0 + size)
            p.color = Color.BLACK
            p.setShadowLayer(50f, 0f, 20f, 0x99000000.toInt())
            c.drawRoundRect(rect, 32f, 32f, p)
            p.clearShadowLayer()
            c.save()
            c.clipPath(Path().apply { addRoundRect(rect, 32f, 32f, Path.Direction.CW) })
            if (cover != null) {
                c.drawBitmap(cover, squareCrop(cover), rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } else {
                p.color = 0x33FFFFFF
                c.drawRect(rect, p)
                val note = tp(180f, 0x88FFFFFF.toInt()).apply { textAlign = Paint.Align.CENTER }
                c.drawText("\u266A", rect.centerX(), rect.centerY() + 62f, note)
            }
            c.restore()

            val tx = MARGIN + size + 40f
            val tw = (W - MARGIN - tx)
            c.drawText(line(d.topSongTitle.uppercase(), tp(30f, LIME, true, 0.1f), tw), tx, y0 + 52f, tp(30f, LIME, true, 0.1f))
            val tpaint = tp(54f, Color.WHITE, bold = true)
            val layout = StaticLayout.Builder.obtain(top.first, 0, top.first.length, tpaint, tw.toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            c.save(); c.translate(tx, y0 + 76f); layout.draw(c); c.restore()
            val sub = if (top.second.isBlank()) top.third else "${top.second} \u00B7 ${top.third}"
            val subPaint = tp(36f, 0xCCFFFFFF.toInt())
            c.drawText(line(sub, subPaint, tw), tx, y0 + 76f + layout.height + 52f, subPaint)
            y0 += size + 70f
        }

        // Two columns: top artists | top songs.
        val colW = 440f
        val leftX = MARGIN
        val rightX = W - MARGIN - colW
        val rankW = 62f
        val textW = colW - rankW
        val rowH = 100f
        val rowsTop = y0 + 78f

        fun header(text: String, x: Float) {
            val hp = tp(32f, LIME, bold = true, spacing = 0.08f)
            c.drawText(line(text.uppercase(), hp, colW), x, y0 + 34f, hp)
        }
        header(d.artistsTitle, leftX)
        header(d.songsTitle, rightX)

        d.artists.take(5).forEachIndexed { i, (name, count) ->
            val ty = rowsTop + i * rowH
            c.drawText("${i + 1}", leftX, ty + 54f, tp(54f, LIME, bold = true))
            val np = tp(40f, Color.WHITE, bold = true)
            c.drawText(line(name, np, textW), leftX + rankW, ty + 38f, np)
            val sp = tp(30f, 0xB3FFFFFF.toInt())
            c.drawText(line(count, sp, textW), leftX + rankW, ty + 76f, sp)
        }
        d.songs.take(5).forEachIndexed { i, (name, artist, count) ->
            val ty = rowsTop + i * rowH
            c.drawText("${i + 1}", rightX, ty + 54f, tp(54f, LIME, bold = true))
            val np = tp(40f, Color.WHITE, bold = true)
            c.drawText(line(name, np, textW), rightX + rankW, ty + 38f, np)
            val sp = tp(30f, 0xB3FFFFFF.toInt())
            val sub = if (artist.isBlank()) count else "$artist \u00B7 $count"
            c.drawText(line(sub, sp, textW), rightX + rankW, ty + 76f, sp)
        }

        // Footer facts + brand.
        if (d.footer.isNotBlank()) {
            val fp = tp(32f, 0xCCFFFFFF.toInt()).apply { textAlign = Paint.Align.CENTER }
            c.drawText(line(d.footer, fp, contentW), W / 2f, 1772f, fp)
        }
        val brand = tp(46f, Color.WHITE, bold = true, spacing = 0.12f).apply { textAlign = Paint.Align.CENTER }
        c.drawText("\u266A  StarrySound", W / 2f, 1855f, brand)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest card is kept
        val file = File(dir, "wrapped_${System.currentTimeMillis()}.png")
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
}
