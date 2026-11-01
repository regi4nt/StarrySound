package com.fxsoundlite.app

import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Filter applied on top of the search box of Device music, YouTube results and an opened playlist: where the song
 * comes from, how long it is and who sings it (YouTube and playlists also sort in the same dialog). In memory only (reset when another list is opened).
 */
class SongFilter {
    /** [ANY] or one of the Track.SOURCE_* values. */
    var source = ANY
    /** [StorageKind.ANY] or where the song is stored: device, SD card, OTG drive. */
    var storage = StorageKind.ANY
    var length = LEN_ANY
    var artist: String? = null
    /** Radio only: one country / city / genre word of the station's subtitle. */
    var tag: String? = null

    val active: Boolean get() = source != ANY || storage != StorageKind.ANY || length != LEN_ANY || artist != null || tag != null

    fun reset() { source = ANY; storage = StorageKind.ANY; length = LEN_ANY; artist = null; tag = null }

    fun copyFrom(o: SongFilter) { source = o.source; storage = o.storage; length = o.length; artist = o.artist; tag = o.tag }

    fun matches(t: Track): Boolean {
        if (source != ANY && t.source != source) return false
        if (storage != StorageKind.ANY && (t.source != Track.SOURCE_LOCAL || MusicLibrary.storageKind(t) != storage)) return false
        if (length != LEN_ANY) {
            val min = t.durationMs / 60_000.0
            if (t.durationMs <= 0L) return false // unknown length (radio, ...)
            val ok = when (length) {
                LEN_SHORT -> min < 3.0
                LEN_MEDIUM -> min >= 3.0 && min <= 5.0
                else -> min > 5.0
            }
            if (!ok) return false
        }
        val a = artist
        if (a != null && !t.artist.trim().equals(a, ignoreCase = true)) return false
        val g = tag
        if (g != null && FilterUi.radioTags(t).none { it.equals(g, ignoreCase = true) }) return false
        return true
    }

    companion object {
        const val ANY = -1
        const val LEN_ANY = 0
        const val LEN_SHORT = 1
        const val LEN_MEDIUM = 2
        const val LEN_LONG = 3
    }
}

/** Optional "sort by" section of the filter dialog: [labels] are string resources, the choice is their index. */
class SortOption(
    val labels: List<Int>,
    val get: () -> Int,
    val set: (Int) -> Unit
)

/** Optional single-choice "show" section of the dialog (the playlist overview's kinds): [values] line up with [labels]. */
class KindOption(
    val values: List<Int>,
    val labels: List<Int>,
    val get: () -> Int,
    val set: (Int) -> Unit
)

/** Online tab section of the filter dialog: source, genre, lossless, commercial. Genre index 0 = all. */
class OnlineOption(
    val sourceLabels: List<String>,
    val getSource: () -> Int,
    val setSource: (Int) -> Unit,
    val genreLabels: List<String>,
    val getGenre: () -> Int,
    val setGenre: (Int) -> Unit,
    val getLossless: () -> Boolean,
    val setLossless: (Boolean) -> Unit,
    val getCommercial: () -> Boolean,
    val setCommercial: (Boolean) -> Unit
)

object FilterUi {

    private fun themeColor(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun sourceLabel(src: Int) = when (src) {
        Track.SOURCE_YOUTUBE -> R.string.source_youtube
        Track.SOURCE_RADIO -> R.string.source_radio
        Track.SOURCE_DRIVE -> R.string.source_drive
        else -> R.string.source_local
    }

    /** Dims the filter button while no filter is on, like the sort button next to it. */
    fun setButtonState(button: View, active: Boolean) {
        button.alpha = if (active) 1f else 0.7f
    }

    /**
     * Source / length / artist filter for [tracks] (the songs the list is made of, before filtering).
     * Sections that would offer no choice (a single source, no known lengths) are left out.
     */
    fun showSongFilter(
        ctx: Context, tracks: List<Track>, filter: SongFilter, sort: SortOption? = null,
        tagMode: Boolean = false, kinds: KindOption? = null, online: OnlineOption? = null,
        manageStorage: (() -> Unit)? = null, onChanged: () -> Unit
    ) {
        // With [kinds] (playlist overview) the dialog is about whole lists, not songs.
        val songSections = !tagMode && kinds == null
        val dp = ctx.resources.displayMetrics.density
        val work = SongFilter().also { it.copyFrom(filter) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }

        fun title(res: Int) {
            root.addView(TextView(ctx).apply {
                setText(res)
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(ctx, R.attr.colorTextSecondary))
                setPadding(0, (14 * dp).toInt(), 0, (6 * dp).toInt())
            })
        }

        /** One row of single-choice chips; [values] and [labels] line up, [get]/[set] read and write the choice. */
        fun chips(values: List<Int>, labels: List<String>, get: () -> Int, set: (Int) -> Unit) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun paint() { for (i in 0 until row.childCount) row.getChildAt(i).isSelected = values[i] == get() }
            for (i in values.indices) {
                val chip = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, row, false) as TextView
                chip.text = labels[i]
                chip.setOnClickListener { set(values[i]); paint() }
                row.addView(chip)
            }
            paint()
            root.addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(row)
            })
        }

        // Online tab: which source, genre, lossless / commercial only (applied together with the rest).
        var wSrc = online?.getSource?.invoke() ?: 0
        var wGenre = online?.getGenre?.invoke() ?: 0
        var wLossless = online?.getLossless?.invoke() ?: false
        var wCommercial = online?.getCommercial?.invoke() ?: false
        if (online != null) {
            title(R.string.online_source_title)
            chips(online.sourceLabels.indices.toList(), online.sourceLabels, { wSrc }, { wSrc = it })
            title(R.string.online_genre_title)
            val genreChip = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, root, false) as TextView
            fun paintGenre() { genreChip.text = online.genreLabels[wGenre]; genreChip.isSelected = wGenre != 0 }
            paintGenre()
            genreChip.setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.online_genre_title)
                    .setItems(online.genreLabels.toTypedArray()) { _, which -> wGenre = which; paintGenre() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            root.addView(genreChip)
            title(R.string.online_filter_title)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun toggle(label: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
                val c = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, row, false) as TextView
                c.setText(label)
                c.isSelected = get()
                c.setOnClickListener { set(!get()); c.isSelected = get() }
                row.addView(c)
            }
            toggle(R.string.online_filter_lossless, { wLossless }, { wLossless = it })
            toggle(R.string.online_filter_commercial, { wCommercial }, { wCommercial = it })
            root.addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(row)
            })
        }

        // Sort order: always offered when the caller has one (applied together with the filter).
        var workSort = sort?.get() ?: 0
        if (sort != null) {
            title(R.string.playlist_sort_title)
            chips(
                sort.labels.indices.toList(),
                sort.labels.map { ctx.getString(it) },
                { workSort }, { workSort = it }
            )
        }

        // Playlist overview: which kinds of playlist to show.
        var workKind = kinds?.get() ?: 0
        if (kinds != null) {
            title(R.string.filter_pl_title)
            chips(kinds.values, kinds.labels.map { ctx.getString(it) }, { workKind }, { workKind = it })
        }

        // Radio: country / city / genre instead of source / length / artist (a station has no length or singer).
        if (tagMode) {
            val counts = HashMap<String, Int>()
            val names = HashMap<String, String>()
            for (s in tracks) for (tg in radioTags(s).distinct()) {
                val k = tg.lowercase(Locale.getDefault())
                counts[k] = (counts[k] ?: 0) + 1
                names.putIfAbsent(k, tg)
            }
            val picked = work.tag?.lowercase(Locale.getDefault())
            val keys = counts.entries
                .filter { it.value >= 2 || it.key == picked }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(80).map { it.key }
            if (keys.isNotEmpty()) {
                title(R.string.filter_tag)
                val tagChip = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, root, false) as TextView
                fun paintTag() {
                    tagChip.text = work.tag ?: ctx.getString(R.string.filter_tag_all)
                    tagChip.isSelected = work.tag != null
                }
                paintTag()
                tagChip.setOnClickListener {
                    val labels = (listOf(ctx.getString(R.string.filter_tag_all)) +
                        keys.map { (names[it] ?: it) + " (" + counts[it] + ")" }).toTypedArray()
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.filter_radio_title)
                        .setItems(labels) { _, which ->
                            work.tag = if (which == 0) null else names[keys[which - 1]]
                            paintTag()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                root.addView(tagChip)
            }
        }

        // Storage (Device tab): phone / SD card / OTG. The chips appear once songs come from more than one place;
        // the "manage" chip is always there so an OTG drive the system did not index can be added.
        if (songSections && manageStorage != null) {
            val kindsHere = tracks.filter { it.source == Track.SOURCE_LOCAL }.map { MusicLibrary.storageKind(it) }.distinct().sorted()
            title(R.string.filter_storage)
            if (kindsHere.size >= 2) {
                chips(
                    listOf(StorageKind.ANY) + kindsHere,
                    listOf(ctx.getString(R.string.filter_all)) + kindsHere.map { ctx.getString(StorageKind.labelRes(it)) },
                    { work.storage }, { work.storage = it }
                )
            }
            val manage = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, root, false) as TextView
            manage.setText(R.string.storage_manage)
            manage.setOnClickListener { manageStorage() }
            root.addView(manage)
        }

        // Source: only when the list really mixes several.
        val sources = if (!songSections) emptyList() else tracks.map { it.source }.distinct().sorted()
        if (sources.size >= 2) {
            title(R.string.filter_source)
            val values = listOf(SongFilter.ANY) + sources
            val labels = listOf(ctx.getString(R.string.filter_all)) + sources.map { ctx.getString(sourceLabel(it)) }
            chips(values, labels, { work.source }, { work.source = it })
        }

        // Length: only when some songs have a known duration.
        if (songSections && tracks.any { it.durationMs > 0L }) {
            title(R.string.filter_length)
            chips(
                listOf(SongFilter.LEN_ANY, SongFilter.LEN_SHORT, SongFilter.LEN_MEDIUM, SongFilter.LEN_LONG),
                listOf(R.string.filter_all, R.string.filter_len_short, R.string.filter_len_medium, R.string.filter_len_long)
                    .map { ctx.getString(it) },
                { work.length }, { work.length = it }
            )
        }

        // Artist: opens a list (there can be hundreds).
        val artists = tracks.map { it.artist.trim() }.filter { it.isNotBlank() }
            .groupingBy { it.lowercase(Locale.getDefault()) }.eachCount()
        val names = HashMap<String, String>()
        tracks.forEach { val a = it.artist.trim(); if (a.isNotBlank()) names.putIfAbsent(a.lowercase(Locale.getDefault()), a) }
        if (songSections && artists.size >= 2) {
            title(R.string.filter_artist)
            val artistChip = LayoutInflater.from(ctx).inflate(R.layout.item_preset_chip, root, false) as TextView
            fun paintArtist() {
                artistChip.text = work.artist ?: ctx.getString(R.string.filter_artist_all)
                artistChip.isSelected = work.artist != null
            }
            paintArtist()
            artistChip.setOnClickListener {
                val sorted = artists.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(300)
                val labels = (listOf(ctx.getString(R.string.filter_artist_all)) +
                    sorted.map { (names[it.key] ?: it.key) + " (" + it.value + ")" }).toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.filter_artist)
                    .setItems(labels) { _, which ->
                        work.artist = if (which == 0) null else names[sorted[which - 1].key]
                        paintArtist()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            root.addView(artistChip)
        }

        if (root.childCount == 0) {
            Toast.makeText(ctx, R.string.filter_none_options, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.filter_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(root) })
            .setPositiveButton(R.string.filter_apply) { _, _ -> filter.copyFrom(work); sort?.set(workSort); kinds?.set(workKind)
                online?.let { it.setSource(wSrc); it.setGenre(wGenre); it.setLossless(wLossless); it.setCommercial(wCommercial) }
                onChanged() }
            .setNeutralButton(R.string.filter_reset) { _, _ -> filter.reset(); sort?.set(0); kinds?.set(0)
                online?.let { it.setGenre(0); it.setLossless(false); it.setCommercial(false) }
                onChanged() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ radio: country / genre

    private val QUALITY = Regex("(?i)^(mp3|aac\\+?|ogg|flac|opus|hls|wma|m3u8|unknown)(\\s+\\d+k)?$|^\\d+k$")

    /** Country / city / genre words of a station (its subtitle is "City · Country · Genre" or "Country · MP3 128k"). */
    fun radioTags(t: Track): List<String> =
        t.artist.split("\u00B7").map { it.trim() }.filter { it.isNotEmpty() && !QUALITY.matches(it) }
}
