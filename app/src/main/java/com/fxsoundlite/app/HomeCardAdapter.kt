package com.fxsoundlite.app

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One square card on a Home shelf ("Listen again", "Your playlists"). */
internal data class HomeCard(val key: String, val title: String, val subtitle: String, val artUrl: String)

/** Horizontal shelf cards (item_home_card): cover, two-line title, one-line subtitle. */
internal class HomeCardAdapter(
    private val onClick: (Int) -> Unit,
    private val onLong: (Int) -> Unit = {}
) : RecyclerView.Adapter<HomeCardAdapter.VH>() {

    private var items: List<HomeCard> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val art: ImageView = v.findViewById(R.id.cardArt)
        val title: TextView = v.findViewById(R.id.cardTitle)
        val subtitle: TextView = v.findViewById(R.id.cardSubtitle)
    }

    fun submit(list: List<HomeCard>) {
        if (list == items) return // the Home page re-checks every few seconds; skip no-op redraws
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_card, parent, false))
        vh.itemView.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        vh.itemView.setOnLongClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) { onLong(p); true } else false
        }
        return vh
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.title.text = c.title
        h.subtitle.text = c.subtitle
        val ctx = h.itemView.context
        val pad = (44 * ctx.resources.displayMetrics.density).toInt()
        h.art.tag = c.key
        h.art.setPadding(pad, pad, pad, pad)
        h.art.setImageResource(R.drawable.ic_music)
        val tv = TypedValue()
        ctx.theme.resolveAttribute(R.attr.colorTextSecondary, tv, true)
        h.art.imageTintList = ColorStateList.valueOf(tv.data)
        if (c.artUrl.isNotBlank() && !MusicPrefs.saver(ctx)) {
            ImageLoader.load(c.artUrl) { bmp ->
                // The card may have been recycled for another item while the image downloaded.
                if (bmp != null && h.art.tag == c.key) {
                    h.art.setPadding(0, 0, 0, 0)
                    h.art.imageTintList = null
                    h.art.setImageBitmap(bmp)
                }
            }
        }
    }
}
