package com.fxsoundlite.app

import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.recyclerview.widget.RecyclerView
import com.fxsoundlite.app.databinding.ItemQuickBinding

/** What the Quick page does with the taps on a reel; implemented by [QuickFragment]. */
interface QuickActions {
    /** Tap anywhere on the reel: pause / resume the preview. */
    fun onTap()
    fun onFull(pos: Int)
    /** Heart under "Full song": like / unlike the reel at [pos]. */
    fun onLike(pos: Int)
    fun onPlaylist(pos: Int)
    fun onQueue(pos: Int)
    /** Three-dot button (bottom-right): menu with download / share for the reel at [pos]. */
    fun onMore(pos: Int, anchor: View)
    /** A reel was (re)bound: lets the page paint the spinner / pause icon / progress for it. */
    fun onBound(vh: QuickAdapter.VH, pos: Int)
}

/**
 * One full-page "reel" per song (item_quick): cover photo, title / artist, a "chorus preview"
 * badge and the action rail on the right. [tracks] is the fragment's live feed list.
 */
class QuickAdapter(
    private val tracks: List<Track>,
    private val actions: QuickActions
) : RecyclerView.Adapter<QuickAdapter.VH>() {

    class VH(val b: ItemQuickBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount() = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemQuickBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val vh = VH(b)

        // Rounded cover.
        val radius = 18f * parent.resources.displayMetrics.density
        b.quickCover.clipToOutline = true
        b.quickCover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        // Blurred backdrop where the OS can do it cheaply (Android 12+); darkened on older ones.
        if (Build.VERSION.SDK_INT >= 31) {
            b.quickBg.setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
        }

        fun pos(): Int = vh.bindingAdapterPosition
        b.quickRoot.setOnClickListener { actions.onTap() }
        b.actFull.setOnClickListener { pos().takeIf { it != RecyclerView.NO_POSITION }?.let(actions::onFull) }
        b.actLike.setOnClickListener { pos().takeIf { it != RecyclerView.NO_POSITION }?.let(actions::onLike) }
        b.actPlaylist.setOnClickListener { pos().takeIf { it != RecyclerView.NO_POSITION }?.let(actions::onPlaylist) }
        b.actQueue.setOnClickListener { pos().takeIf { it != RecyclerView.NO_POSITION }?.let(actions::onQueue) }
        b.actMore.setOnClickListener { v -> pos().takeIf { it != RecyclerView.NO_POSITION }?.let { actions.onMore(it, v) } }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = tracks[position]
        val b = h.b
        val ctx = b.root.context

        b.quickTitle.text = t.title
        b.quickArtist.text = QuickFeed.cleanArtist(t.artist)
        b.quickBadge.text = ctx.getString(
            R.string.quick_badge
        ) + " · " + formatDuration(QuickFeed.chorusStartMs(t.durationMs))

        // Cover: placeholder first; the real image replaces it once downloaded. Saver mode keeps
        // the placeholder (same rule as every other list in the app).
        val pad = (56 * ctx.resources.displayMetrics.density).toInt()
        b.quickCover.tag = t.key
        b.quickCover.setPadding(pad, pad, pad, pad)
        b.quickCover.setImageResource(R.drawable.ic_music)
        b.quickCover.imageTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
        b.quickBg.setImageDrawable(null)
        if (t.artUrl.isNotBlank() && !MusicPrefs.saver(ctx)) {
            ImageLoader.load(t.artUrl) { bmp ->
                // The page may have been recycled for another song while the image downloaded.
                if (bmp != null && b.quickCover.tag == t.key) {
                    b.quickCover.setPadding(0, 0, 0, 0)
                    b.quickCover.imageTintList = null
                    b.quickCover.setImageBitmap(bmp)
                    b.quickBg.setImageBitmap(bmp)
                }
            }
            // Warm the cache for the next two reels so they do not pop in late.
            for (d in 1..2) tracks.getOrNull(position + d)?.artUrl?.takeIf { it.isNotBlank() }?.let { ImageLoader.load(it) { } }
        }

        actions.onBound(h, position)
    }
}
