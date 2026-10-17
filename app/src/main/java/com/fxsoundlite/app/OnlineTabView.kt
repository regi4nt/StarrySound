package com.fxsoundlite.app

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*

/**
 * The page of the "Online" tab in Search: source chips (Internet Archive, Audius, Jamendo, ccMixter, Openverse),
 * a search box and the results. Built in code, so the Search screen only has to add it as the content of the tab:
 *
 *     container.addView(OnlineTabView(context))
 *
 * Tapping a song plays it (the whole result list becomes the queue); tapping an Internet Archive item plays its songs.
 */
class OnlineTabView(context: Context) : LinearLayout(context) {

    private var source = OnlineSource.ARCHIVE
    private var raw: List<OnlineResult> = emptyList()      // what the source returned
    private var results: List<OnlineResult> = emptyList()  // raw after the filter chips
    private var minSeconds = 0       // 0 = any length, 60, 180
    private var sortAz = false
    private var losslessOnly = false
    private var commercialOnly = false
    private var genre: String? = null
    private var request = 0 // bumped on every search / chip change; an older answer is dropped

    private val chipViews = ArrayList<TextView>()
    private val input = EditText(context)
    private val filterButton = ImageButton(context)
    private val filterRow = LinearLayout(context)
    private val filterChips = ArrayList<Pair<TextView, () -> Boolean>>() // chip + "is on" test
    private val genreRow = LinearLayout(context)
    private val progress = ProgressBar(context)
    private val message = TextView(context)
    private val keyButton = Button(context)
    private val list = ListView(context)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    init {
        orientation = LinearLayout.VERTICAL
        val fg = themeColor(R.attr.colorTextPrimary)
        val dim = themeColor(R.attr.colorTextSecondary)

        // Source chips
        val chips = LinearLayout(context).apply { setPadding(dp(12), dp(8), dp(12), dp(4)) }
        OnlineSource.values().forEach { s ->
            val c = TextView(context).apply {
                this.text = context.getString(s.labelRes)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { selectSource(s); search() }
            }
            chipViews += c
            chips.addView(c, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // Search box
        input.apply {
            hint = context.getString(R.string.online_archive_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); search(); true } else false
            }
        }
        val searchRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(input, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterButton.apply {
            setImageResource(R.drawable.ic_filter)
            contentDescription = context.getString(R.string.online_filter_desc)
            setBackgroundResource(android.R.color.transparent)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        searchRow.addView(filterButton, LayoutParams(dp(44), dp(44)))
        addView(searchRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(4), dp(8), dp(4))
        })

        // Filter chips (shown with the button next to the search box): length and sorting, applied to the results on screen.
        filterRow.setPadding(dp(12), dp(0), dp(12), dp(4))
        fun chip(label: Int, on: () -> Boolean, server: Boolean = false, row: LinearLayout = filterRow, labelText: String? = null, tap: () -> Unit) {
            val c = TextView(context).apply {
                text = labelText ?: context.getString(label)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener { tap(); if (server) search() else applyFilter(); paintFilters() }
            }
            filterChips += c to on
            row.addView(c, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        chip(R.string.online_filter_any, { minSeconds == 0 }) { minSeconds = 0 }
        chip(R.string.online_filter_1min, { minSeconds == 60 }) { minSeconds = if (minSeconds == 60) 0 else 60 }
        chip(R.string.online_filter_3min, { minSeconds == 180 }) { minSeconds = if (minSeconds == 180) 0 else 180 }
        chip(R.string.online_filter_az, { sortAz }) { sortAz = !sortAz }
        chip(R.string.online_filter_lossless, { losslessOnly }, server = true) { losslessOnly = !losslessOnly }
        chip(R.string.online_filter_commercial, { commercialOnly }, server = true) { commercialOnly = !commercialOnly }
        genreRow.setPadding(dp(12), dp(0), dp(12), dp(4))
        OnlineApi.GENRES.forEach { g ->
            chip(0, { genre == g }, server = true, row = genreRow, labelText = g.replaceFirstChar { it.uppercase() }) { genre = if (genre == g) null else g }
        }
        // Both chip rows open and close together with the filter button.
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(filterRow) })
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(genreRow) })
        }
        addView(panel)
        filterButton.setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        paintFilters()

        addView(progress, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        progress.visibility = View.GONE

        message.apply { setTextColor(dim); gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(8)); visibility = View.GONE }
        addView(message)
        keyButton.apply {
            text = context.getString(R.string.jamendo_key_title)
            visibility = View.GONE
            setOnClickListener { OnlineKeys.showJamendoDialog(context) { search() } }
        }
        addView(keyButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })

        list.apply {
            divider = null
            clipToPadding = false
            setPadding(0, 0, 0, dp(74)) // room for the mini player
            adapter = object : BaseAdapter() {
                override fun getCount() = results.size
                override fun getItem(i: Int) = results[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                    val row = (convert as? LinearLayout) ?: LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        addView(TextView(context).apply { setTextColor(fg); setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(TextView(context).apply { setTextColor(dim); textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    }
                    val (title, sub) = when (val r = results[i]) {
                        is OnlineResult.Album -> r.item.title to r.item.creator
                        is OnlineResult.Song -> r.track.title to r.track.artist
                    }
                    (row.getChildAt(0) as TextView).text = title
                    (row.getChildAt(1) as TextView).apply { text = sub; visibility = if (sub.isBlank()) View.GONE else View.VISIBLE }
                    return row
                }
            }
            setOnItemClickListener { _, _, i, _ -> open(i) }
        }
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        selectSource(source)
        search() // starts with the popular list of the first source
    }

    private fun selectSource(s: OnlineSource) {
        source = s
        val accent = themeColor(R.attr.colorAccent)
        val fg = themeColor(R.attr.colorTextPrimary)
        chipViews.forEachIndexed { i, c ->
            val on = OnlineSource.values()[i] == s
            c.setBackgroundColor((if (on) accent else fg) and 0x00FFFFFF or (if (on) 0x55000000 else 0x1A000000))
            c.setTextColor(fg)
            c.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun paintFilters() {
        val accent = themeColor(R.attr.colorAccent)
        val fg = themeColor(R.attr.colorTextPrimary)
        val dim = themeColor(R.attr.colorTextSecondary)
        filterChips.forEach { (c, on) ->
            val active = on()
            c.setBackgroundColor((if (active) accent else fg) and 0x00FFFFFF or (if (active) 0x55000000 else 0x1A000000))
            c.setTextColor(fg)
            c.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        // The app's filter icon is white: tint it with the text colour, or the accent while a filter is on.
        val any = minSeconds != 0 || sortAz || losslessOnly || commercialOnly || genre != null
        filterButton.setColorFilter(if (any) accent else dim, android.graphics.PorterDuff.Mode.SRC_ATOP)
    }

    /** Length filter + A-Z sort on the results already loaded; no new request. Items of unknown length (0) are kept. */
    private fun applyFilter() {
        var out = raw.filter { r ->
            r !is OnlineResult.Song || (
                (minSeconds == 0 || r.track.durationMs <= 0L || r.track.durationMs >= minSeconds * 1000L) &&
                (!losslessOnly || AudioQuality.cached(r.track)?.lossless == true)
            )
        }
        if (sortAz) out = out.sortedBy {
            when (it) { is OnlineResult.Album -> it.item.title; is OnlineResult.Song -> it.track.title }.lowercase()
        }
        results = out
        paintFilters()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        list.setSelection(0)
        show(if (results.isEmpty() && !progressVisible()) context.getString(R.string.online_no_results) else null)
    }

    private fun progressVisible() = progress.visibility == View.VISIBLE

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun show(msg: String?, needKey: Boolean = false) {
        message.text = msg.orEmpty()
        message.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
        keyButton.visibility = if (needKey) View.VISIBLE else View.GONE
    }

    private fun search() {
        val my = ++request
        val src = source
        val q = input.text.toString()
        raw = emptyList(); results = emptyList(); (list.adapter as BaseAdapter).notifyDataSetChanged()
        if (OnlineApi.needsKey(context, src)) { progress.visibility = View.GONE; show(context.getString(R.string.online_need_jamendo_key), true); return }
        show(null)
        progress.visibility = View.VISIBLE
        Thread {
            val found = try { OnlineApi.search(context, src, q, OnlineFilter(genre, losslessOnly, commercialOnly)) } catch (e: Exception) { e }
            post {
                if (my != request) return@post
                progress.visibility = View.GONE
                if (found is Exception) {
                    show(context.getString(R.string.online_search_failed, found.message ?: found.javaClass.simpleName))
                } else {
                    raw = (found as List<*>).filterIsInstance<OnlineResult>()
                    applyFilter()
                }
            }
        }.start()
    }

    private fun open(i: Int) {
        val svc = MusicService.instance
        if (svc == null) { Toast.makeText(context, R.string.online_player_not_ready, Toast.LENGTH_SHORT).show(); return }
        when (val r = results.getOrNull(i) ?: return) {
            is OnlineResult.Song -> {
                val songs = results.filterIsInstance<OnlineResult.Song>().map { it.track }
                svc.setQueueAndPlay(songs, songs.indexOf(r.track).coerceAtLeast(0))
            }
            is OnlineResult.Album -> {
                progress.visibility = View.VISIBLE
                Thread {
                    val t = try { ArchiveApi.tracks(context, r.item) } catch (e: Exception) { emptyList() }
                    post {
                        progress.visibility = View.GONE
                        if (t.isEmpty()) Toast.makeText(context, R.string.online_no_results, Toast.LENGTH_SHORT).show()
                        else MusicService.instance?.setQueueAndPlay(t, 0)
                    }
                }.start()
            }
        }
    }
}
