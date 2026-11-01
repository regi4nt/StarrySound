package com.fxsoundlite.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Spotify player opened from the Spotify tab next to Radio. Spotify audio only plays inside the Spotify app, so this
 * screen is a remote: it shows what Spotify is playing and controls it through the Web API (Premium + an active
 * Spotify device). Four parts: now playing, your playlists, song search, and the queue. The StarrySound equalizer
 * still reaches the audio through [SpotifyEffects].
 */
class SpotifyActivity : AppCompatActivity() {

    private class Row(
        val title: String,
        val sub: String,
        val art: String,
        val actionLabel: String?,
        val onTap: (() -> Unit)?,
        val onAction: (() -> Unit)?
    )

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val green = Color.parseColor("#1DB954")
    private var alive = false
    private var destroyed = false

    private lateinit var statusText: TextView
    private lateinit var cover: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: TextView
    private lateinit var shuffleBtn: TextView
    private lateinit var repeatBtn: TextView
    private lateinit var searchInput: EditText
    private lateinit var list: ListView
    private val tabButtons = ArrayList<TextView>()

    private val rows = ArrayList<Row>()
    private var tab = 0 // 0 playlists, 1 search, 2 queue
    private var listToken = 0 // answers of an older tab / search are ignored

    private var playing = false
    private var shuffle = false
    private var repeat = "off"
    private var seeking = false

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val v = convertView ?: buildRowView()
            val art = v.findViewById<ImageView>(R.id.sp_art)
            val title = v.findViewById<TextView>(R.id.sp_title_text)
            val sub = v.findViewById<TextView>(R.id.sp_sub_text)
            val action = v.findViewById<TextView>(R.id.sp_action)
            title.text = row.title
            sub.text = row.sub
            art.setImageDrawable(null)
            art.tag = row.art
            if (row.art.isNotBlank()) {
                ImageLoader.load(row.art) { bm -> if (bm != null && art.tag == row.art) art.setImageBitmap(bm) }
            }
            if (row.actionLabel != null && row.onAction != null) {
                action.visibility = View.VISIBLE
                action.text = row.actionLabel
                action.setOnClickListener { row.onAction.invoke() }
            } else {
                action.visibility = View.GONE
            }
            return v
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!alive) return
            refreshPlayer()
            ui.postDelayed(this, 2500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.sp_title)
        setContentView(buildScreen())
        setTab(0)
    }

    override fun onResume() {
        super.onResume()
        alive = true
        if (SpotifyAuth.isConnected(this)) {
            statusText.visibility = View.GONE
            ui.removeCallbacks(poll)
            ui.post(poll)
        } else {
            showNotConnected()
        }
    }

    override fun onPause() {
        alive = false
        ui.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ screen

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
        } else fallback
    }

    private fun textView(size: Float, bold: Boolean = false, color: Int = themeColor(android.R.attr.textColorPrimary, Color.WHITE)): TextView =
        TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun controlButton(label: String, size: Float): TextView =
        textView(size).apply {
            text = label
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
        }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(android.R.attr.colorBackground, Color.BLACK))
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        root.addView(textView(22f, true).apply { setText(R.string.sp_title) })

        statusText = textView(13f, color = Color.parseColor("#FF6B6B")).apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(statusText)

        // ---- now playing
        val now = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#33000000"))
        }
        now.addView(cover, LinearLayout.LayoutParams(dp(96), dp(96)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleText = textView(17f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setText(R.string.sp_nothing) }
        artistText = textView(14f, color = themeColor(android.R.attr.textColorSecondary, Color.GRAY)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        names.addView(titleText)
        names.addView(artistText)
        now.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        root.addView(now)

        seek = SeekBar(this).apply {
            max = 1
            progressTintList = android.content.res.ColorStateList.valueOf(green)
            thumbTintList = android.content.res.ColorStateList.valueOf(green)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val pos = sb?.progress ?: 0
                    control({ SpotifyApi.put(applicationContext, "/me/player/seek?position_ms=$pos", "{}") }, null)
                    seeking = false
                }
            })
        }
        root.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        shuffleBtn = controlButton("\uD83D\uDD00", 20f).apply { alpha = 0.4f }
        val prevBtn = controlButton("\u23EE", 26f)
        playBtn = controlButton("\u25B6", 32f)
        val nextBtn = controlButton("\u23ED", 26f)
        repeatBtn = controlButton("\uD83D\uDD01", 20f).apply { alpha = 0.4f }
        for (b in listOf(shuffleBtn, prevBtn, playBtn, nextBtn, repeatBtn)) controls.addView(b)
        root.addView(controls)

        shuffleBtn.setOnClickListener {
            val to = !shuffle
            control({ SpotifyApi.put(applicationContext, "/me/player/shuffle?state=$to", "{}") }, null)
        }
        repeatBtn.setOnClickListener {
            val to = when (repeat) { "off" -> "context"; "context" -> "track"; else -> "off" }
            control({ SpotifyApi.put(applicationContext, "/me/player/repeat?state=$to", "{}") }, null)
        }
        prevBtn.setOnClickListener {
            control({ SpotifyApi.post(applicationContext, "/me/player/previous") }, { SpotifyRemote.previous(applicationContext) })
        }
        nextBtn.setOnClickListener {
            control({ SpotifyApi.post(applicationContext, "/me/player/next") }, { SpotifyRemote.next(applicationContext) })
        }
        playBtn.setOnClickListener {
            if (playing) control({ SpotifyApi.put(applicationContext, "/me/player/pause", "{}") }, { SpotifyRemote.playPause(applicationContext) })
            else control({ SpotifyApi.put(applicationContext, "/me/player/play", "{}") }, { SpotifyRemote.playPause(applicationContext) })
        }

        // ---- tabs
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val labels = intArrayOf(R.string.sp_playlists, R.string.sp_search, R.string.sp_queue)
        for ((i, res) in labels.withIndex()) {
            val b = textView(14f, true).apply {
                setText(res)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { setTab(i) }
            }
            tabButtons += b
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)

        searchInput = EditText(this).apply {
            setHint(R.string.sp_search_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            visibility = View.GONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runSearch(text.toString().trim())
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
        }
        root.addView(searchInput)

        list = ListView(this).apply {
            adapter = this@SpotifyActivity.adapter
            divider = null
            setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.onTap?.invoke() }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildRowView(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val art = ImageView(this).apply {
            id = R.id.sp_art
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#33000000"))
        }
        row.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(textView(15f, true).apply { id = R.id.sp_title_text; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(textView(12f, color = themeColor(android.R.attr.textColorSecondary, Color.GRAY)).apply { id = R.id.sp_sub_text; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        val action = textView(13f, true, green).apply {
            id = R.id.sp_action
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isFocusable = false
            isClickable = true
            visibility = View.GONE
        }
        row.addView(action)
        return row
    }

    // ------------------------------------------------------------------ tabs + lists

    private fun setTab(i: Int) {
        tab = i
        val on = themeColor(android.R.attr.textColorPrimary, Color.WHITE)
        for ((idx, b) in tabButtons.withIndex()) {
            b.alpha = if (idx == i) 1f else 0.5f
            b.setTextColor(if (idx == i) green else on)
        }
        searchInput.visibility = if (i == 1) View.VISIBLE else View.GONE
        listToken++
        rows.clear()
        adapter.notifyDataSetChanged()
        if (!SpotifyAuth.isConnected(this)) return
        when (i) {
            0 -> loadPlaylists()
            1 -> if (searchInput.text.isNotBlank()) runSearch(searchInput.text.toString().trim())
            else -> loadQueue()
        }
    }

    private fun showRows(token: Int, newRows: List<Row>) {
        if (destroyed || token != listToken) return
        rows.clear()
        rows.addAll(newRows)
        adapter.notifyDataSetChanged()
        if (newRows.isEmpty()) toast(getString(R.string.sp_empty))
    }

    private fun loadPlaylists() {
        val token = listToken
        val app = applicationContext
        io.execute {
            try {
                val pls = SpotifyApi.playlists(app)
                val out = pls.map { pl ->
                    Row(pl.name, if (pl.total >= 0) "${pl.total}" else "", "", null, { playPlaylist(pl.id) }, null)
                }
                runOnUiThread { showRows(token, out) }
            } catch (e: Exception) { runOnUiThread { showError(e) } }
        }
    }

    private fun runSearch(q: String) {
        if (q.isEmpty() || !SpotifyAuth.isConnected(this)) return
        val token = ++listToken
        val app = applicationContext
        io.execute {
            try {
                val hits = SpotifyApi.search(app, q)
                val out = hits.map { h ->
                    Row(h.title, h.artist, h.artUrl, getString(R.string.sp_add_queue), { playUri(h.uri) }, { addToQueue(h.uri) })
                }
                runOnUiThread { showRows(token, out) }
            } catch (e: Exception) { runOnUiThread { showError(e) } }
        }
    }

    private fun loadQueue() {
        val token = listToken
        val app = applicationContext
        io.execute {
            try {
                val hits = SpotifyApi.queue(app)
                val out = hits.map { h -> Row(h.title, h.artist, h.artUrl, null, null, null) }
                runOnUiThread { showRows(token, out) }
            } catch (e: Exception) { runOnUiThread { showError(e) } }
        }
    }

    // ------------------------------------------------------------------ playback actions

    private fun playPlaylist(id: String) {
        val app = applicationContext
        control({ SpotifyRemote.playPlaylist(app, id).let { Http.Response(it, "") } }, null)
    }

    private fun playUri(uri: String) {
        val app = applicationContext
        val body = JSONObject().put("uris", org.json.JSONArray().put(uri)).toString()
        control({ SpotifyApi.put(app, "/me/player/play", body) }, null)
    }

    private fun addToQueue(uri: String) {
        val app = applicationContext
        val enc = java.net.URLEncoder.encode(uri, "UTF-8")
        io.execute {
            try {
                val r = SpotifyApi.post(app, "/me/player/queue?uri=$enc")
                runOnUiThread { if (r.code in 200..299) toast(getString(R.string.sp_queued)) else hint(r.code) }
            } catch (e: Exception) { runOnUiThread { showError(e) } }
        }
    }

    /** Runs a player request off the main thread; [fallback] (media key to the Spotify app) when Spotify's API refuses. */
    private fun control(block: () -> Http.Response, fallback: (() -> Unit)?) {
        io.execute {
            try {
                val r = block()
                if (r.code !in 200..299) runOnUiThread { fallback?.invoke(); hint(r.code) }
            } catch (e: Exception) { runOnUiThread { showError(e) } }
            ui.postDelayed({ if (alive) refreshPlayer() }, 700)
        }
    }

    // ------------------------------------------------------------------ now playing

    private fun refreshPlayer() {
        if (!SpotifyAuth.isConnected(this)) return
        val app = applicationContext
        io.execute {
            try {
                val p = SpotifyApi.player(app)
                runOnUiThread { showPlayer(p) }
            } catch (e: Exception) { runOnUiThread { showError(e) } }
        }
    }

    private fun showPlayer(p: JSONObject?) {
        if (destroyed) return
        statusText.visibility = View.GONE
        val item = p?.optJSONObject("item")
        if (p == null || item == null) {
            titleText.setText(R.string.sp_nothing)
            artistText.text = ""
            cover.setImageDrawable(null)
            cover.tag = null
            playing = false
            playBtn.text = "\u25B6"
            if (!seeking) { seek.max = 1; seek.progress = 0 }
            return
        }
        titleText.text = item.optString("name")
        val artists = item.optJSONArray("artists")
        val names = (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        artistText.text = names.joinToString(", ")
        val art = item.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
            ?: item.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
        if (art.isNotBlank() && cover.tag != art) {
            cover.tag = art
            ImageLoader.load(art) { bm -> if (bm != null && cover.tag == art) cover.setImageBitmap(bm) }
        }
        playing = p.optBoolean("is_playing")
        shuffle = p.optBoolean("shuffle_state")
        repeat = p.optString("repeat_state", "off")
        playBtn.text = if (playing) "\u23F8" else "\u25B6"
        shuffleBtn.alpha = if (shuffle) 1f else 0.4f
        repeatBtn.text = if (repeat == "track") "\uD83D\uDD02" else "\uD83D\uDD01"
        repeatBtn.alpha = if (repeat == "off") 0.4f else 1f
        if (!seeking) {
            val dur = item.optLong("duration_ms", 0L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            seek.max = dur
            seek.progress = p.optLong("progress_ms", 0L).coerceIn(0L, dur.toLong()).toInt()
        }
    }

    // ------------------------------------------------------------------ messages

    private fun showNotConnected() {
        statusText.text = getString(R.string.sp_connect_first)
        statusText.visibility = View.VISIBLE
        statusText.isClickable = true
        statusText.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        statusText.append("  \u203A " + getString(R.string.sp_settings))
    }

    private fun hint(code: Int) {
        when (code) {
            404 -> toast(getString(R.string.sp_no_device))
            403 -> toast(getString(R.string.sp_forbidden))
            else -> toast(getString(R.string.spotify_error, "HTTP $code"))
        }
    }

    private fun showError(e: Exception) {
        if (destroyed) return
        val code = (e as? SpotifyApi.ApiException)?.code
        when (code) {
            404 -> toast(getString(R.string.sp_no_device))
            403 -> toast(getString(R.string.sp_forbidden) + " " + (e.message ?: ""))
            else -> toast(getString(R.string.spotify_error, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
