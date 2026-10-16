package com.fxsoundlite.app

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fxsoundlite.app.databinding.ActivityWrappedBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * "Wrapped": a monthly or yearly recap of what was played (time, top artists and songs, favourite
 * hour, busiest day, sources). The numbers come from the per-month buckets of [StatsStore]; the
 * cards are built here in code. Opened from the stats card on Home; the share button sends a
 * Wrapped-style image card ([WrappedShareCard]) together with the recap as text.
 */
class WrappedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWrappedBinding

    private var yearly = false
    private var monthIdx = 0
    private var yearIdx = 0

    /** "yyyyMM", newest first; always contains the current month so a fresh install still shows a page. */
    private var months: List<String> = emptyList()
    private var years: List<Int> = emptyList()

    private var shareText = ""
    private var shareLabel = ""
    private var shareRecap: StatsStore.Recap? = null

    private val locale: Locale get() = resources.configuration.locales[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        binding = ActivityWrappedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.tabMonthly.setOnClickListener { if (yearly) { yearly = false; refresh() } }
        binding.tabYearly.setOnClickListener { if (!yearly) { yearly = true; refresh() } }
        // Lists run newest first, so "earlier" moves to a higher index.
        binding.prevButton.setOnClickListener { step(+1) }
        binding.nextButton.setOnClickListener { step(-1) }
        binding.shareButton.setOnClickListener { share() }

        if (savedInstanceState != null) {
            yearly = savedInstanceState.getBoolean(STATE_YEARLY)
            monthIdx = savedInstanceState.getInt(STATE_MONTH)
            yearIdx = savedInstanceState.getInt(STATE_YEAR)
        }
        loadPeriods()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_YEARLY, yearly)
        outState.putInt(STATE_MONTH, monthIdx)
        outState.putInt(STATE_YEAR, yearIdx)
    }

    private fun loadPeriods() {
        months = (StatsStore.monthKeys(this) + StatsStore.currentMonthKey()).distinct().sortedDescending()
        years = months.map { it.take(4).toInt() }.distinct().sortedDescending()
        monthIdx = monthIdx.coerceIn(0, months.lastIndex)
        yearIdx = yearIdx.coerceIn(0, years.lastIndex)
    }

    private fun step(delta: Int) {
        if (yearly) yearIdx = (yearIdx + delta).coerceIn(0, years.lastIndex)
        else monthIdx = (monthIdx + delta).coerceIn(0, months.lastIndex)
        refresh()
    }

    // ------------------------------------------------------------------ labels

    private fun monthLabel(key: String, withYear: Boolean = true): String {
        val cal = Calendar.getInstance().apply {
            set(key.take(4).toInt(), key.substring(4, 6).toInt() - 1, 1)
        }
        val pattern = if (withYear) "LLLL yyyy" else "LLLL"
        return SimpleDateFormat(pattern, locale).format(cal.time).replaceFirstChar { it.titlecase(locale) }
    }

    private fun dayLabel(key: String): String {
        val cal = Calendar.getInstance().apply {
            set(key.take(4).toInt(), key.substring(4, 6).toInt() - 1, key.substring(6, 8).toInt())
        }
        return SimpleDateFormat("d MMM", locale).format(cal.time)
    }

    private fun duration(ms: Long): String {
        val totalMin = (ms / 60_000L).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) getString(R.string.stats_fmt_hour_min, h, m) else getString(R.string.stats_fmt_min, m)
    }

    private fun hourLabel(h: Int) = String.format(Locale.US, "%02d:00–%02d:00", h, (h + 1) % 24)

    private fun sourceName(src: Int) = getString(
        when (src) {
            Track.SOURCE_YOUTUBE -> R.string.source_youtube
            Track.SOURCE_RADIO -> R.string.source_radio
            Track.SOURCE_DRIVE -> R.string.source_drive
            else -> R.string.source_local
        }
    )

    // ------------------------------------------------------------------ screen

    private fun refresh() {
        binding.tabMonthly.isSelected = !yearly
        binding.tabYearly.isSelected = yearly

        val label: String
        val recap: StatsStore.Recap
        if (yearly) {
            val y = years[yearIdx]
            label = y.toString()
            recap = StatsStore.recapForYear(this, y)
        } else {
            val k = months[monthIdx]
            label = monthLabel(k)
            recap = StatsStore.recapForMonth(this, k)
        }
        binding.periodLabel.text = label
        val size = if (yearly) years.size else months.size
        val idx = if (yearly) yearIdx else monthIdx
        binding.prevButton.alpha = if (idx < size - 1) 1f else 0.3f
        binding.nextButton.alpha = if (idx > 0) 1f else 0.3f

        binding.cards.removeAllViews()
        if (recap.isEmpty) {
            binding.emptyText.visibility = View.VISIBLE
            binding.shareButton.visibility = View.GONE
            shareText = ""
            shareRecap = null
            return
        }
        binding.emptyText.visibility = View.GONE
        binding.shareButton.visibility = View.VISIBLE
        build(label, recap)
        shareText = buildShareText(label, recap)
        shareLabel = label
        shareRecap = recap
    }

    private fun build(label: String, r: StatsStore.Recap) {
        val cards = binding.cards

        // Hero
        cards.addView(vertical().apply {
            setBackgroundResource(R.drawable.wrapped_hero_bg)
            setPadding(dp(22), dp(22), dp(22), dp(22))
            val onAccent = themeColor(R.attr.colorOnAccent)
            addView(text(label.uppercase(locale), 12f, onAccent, bold = true).apply { alpha = 0.8f })
            addView(text(getString(R.string.wrapped_listened), 14f, onAccent).apply {
                setPadding(0, dp(14), 0, 0); alpha = 0.9f
            })
            addView(text(duration(r.totalMs), 40f, onAccent, bold = true))
            addView(text(getString(R.string.wrapped_plays_line, r.plays, r.activeDays), 13f, onAccent).apply {
                setPadding(0, dp(4), 0, 0); alpha = 0.9f
            })
        }, lp(top = 14))

        // Top artist + top song
        val topArtist = r.artists.firstOrNull()
        val topSong = r.tracks.firstOrNull()
        if (topArtist != null || topSong != null) {
            cards.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (topArtist != null) addView(highlight(
                    R.string.wrapped_top_artist, topArtist.first,
                    getString(R.string.wrapped_count_fmt, topArtist.second)
                ), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (topSong != null) marginEnd = dp(6)
                })
                if (topSong != null) addView(highlight(
                    R.string.wrapped_top_song, topSong.title,
                    topSong.artist.ifBlank { getString(R.string.wrapped_count_fmt, topSong.plays) }
                ), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (topArtist != null) marginStart = dp(6)
                })
            }, lp(top = 12))
        }

        // Top artists
        if (r.artists.isNotEmpty()) {
            cards.addView(card(R.string.wrapped_artists_title).apply {
                val max = r.artists.first().second.coerceAtLeast(1)
                r.artists.forEachIndexed { i, (name, n) ->
                    addView(barRow(i + 1, name, getString(R.string.wrapped_count_fmt, n), n.toFloat() / max))
                }
            }, lp(top = 12))
        }

        // Top songs
        if (r.tracks.isNotEmpty()) {
            cards.addView(card(R.string.wrapped_songs_title).apply {
                val max = r.tracks.first().plays.coerceAtLeast(1)
                r.tracks.forEachIndexed { i, t ->
                    val name = if (t.artist.isBlank()) t.title else t.title + " — " + t.artist
                    addView(barRow(i + 1, name, getString(R.string.wrapped_count_fmt, t.plays), t.plays.toFloat() / max))
                }
            }, lp(top = 12))
        }

        // Create a playlist from the top songs of this period
        if (r.playlistTracks.isNotEmpty()) {
            cards.addView(vertical().apply {
                setBackgroundResource(R.drawable.wrapped_hero_bg)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                isClickable = true
                isFocusable = true
                val onAccent = themeColor(R.attr.colorOnAccent)
                addView(text(getString(R.string.wrapped_make_playlist), 15f, onAccent, bold = true))
                addView(text(getString(R.string.wrapped_make_playlist_sub, r.playlistTracks.size), 12f, onAccent).apply {
                    setPadding(0, dp(2), 0, 0); alpha = 0.85f
                })
                setOnClickListener { createPlaylist(label, r.playlistTracks) }
            }, lp(top = 12))
        }

        // Habits: 2-column grid of small facts
        val facts = ArrayList<Pair<String, String>>()
        if (r.peakHour >= 0) facts.add(getString(R.string.wrapped_peak_hour) to hourLabel(r.peakHour))
        r.bestDay?.let { facts.add(getString(R.string.wrapped_best_day) to dayLabel(it) + " · " + duration(r.bestDayMs)) }
        if (r.activeDays > 0) facts.add(getString(R.string.wrapped_avg_day) to duration(r.totalMs / r.activeDays))
        if (yearly) r.topMonth?.let {
            facts.add(getString(R.string.wrapped_top_month) to monthLabel(it, withYear = false) + " · " + duration(r.topMonthMs))
        }
        if (r.artistCount > 0 || r.trackCount > 0) {
            facts.add(getString(R.string.wrapped_variety) to getString(R.string.wrapped_variety_value, r.artistCount, r.trackCount))
        }
        if (facts.isNotEmpty()) {
            cards.addView(card(R.string.wrapped_habits_title).apply {
                facts.chunked(2).forEach { pair ->
                    addView(LinearLayout(this@WrappedActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        pair.forEach { (k, v) -> addView(fact(k, v), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
                        if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(10)
                    })
                }
            }, lp(top = 12))
        }

        // Hour of day chart
        if (r.peakHour >= 0) cards.addView(hoursCard(r), lp(top = 12))

        // Sources
        val srcTotal = r.sources.values.sum()
        if (srcTotal > 0) {
            cards.addView(card(R.string.wrapped_sources_title).apply {
                r.sources.entries.sortedByDescending { it.value }.forEachIndexed { i, (src, n) ->
                    val pct = (n * 100f / srcTotal).toInt()
                    addView(barRow(0, sourceName(src), "$pct%", n.toFloat() / srcTotal, showRank = false))
                }
            }, lp(top = 12))
        }
        cards.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
    }

    // ------------------------------------------------------------------ view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    /** A rounded card with a small heading; rows are added by the caller. */
    private fun card(titleRes: Int) = vertical().apply {
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(text(getString(titleRes), 13f, themeColor(R.attr.colorTextSecondary), bold = true))
    }

    /** Half-width card: small heading, a big accent value (artist / song) and a line below. */
    private fun highlight(titleRes: Int, value: String, sub: String) = vertical().apply {
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(text(getString(titleRes), 12f, themeColor(R.attr.colorTextSecondary), bold = true))
        addView(text(value, 18f, themeColor(androidx.appcompat.R.attr.colorAccent), bold = true).apply {
            maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0)
        })
        addView(text(sub, 12f, themeColor(R.attr.colorTextSecondary)).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        })
    }

    private fun fact(k: String, v: String) = vertical().apply {
        setPadding(0, 0, dp(8), 0)
        addView(text(k, 11f, themeColor(R.attr.colorTextSecondary)))
        addView(text(v, 14f, themeColor(R.attr.colorTextPrimary), bold = true).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(color) }

    /** Rank, name, count and a thin bar showing [frac] (0..1) of the top entry. */
    private fun barRow(rank: Int, name: String, count: String, frac: Float, showRank: Boolean = true): View =
        vertical().apply {
            setPadding(0, dp(12), 0, 0)
            addView(LinearLayout(this@WrappedActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (showRank) addView(
                    text(rank.toString(), 14f, themeColor(androidx.appcompat.R.attr.colorAccent), bold = true),
                    LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
                )
                addView(text(name, 14f, themeColor(R.attr.colorTextPrimary), bold = true).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(count, 12f, themeColor(R.attr.colorTextSecondary)).apply { setPadding(dp(8), 0, 0, 0) })
            })
            val f = frac.coerceIn(0.02f, 1f)
            addView(LinearLayout(this@WrappedActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 1000f
                background = pill(themeColor(R.attr.colorTrackBg))
                addView(View(context).apply { background = pill(themeColor(androidx.appcompat.R.attr.colorAccent)) },
                    LinearLayout.LayoutParams(0, dp(6), f * 1000f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(6)
                if (showRank) marginStart = dp(24)
            })
        }

    /** 24 bars (hour of day), the favourite hour at full strength. */
    private fun hoursCard(r: StatsStore.Recap): View = card(R.string.wrapped_hours_title).apply {
        val max = (r.hours.maxOrNull() ?: 1L).coerceAtLeast(1L)
        val accent = themeColor(androidx.appcompat.R.attr.colorAccent)
        val faded = (accent and 0x00FFFFFF) or (0x66 shl 24)
        addView(LinearLayout(this@WrappedActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            for (h in 0 until 24) {
                val barH = (dp(80) * (r.hours.getOrElse(h) { 0L }.toFloat() / max)).toInt().coerceAtLeast(dp(2))
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(if (h == r.peakHour) accent else faded)
                    }
                }, LinearLayout.LayoutParams(0, barH, 1f).apply { marginStart = dp(1); marginEnd = dp(1) })
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)).apply { topMargin = dp(14) })
        addView(LinearLayout(this@WrappedActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            val c = themeColor(R.attr.colorTextSecondary)
            addView(text("00", 10f, c), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("12", 10f, c).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("23", 10f, c).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }

    // ------------------------------------------------------------------ playlist

    /** Asks for a name (prefilled), stores the songs as a normal playlist and offers to open it. */
    private fun createPlaylist(label: String, tracks: List<Track>) {
        PlaylistUi.promptName(this, R.string.playlist_create, getString(R.string.wrapped_playlist_name, label)) { name ->
            val pl = PlaylistStore.createWith(applicationContext, name, tracks)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.playlist_created, pl.name))
                .setMessage(getString(R.string.wrapped_playlist_added, pl.tracks.size))
                .setPositiveButton(R.string.wrapped_playlist_open) { _, _ -> openPlaylist(pl.id) }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Back to the main screen, on the Playlist page with the new list opened. */
    private fun openPlaylist(id: String) {
        PlaylistFragment.pendingOpen = id
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYLIST)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    // ------------------------------------------------------------------ share

    private fun buildShareText(label: String, r: StatsStore.Recap): String {
        val sb = StringBuilder()
        sb.append(getString(R.string.wrapped_share_header, label)).append('\n')
        sb.append(getString(R.string.wrapped_listened)).append(' ').append(duration(r.totalMs)).append('\n')
        sb.append(getString(R.string.wrapped_plays_line, r.plays, r.activeDays)).append('\n')
        if (r.artists.isNotEmpty()) {
            sb.append('\n').append(getString(R.string.wrapped_artists_title)).append('\n')
            r.artists.forEachIndexed { i, (n, c) -> sb.append(i + 1).append(". ").append(n).append(" (").append(getString(R.string.wrapped_count_fmt, c)).append(")\n") }
        }
        if (r.tracks.isNotEmpty()) {
            sb.append('\n').append(getString(R.string.wrapped_songs_title)).append('\n')
            r.tracks.forEachIndexed { i, t ->
                sb.append(i + 1).append(". ").append(t.title)
                if (t.artist.isNotBlank()) sb.append(" — ").append(t.artist)
                sb.append(" (").append(getString(R.string.wrapped_count_fmt, t.plays)).append(")\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /** Shares a Wrapped-style picture (with the recap text as caption); plain text if the picture fails. */
    private fun share() {
        val r = shareRecap
        if (shareText.isBlank() || r == null) return
        val footer = ArrayList<String>()
        if (r.peakHour >= 0) footer.add(getString(R.string.wrapped_peak_hour) + ": " + hourLabel(r.peakHour))
        val data = WrappedShareCard.Data(
            period = shareLabel,
            listened = getString(R.string.wrapped_listened),
            duration = duration(r.totalMs),
            plays = getString(R.string.wrapped_plays_line, r.plays, r.activeDays),
            artistsTitle = getString(R.string.wrapped_artists_title),
            songsTitle = getString(R.string.wrapped_songs_title),
            topSongTitle = getString(R.string.wrapped_top_song),
            artists = r.artists.map { (n, c) -> n to getString(R.string.wrapped_count_fmt, c) },
            songs = r.tracks.map { Triple(it.title, it.artist, getString(R.string.wrapped_count_fmt, it.plays)) },
            footer = footer.joinToString(" \u00B7 "),
            coverUrl = r.tracks.firstOrNull()?.track?.artUrl.orEmpty()
        )
        WrappedShareCard.share(this, data, shareText, getString(R.string.wrapped_share_chooser))
    }

    companion object {
        private const val STATE_YEARLY = "yearly"
        private const val STATE_MONTH = "month_idx"
        private const val STATE_YEAR = "year_idx"
    }
}
