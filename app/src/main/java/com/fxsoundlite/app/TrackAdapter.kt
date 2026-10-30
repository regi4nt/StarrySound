package com.fxsoundlite.app

import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Song rows (item_track) shared by the Music page and the Playlist page. */
internal class TrackAdapter(private val onClick: (Int) -> Unit, private val onLong: (Int) -> Unit) :
    RecyclerView.Adapter<TrackAdapter.VH>() {

    private var items: List<Track> = emptyList()
    private var currentKey = ""
    /** Multi-select mode (playlist page): rows show a check box; [selected] holds the picked songs' [Track.syncKey]. */
    private var selecting = false
    private var selected: Set<String> = emptySet()
    private companion object { const val STATUS_PAYLOAD = "radio_status" }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.trackIcon)
        val title: TextView = view.findViewById(R.id.trackTitle)
        val artist: TextView = view.findViewById(R.id.trackArtist)
        val duration: TextView = view.findViewById(R.id.trackDuration)
        val check: CheckBox = view.findViewById(R.id.trackCheck)
    }

    fun setSelection(active: Boolean, keys: Set<String>) {
        if (active == selecting && keys == selected) return
        selecting = active
        selected = keys
        notifyDataSetChanged()
    }

    fun submit(list: List<Track>) {
        items = list
        notifyDataSetChanged()
        // Radio rows show whether the station answers right now; probe them in the background.
        val urls = list.filter { it.source == Track.SOURCE_RADIO }.map { it.remoteId }
        if (urls.isNotEmpty()) RadioStatus.probe(urls) { url -> refreshStatus(url) }
    }

    private fun refreshStatus(url: String) {
        for (i in items.indices) {
            val t = items[i]
            if (t.source == Track.SOURCE_RADIO && t.remoteId == url) notifyItemChanged(i, STATUS_PAYLOAD)
        }
    }

    private fun bindRadioStatus(h: VH, t: Track) {
        val ctx = h.itemView.context
        val (label, color) = when (RadioStatus.get(t.remoteId)) {
            RadioStatus.ACTIVE -> ctx.getString(R.string.radio_status_active) to 0xFF2E9D57.toInt()
            RadioStatus.INACTIVE -> ctx.getString(R.string.radio_status_inactive) to 0xFFD94B4B.toInt()
            else -> ctx.getString(R.string.radio_status_checking) to attrColor(ctx, R.attr.colorTextSecondary)
        }
        val s = SpannableString("\u25CF $label")
        s.setSpan(ForegroundColorSpan(color), 0, s.length, 0)
        h.duration.text = s
    }

    fun setCurrentKey(key: String) {
        if (key == currentKey) return
        currentKey = key
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        val vh = VH(v)
        v.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        v.setOnLongClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) { onLong(pos); true } else false
        }
        return vh
    }

    override fun getItemCount(): Int = items.size

    private fun attrColor(ctx: android.content.Context, attr: Int): Int {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun showDefaultIcon(h: VH, current: Boolean) {
        val ctx = h.itemView.context
        val pad = (11 * ctx.resources.displayMetrics.density).toInt()
        h.icon.setPadding(pad, pad, pad, pad)
        h.icon.setImageResource(R.drawable.ic_music)
        h.icon.imageTintList = ColorStateList.valueOf(
            attrColor(ctx, if (current) androidx.appcompat.R.attr.colorAccent else R.attr.colorTextSecondary)
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(STATUS_PAYLOAD)) {
            val t = items.getOrNull(position) ?: return
            if (t.source == Track.SOURCE_RADIO) bindRadioStatus(holder, t)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val current = t.key == currentKey
        holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
        holder.check.isChecked = selecting && t.syncKey in selected
        holder.title.text = t.title
        holder.artist.text = t.artist.ifBlank { t.album }.let {
            if (t.missing) holder.itemView.context.getString(R.string.track_missing_here, it) else it
        }.let { t.labeledSubtitle(holder.itemView.context, it) }
        holder.itemView.alpha = if (t.missing) 0.45f else 1f
        if (t.source == Track.SOURCE_RADIO) bindRadioStatus(holder, t)
        else if (YouTubeApi.isListRow(t)) holder.duration.text = "" // a playlist / mix row has no length
        else if (t.source == Track.SOURCE_TV) holder.duration.text = "LIVE"
        else holder.duration.text = formatDuration(t.durationMs)

        val ctx = holder.itemView.context
        holder.title.setTextColor(
            attrColor(ctx, if (current) androidx.appcompat.R.attr.colorAccent else R.attr.colorTextPrimary)
        )

        holder.icon.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.icon.tag = t.key
        showDefaultIcon(holder, current)
        if (!MusicPrefs.saver(ctx)) {
            val show = { bmp: android.graphics.Bitmap? ->
                // The row may have been recycled for another track while the image downloaded.
                if (bmp != null && holder.icon.tag == t.key) {
                    holder.icon.setPadding(0, 0, 0, 0)
                    holder.icon.imageTintList = null
                    holder.icon.setImageBitmap(bmp)
                }
            }
            if (t.source == Track.SOURCE_LOCAL) { if (!t.missing) ImageLoader.loadLocal(ctx, t, show) }
            else if (t.artUrl.isNotBlank()) ImageLoader.load(t.artUrl, show)
        }
    }
}
