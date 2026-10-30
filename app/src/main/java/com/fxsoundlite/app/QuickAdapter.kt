package com.fxsoundlite.app

import android.content.res.ColorStateList
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.graphics.SurfaceTexture
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
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
    /** Video button: preview this reel's clip as video. */
    fun onVideo(pos: Int)
    /** Video mode: the reel's video view got (available = true) or lost its surface. */
    fun onVideoSurface(vh: QuickAdapter.VH, available: Boolean)
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

    class VH(val b: ItemQuickBinding) : RecyclerView.ViewHolder(b.root) {
        /** Surface of [ItemQuickBinding.quickVideo] while it has one. */
        var surface: Surface? = null
    }

    override fun getItemCount() = tracks.size

    companion object {
        /**
         * A TextureView stretches the video to its own size. This scales it like "center crop": the video
         * fills the whole page, keeps its proportions and the outer parts (left / right or top / bottom)
         * are cut off. [w] x [h] is the video's picture size; null / 0 removes the transform.
         */
        fun cropToFill(tv: TextureView, w: Int, h: Int) {
            if (w <= 0 || h <= 0) { tv.tag = null; tv.setTransform(null); return }
            tv.tag = intArrayOf(w, h)
            val vw = tv.width.toFloat()
            val vh = tv.height.toFloat()
            if (vw <= 0f || vh <= 0f) return // applied by the layout listener once the view has a size
            val scale = maxOf(vw / w, vh / h)
            val m = Matrix()
            m.setScale(w * scale / vw, h * scale / vh, vw / 2f, vh / 2f)
            tv.setTransform(m)
        }

        /** Video mode (and no data saver): the page shows the video, never the small cover. */
        fun videoOn(ctx: android.content.Context) = MusicPrefs.videoMode(ctx) && !MusicPrefs.saver(ctx)
    }

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
        b.quickVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                vh.surface?.release()
                vh.surface = Surface(st)
                actions.onVideoSurface(vh, true)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                actions.onVideoSurface(vh, false) // the player lets go of it first
                vh.surface?.release()
                vh.surface = null
                return true
            }
        }
        // The video follows the view's size (rotation, split screen): crop it again to fill.
        b.quickVideo.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                (v.tag as? IntArray)?.let { cropToFill(v as TextureView, it[0], it[1]) }
            }
        }

        fun pos(): Int = vh.bindingAdapterPosition
        b.quickRoot.setOnClickListener { actions.onTap() }
        b.actFull.setOnClickListener { pos().takeIf { it != RecyclerView.NO_POSITION }?.let(actions::onFull) }
        b.actVideo.setOnClickListener { pos().takeIf { it != RecyclerView.NO_POSITION }?.let(actions::onVideo) }
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

        // Video mode: no small cover. The song's picture fills the whole page (sharp, centre cropped) until the
        // first video frame arrives; otherwise the cover is shown over a blurred backdrop (Android 12+).
        val videoMode = videoOn(ctx)
        b.quickCover.visibility = if (videoMode) View.INVISIBLE else View.VISIBLE // INVISIBLE: spinner / lyrics stay anchored to it
        if (Build.VERSION.SDK_INT >= 31) {
            b.quickBg.setRenderEffect(if (videoMode) null else RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
        }

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
