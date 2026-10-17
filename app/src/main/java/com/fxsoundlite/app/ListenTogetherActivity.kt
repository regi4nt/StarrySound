package com.fxsoundlite.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import kotlin.math.abs

/**
 * "Listen together" as a full-screen, Discord-style room:
 *  - not in a room: a "join voice" style start page (create a room / join with a code);
 *  - in a room: a header with the room "channel", a grid of member tiles (avatar ring lights up
 *    green while music plays, like a speaking indicator), a now-playing activity card, and a
 *    floating control bar (copy code / invite / leave in red).
 *
 * All the syncing is done by [ListenTogether]; this screen only shows its state and calls
 * host() / join() / leave(). Open it through [ListenTogetherUi.show].
 */
class ListenTogetherActivity : AppCompatActivity(), MusicService.Listener {

    // Discord's dark palette.
    private val cBg = Color.parseColor("#1E1F22")
    private val cPanel = Color.parseColor("#2B2D31")
    private val cBar = Color.parseColor("#111214")
    private val cBlurple = Color.parseColor("#5865F2")
    private val cGreen = Color.parseColor("#23A55A")
    private val cRed = Color.parseColor("#F23F43")
    private val cText = Color.parseColor("#F2F3F5")
    private val cMuted = Color.parseColor("#B5BAC1")
    private val cFaint = Color.parseColor("#80848E")
    private val avatarColors = intArrayOf(
        Color.parseColor("#5865F2"), Color.parseColor("#EB459E"), Color.parseColor("#F0B232"),
        Color.parseColor("#23A55A"), Color.parseColor("#ED4245"), Color.parseColor("#3BA5C8"),
        Color.parseColor("#9B59B6")
    )

    private val main = Handler(Looper.getMainLooper())
    private var dp = 1f
    private fun px(v: Int) = (v * dp).toInt()

    private lateinit var content: LinearLayout       // swapped by render()
    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView
    private lateinit var headerIcon: ImageView
    private lateinit var barHolder: FrameLayout      // bottom control bar (room only)
    private lateinit var scroll: ScrollView

    private var wasActive = false
    private var joinCode: String? = null
    private var autoJoinDone = false

    // Now-playing card views (only valid while a room is shown).
    private var npTitle: TextView? = null
    private var npArtist: TextView? = null
    private var npCover: ImageView? = null
    private var npFill: View? = null
    private var npTrack: View? = null
    private var npCur: TextView? = null
    private var npDur: TextView? = null
    private var npPlay: ImageView? = null
    private var npEmpty: TextView? = null
    private var npBody: View? = null
    private var shownArt = ""

    /** Tiles whose avatar ring follows the play state. */
    private val rings = mutableListOf<View>()

    private val ticker = object : Runnable {
        override fun run() {
            refreshNowPlaying()
            main.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        joinCode = intent?.getStringExtra(EXTRA_JOIN)

        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(cBg))

        setContentView(buildFrame())
        wasActive = ListenTogether.active
    }

    override fun onStart() {
        super.onStart()
        ListenTogether.onChange = { onRoomChanged() }
        MusicService.addGlobalListener(this)
        if (wasActive && !ListenTogether.active) { // the room ended while this screen was in the background
            wasActive = false
            finish()
            return
        }
        render()
        val code = joinCode
        if (!autoJoinDone && !code.isNullOrBlank() && !ListenTogether.active && ListenTogether.isConfigured(this)) {
            autoJoinDone = true
            doJoin(code)
        }
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onStop() {
        MusicService.removeGlobalListener(this)
        ListenTogether.onChange = null
        super.onStop()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_down)
    }

    override fun onMusicStateChanged() { refreshNowPlaying() }

    private fun onRoomChanged() {
        val nowActive = ListenTogether.active
        if (wasActive && !nowActive) { // left, or the host closed the room: back to where we came from
            wasActive = false
            finish()
            return
        }
        wasActive = nowActive
        render()
    }

    // ------------------------------------------------------------------ frame: header + content + bar

    private fun buildFrame(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(4), px(12), px(4))
        }
        val back = iconButton(R.drawable.ic_expand_more, cText, 48, 28).apply {
            contentDescription = getString(android.R.string.cancel)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(px(48), px(48)))

        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_volume_up)
            setColorFilter(cMuted)
        }
        titleRow.addView(headerIcon, LinearLayout.LayoutParams(px(18), px(18)).apply { rightMargin = px(6) })
        headerTitle = TextView(this).apply {
            setTextColor(cText)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        titleRow.addView(headerTitle)
        titles.addView(titleRow)
        headerSub = TextView(this).apply {
            setTextColor(cMuted)
            textSize = 12f
            maxLines = 1
        }
        titles.addView(headerSub)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(2) })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        // Scrolling content
        scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(4), px(12), px(16))
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom bar
        barHolder = FrameLayout(this).apply { setPadding(px(16), px(4), px(16), px(14)) }
        root.addView(barHolder, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        content.removeAllViews()
        barHolder.removeAllViews()
        rings.clear()
        shownArt = ""
        npTitle = null; npArtist = null; npCover = null; npFill = null; npTrack = null
        npCur = null; npDur = null; npPlay = null; npEmpty = null; npBody = null

        if (ListenTogether.active) renderRoom() else renderIdle()
    }

    // ---- not in a room

    private fun renderIdle() {
        headerIcon.visibility = View.GONE
        headerTitle.setText(R.string.listen_title)
        headerSub.visibility = View.GONE

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(8), px(28), px(8), px(24))
        }
        val badge = FrameLayout(this).apply { background = oval(cBlurple) }
        badge.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_headphones)
            setColorFilter(Color.WHITE)
        }, FrameLayout.LayoutParams(px(48), px(48), Gravity.CENTER))
        hero.addView(badge, LinearLayout.LayoutParams(px(96), px(96)))
        hero.addView(text(getString(R.string.listen_idle_title), 22f, cText, bold = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20) })
        hero.addView(text(getString(R.string.listen_desc), 14f, cMuted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))

        val create = pillButton(getString(R.string.listen_create), cBlurple, Color.WHITE).apply {
            setOnClickListener {
                isEnabled = false
                alpha = 0.6f
                ListenTogether.host(this@ListenTogetherActivity) { fail, _ ->
                    if (fail != null) { isEnabled = true; alpha = 1f; toast(reason(fail)) }
                }
            }
        }
        content.addView(create, LinearLayout.LayoutParams(-1, px(50)))

        // "OR" divider
        val orRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun line() = View(this).apply { setBackgroundColor(cPanel) }
        orRow.addView(line(), LinearLayout.LayoutParams(0, px(1), 1f))
        orRow.addView(text(getString(R.string.listen_idle_or), 11f, cFaint, bold = true),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(12); rightMargin = px(12) })
        orRow.addView(line(), LinearLayout.LayoutParams(0, px(1), 1f))
        content.addView(orRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20); bottomMargin = px(20) })

        // Join with a code
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cPanel, 16)
            setPadding(px(16), px(16), px(16), px(16))
        }
        card.addView(text(getString(R.string.listen_code_label), 11f, cMuted, bold = true))
        val input = EditText(this).apply {
            hint = getString(R.string.listen_code_hint)
            setHintTextColor(cFaint)
            setTextColor(cText)
            textSize = 24f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(6))
            background = rounded(cBg, 10)
            setPadding(px(12), px(12), px(12), px(12))
            joinCode?.let { setText(it.uppercase().take(6)) }
        }
        card.addView(input, LinearLayout.LayoutParams(-1, px(56)).apply { topMargin = px(10) })
        val join = pillButton(getString(R.string.listen_join), cGreen, Color.WHITE).apply {
            setOnClickListener { doJoin(input.text.toString()) }
        }
        card.addView(join, LinearLayout.LayoutParams(-1, px(48)).apply { topMargin = px(12) })
        content.addView(card, LinearLayout.LayoutParams(-1, -2))

        if (!ListenTogether.isConfigured(this)) {
            content.addView(text(getString(R.string.listen_err_config), 12f, cFaint).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
        }
        content.addView(text(getString(R.string.listen_server), 13f, cMuted).apply {
            gravity = Gravity.CENTER
            setPadding(px(12), px(14), px(12), px(14))
            setOnClickListener { serverDialog() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
    }

    // ---- in a room

    private fun renderRoom() {
        val host = ListenTogether.role == ListenTogether.Role.HOST
        headerIcon.visibility = View.VISIBLE
        headerTitle.text = getString(R.string.listen_channel_fmt, ListenTogether.code)
        headerSub.visibility = View.VISIBLE
        headerSub.text = getString(R.string.listen_connected) + " • " +
            getString(R.string.listen_members_fmt, ListenTogether.members)

        // Members
        val ids = memberOrder()
        content.addView(
            text(getString(R.string.listen_voice_members, ids.size), 11f, cMuted, bold = true),
            LinearLayout.LayoutParams(-1, -2).apply { leftMargin = px(4); topMargin = px(6); bottomMargin = px(8) }
        )
        val cols = if (ids.size == 1) 1 else 2
        val tileH = if (ids.size == 1) px(200) else px(148)
        var i = 0
        var guestNo = 0
        val labels = ArrayList<String>()
        val initials = ArrayList<String>()
        for (id in ids) {
            when {
                id == ListenTogether.myId -> {
                    labels.add(getString(R.string.listen_you)); initials.add(labels.last().take(1).uppercase())
                }
                id == ListenTogether.hostId -> {
                    labels.add(getString(R.string.listen_host_tag)); initials.add(labels.last().take(1).uppercase())
                }
                else -> {
                    guestNo++
                    labels.add(getString(R.string.listen_guest_fmt, guestNo)); initials.add(guestNo.toString())
                }
            }
        }
        while (i < ids.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val idx = i + c
                if (idx < ids.size) {
                    val tile = memberTile(ids[idx], labels[idx], initials[idx])
                    row.addView(tile, LinearLayout.LayoutParams(0, tileH, 1f).apply {
                        if (c > 0) leftMargin = px(8)
                    })
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, tileH, 1f).apply { leftMargin = px(8) })
                }
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
            i += cols
        }

        // Now playing
        content.addView(nowPlayingCard(host), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
        refreshNowPlaying()

        // Up next (shared queue)
        content.addView(upNextSection(host), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10); bottomMargin = px(8) })

        // Floating control bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(cBar, 32)
            setPadding(px(12), px(10), px(12), px(10))
        }
        val copy = roundButton(R.drawable.ic_copy, cPanel, cText).apply {
            contentDescription = getString(R.string.listen_copy_code)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("room", ListenTogether.code))
                toast(R.string.listen_code_copied)
            }
        }
        val invite = roundButton(R.drawable.ic_person_add, cBlurple, Color.WHITE).apply {
            contentDescription = getString(R.string.listen_invite)
            setOnClickListener {
                val msg = getString(R.string.listen_invite_text, ListenTogether.code, ListenTogether.shareLink())
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg)
                startActivity(Intent.createChooser(send, getString(R.string.listen_invite)))
            }
        }
        val leave = FrameLayout(this).apply {
            background = ripple(rounded(cRed, 26))
            contentDescription = getString(if (host) R.string.listen_close else R.string.listen_leave)
            isClickable = true
            isFocusable = true
            setOnClickListener { ListenTogether.leave() }
        }
        leave.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_call_end)
            setColorFilter(Color.WHITE)
        }, FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER))
        bar.addView(copy, LinearLayout.LayoutParams(px(52), px(52)))
        bar.addView(invite, LinearLayout.LayoutParams(px(52), px(52)).apply { leftMargin = px(14) })
        bar.addView(leave, LinearLayout.LayoutParams(px(84), px(52)).apply { leftMargin = px(14) })
        barHolder.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
    }

    /** Host first, then us, then everyone else; always contains us and the host. */
    private fun memberOrder(): List<String> {
        val me = ListenTogether.myId
        val hostId = ListenTogether.hostId
        val all = LinkedHashSet<String>()
        if (hostId.isNotBlank()) all.add(hostId)
        all.add(me)
        ListenTogether.memberIds.forEach { all.add(it) }
        return all.toList()
    }

    private fun memberTile(id: String, name: String, initial: String): View {
        val isHost = id == ListenTogether.hostId
        val tile = FrameLayout(this).apply { background = rounded(cPanel, 14) }

        // Avatar with a ring that lights up (green) while music plays.
        val ringBox = FrameLayout(this)
        val ring = View(this)
        ringBox.addView(ring, FrameLayout.LayoutParams(-1, -1))
        rings.add(ring)
        val color = avatarColors[abs(id.hashCode()) % avatarColors.size]
        val avatar = TextView(this).apply {
            text = initial
            gravity = Gravity.CENTER
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = oval(color)
        }
        ringBox.addView(avatar, FrameLayout.LayoutParams(px(66), px(66), Gravity.CENTER))
        tile.addView(ringBox, FrameLayout.LayoutParams(px(78), px(78), Gravity.CENTER))

        // Name chip, bottom-left.
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#B3000000"), 8)
            setPadding(px(8), px(3), px(8), px(3))
        }
        if (isHost) {
            chip.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_crown)
                setColorFilter(Color.parseColor("#F0B232"))
            }, LinearLayout.LayoutParams(px(14), px(14)).apply { rightMargin = px(5) })
        }
        chip.addView(text(name, 12f, cText, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        tile.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = px(8); bottomMargin = px(8)
        })
        return tile
    }

    private fun nowPlayingCard(host: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cPanel, 16)
            setPadding(px(16), px(14), px(16), px(16))
        }
        // Label row
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        label.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_headphones)
            setColorFilter(cGreen)
        }, LinearLayout.LayoutParams(px(16), px(16)).apply { rightMargin = px(6) })
        label.addView(text(getString(R.string.listen_np_label), 11f, cGreen, bold = true))
        card.addView(label)

        // Empty state
        val empty = text(
            getString(if (host) R.string.listen_np_empty_host else R.string.listen_np_empty_guest), 14f, cMuted
        ).apply { setPadding(0, px(12), 0, px(4)) }
        npEmpty = empty
        card.addView(empty)

        // Body (cover + titles + progress + controls)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        npBody = body
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(cBg)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 10 * dp) }
            }
        }
        npCover = cover
        top.addView(cover, LinearLayout.LayoutParams(px(76), px(76)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = text("", 16f, cText, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val artist = text("", 13f, cMuted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        npTitle = title
        npArtist = artist
        titles.addView(title)
        titles.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2) })
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(14) })
        body.addView(top, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })

        // Progress bar
        val track = FrameLayout(this).apply { background = rounded(cBg, 3) }
        val fill = View(this).apply { background = rounded(cGreen, 3) }
        track.addView(fill, FrameLayout.LayoutParams(0, -1))
        npTrack = track
        npFill = fill
        body.addView(track, LinearLayout.LayoutParams(-1, px(5)).apply { topMargin = px(16) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cur = text("0:00", 11f, cFaint)
        val dur = text("0:00", 11f, cFaint).apply { gravity = Gravity.END }
        npCur = cur
        npDur = dur
        times.addView(cur, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(dur, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })

        if (host) {
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val prev = roundButton(R.drawable.ic_prev, Color.TRANSPARENT, cText).apply {
                setOnClickListener { MusicService.instance?.previous() }
            }
            val play = roundButton(R.drawable.ic_play, Color.WHITE, Color.parseColor("#111214")).apply {
                setOnClickListener { MusicService.instance?.togglePlayPause() }
            }
            npPlay = play.getChildAt(0) as ImageView
            val next = roundButton(R.drawable.ic_next, Color.TRANSPARENT, cText).apply {
                setOnClickListener { MusicService.instance?.next() }
            }
            controls.addView(prev, LinearLayout.LayoutParams(px(48), px(48)))
            controls.addView(play, LinearLayout.LayoutParams(px(56), px(56)).apply {
                leftMargin = px(20); rightMargin = px(20)
            })
            controls.addView(next, LinearLayout.LayoutParams(px(48), px(48)))
            body.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        } else {
            body.addView(text(getString(R.string.listen_np_guest_hint), 12f, cFaint).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        }
        card.addView(body)
        return card
    }

    /** Updates the now-playing card and the avatar rings from the player (cheap; runs every second). */
    private fun refreshNowPlaying() {
        if (!ListenTogether.active || npBody == null) return
        val svc = MusicService.instance
        val t = svc?.current
        val has = svc != null && t != null // radio too: a live station has no length, the card shows --:--
        npBody?.visibility = if (has) View.VISIBLE else View.GONE
        npEmpty?.visibility = if (has) View.GONE else View.VISIBLE
        val playing = has && svc!!.isPlaying
        rings.forEach { it.background = ringDrawable(playing) }
        if (!has) return

        npTitle?.text = t!!.title
        npArtist?.text = t.artist
        val art = t.artUrl
        if (art != shownArt) {
            shownArt = art
            npCover?.setImageDrawable(null)
            if (art.isNotBlank()) {
                ImageLoader.load(art) { bmp ->
                    if (bmp != null && art == shownArt) npCover?.setImageBitmap(bmp)
                }
            }
        }
        npPlay?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        val dur = svc!!.duration
        val pos = svc.position.coerceIn(0, if (dur > 0) dur else Int.MAX_VALUE)
        npCur?.text = fmt(pos)
        npDur?.text = if (dur > 0) fmt(dur) else "--:--"
        val track = npTrack
        val fill = npFill
        if (track != null && fill != null) {
            val w = if (dur > 0) (track.width.toLong() * pos / dur).toInt() else 0
            val lp = fill.layoutParams
            if (lp.width != w) { lp.width = w; fill.layoutParams = lp }
        }
    }

    // ------------------------------------------------------------------ up next / suggestions

    private fun upNextSection(host: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cPanel, 12)
            setPadding(px(16), px(14), px(16), px(14))
        }
        box.addView(text(getString(R.string.listen_upnext), 11f, cMuted, bold = true))
        val list = ListenTogether.upNext
        if (list.isEmpty()) {
            box.addView(text(getString(R.string.listen_upnext_empty), 13f, cFaint),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        } else {
            list.take(8).forEachIndexed { i, (title, artist) ->
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(text("${i + 1}. $title", 14f, cText).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (artist.isNotBlank()) texts.addView(text(artist, 12f, cMuted).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (host) row.addView(text("\u2715", 16f, cMuted).apply { // host removes a song from the queue
                    gravity = Gravity.CENTER
                    setPadding(px(14), px(8), px(6), px(8))
                    contentDescription = getString(R.string.listen_remove_song)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { ListenTogether.removeUpNext(i, title) }
                }, LinearLayout.LayoutParams(-2, -2))
                box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
            }
        }
        if (!host) {
            box.addView(pillButton(getString(R.string.listen_add_song), cBlurple, Color.WHITE).apply {
                setOnClickListener { suggestDialog() }
            }, LinearLayout.LayoutParams(-1, px(46)).apply { topMargin = px(14) })
        }
        return box
    }

    /** Guest: type a search, pick a result, and it is sent to the host's queue. */
    private fun suggestDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.listen_search_hint)
            setSingleLine()
        }
        val wrap = LinearLayout(this).apply {
            setPadding(px(24), px(8), px(24), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.listen_add_song)
            .setView(wrap)
            .setPositiveButton(R.string.listen_search_go) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                toast(R.string.listen_searching)
                val act = this@ListenTogetherActivity
                Thread {
                    val res = try { YouTubeApi.userSearch(act, q).take(10) } catch (e: Exception) { emptyList() }
                    act.runOnUiThread {
                        if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                        if (res.isEmpty()) toast(R.string.listen_search_none) else pickDialog(res)
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickDialog(results: List<Track>) {
        val names = results.map { if (it.artist.isBlank()) it.title else "${it.title} — ${it.artist}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.listen_add_song)
            .setItems(names) { _, i ->
                ListenTogether.request(results[i]) { ok ->
                    toast(if (ok) R.string.listen_request_sent else R.string.listen_err_network)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ actions

    private fun doJoin(code: String) {
        ListenTogether.join(this, code) { fail, _ ->
            if (fail != null) toast(reason(fail))
        }
    }

    private fun serverDialog() {
        val input = EditText(this).apply {
            setText(ListenTogether.dbUrl(this@ListenTogetherActivity))
            hint = getString(R.string.listen_server_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val wrap = LinearLayout(this).apply {
            setPadding(px(24), px(8), px(24), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.listen_server_title)
            .setMessage(R.string.listen_server_msg)
            .setView(wrap)
            .setPositiveButton(R.string.listen_save) { _, _ ->
                ListenTogether.setDbUrl(this, input.text.toString())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reason(f: ListenTogether.Fail) = when (f) {
        ListenTogether.Fail.NOT_CONFIGURED -> R.string.listen_err_config
        ListenTogether.Fail.NETWORK -> R.string.listen_err_network
        ListenTogether.Fail.NOT_FOUND -> R.string.listen_err_notfound
        ListenTogether.Fail.BUSY -> R.string.listen_err_busy
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ view helpers

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * dp
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun ringDrawable(on: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(px(3), if (on) cGreen else Color.TRANSPARENT)
    }

    private fun ripple(content: android.graphics.drawable.Drawable) =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

    private fun pillButton(label: String, bg: Int, fg: Int) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(fg)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ripple(rounded(bg, 10))
        isClickable = true
        isFocusable = true
    }

    /** Plain tappable icon (no background), [box] dp square, glyph [glyph] dp. */
    private fun iconButton(res: Int, tint: Int, box: Int, glyph: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        val pad = px((box - glyph) / 2)
        setPadding(pad, pad, pad, pad)
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF), null, oval(Color.WHITE)
        )
    }

    /** Round button holding one icon; the icon is child 0 (so callers can swap it). */
    private fun roundButton(res: Int, bg: Int, fg: Int) = FrameLayout(this).apply {
        background = ripple(oval(bg))
        isClickable = true
        isFocusable = true
        addView(ImageView(context).apply {
            setImageResource(res)
            setColorFilter(fg)
        }, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
    }

    companion object {
        const val EXTRA_JOIN = "join"
    }
}
