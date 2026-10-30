package com.fxsoundlite.app

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.TypedValue
import android.view.Gravity
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.fxsoundlite.app.databinding.ActivityNowPlayingBinding

/** Full-screen player, opened by tapping the mini player. Shares MusicService with everything else. */
class NowPlayingActivity : AppCompatActivity(), MusicService.Listener {

    private lateinit var binding: ActivityNowPlayingBinding
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false

    // Lyrics mode (replaces the cover): which track the lyrics belong to and what to show.
    private var lyricsMode = false
    private var lyricsKey: String? = null
    private var lyrics: LyricsApi.Result? = null
    private var lyricLineStarts = IntArray(0)
    private var lyricIdx = -2
    private enum class LyricsView { ORIGINAL, ROMAN, TRANSLATE }
    private var lyricsView = LyricsView.ORIGINAL
    private var romanLines: List<String>? = null
    private var transLines: List<String>? = null
    private val lyricsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            updateCoverFx() // safety net: picks up play/pause changes that came without a callback
            handler.postDelayed(this, if (MusicPrefs.saver(this@NowPlayingActivity)) 1500L else if (lyricsMode) 150L else 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        PlayerMode.init(this) // load the saved mode first, or it would overwrite this setMusic() later
        PlayerMode.setMusic() // music player in use: the mini bar opens this one again
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_OVER_LOCK, false)) {
            if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
            else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCover()
        volumeControlStream = AudioManager.STREAM_MUSIC // tombol volume fisik tetap mengatur media
        binding.npLyricsBtn.setOnClickListener { setLyricsMode(!lyricsMode) }
        binding.npModeOriginal.setOnClickListener { setLyricsView(LyricsView.ORIGINAL) }
        binding.npModeRoman.setOnClickListener { setLyricsView(LyricsView.ROMAN) }
        binding.npModeTranslate.setOnClickListener {
            // First tap: translate; tap again while selected: choose Indonesian / English.
            if (lyricsView == LyricsView.TRANSLATE) showTransLangDialog() else setLyricsView(LyricsView.TRANSLATE)
        }
        updateModeChips()
        binding.npClose.setOnClickListener { finish() }
        binding.npMore.setOnClickListener { showMoreMenu(it) }
        binding.npVolume.setOnClickListener { showSystemVolume() }
        binding.npQueue.setOnClickListener { showQueue() }
        binding.npBg.setOnClickListener {
            MusicPrefs.setPlayerBgScreensaver(this, !MusicPrefs.playerBgScreensaver(this))
            applyPlayerBackground()
            syncWash()
        }
        binding.npAddPlaylist.setOnClickListener {
            MusicService.instance?.current?.let { t -> PlaylistUi.addToPlaylist(this, t) { } }
        }
        binding.npVideo.setOnClickListener { openVideo() }
        binding.npPlay.setOnClickListener { MusicService.instance?.togglePlayPause() }
        binding.npNext.setOnClickListener { MusicService.instance?.next() }
        binding.npPrev.setOnClickListener { MusicService.instance?.previous() }
        binding.npShuffle.setOnClickListener { MusicService.instance?.toggleShuffle() }
        binding.npRepeat.setOnClickListener { MusicService.instance?.cycleRepeat() }
        binding.npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.npElapsed.text = formatDuration(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                MusicService.instance?.seekTo(sb?.progress ?: 0)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        MusicService.addGlobalListener(this)
        onMusicStateChanged()
        handler.post(tick)
        applyPlayerBackground() // also picks up a Screensaver background changed in Settings
        syncWash()
        binding.npArt.invalidateOutline() // and a cover shape changed in Settings
        applyCoverSize()
        updateCoverFx()
    }

    override fun onStop() {
        MusicService.removeGlobalListener(this)
        handler.removeCallbacks(tick)
        binding.npBgAnimated.stop() // no animation loop while the player is off screen
        stopCoverLoop()
        super.onStop()
    }

    private var bgToken = 0
    private var bgImageUri: String? = null

    /**
     * Player background: plain theme colour, or whatever the Screensaver page uses (an animated
     * style, or a picked photo). A picked video is not played here (it would keep decoding behind
     * the player), so that case stays plain.
     */
    /** True while the Screensaver's animated style / photo is the player background (shown without any filter). */
    private var screensaverBgShown = false

    private fun applyPlayerBackground() {
        val b = binding
        bgToken++
        val token = bgToken
        val bgOn = MusicPrefs.playerBgScreensaver(this)
        b.npBg.imageTintList = ColorStateList.valueOf(
            (if (bgOn) themeAccent() else color(R.attr.colorTextSecondary))
        )
        val type = ScreensaverPrefs.mediaType(this)
        val uri = ScreensaverPrefs.videoUri(this)
        val wantsAnimated = MusicPrefs.playerBgScreensaver(this) && type == ScreensaverPrefs.TYPE_ANIMATED
        val wantsImage = MusicPrefs.playerBgScreensaver(this) && type == ScreensaverPrefs.TYPE_IMAGE && !uri.isNullOrBlank()

        if (!wantsImage) {
            b.npBgImage.visibility = View.GONE
            b.npBgImage.setImageDrawable(null)
            bgImageUri = null
        }
        if (!wantsAnimated) {
            b.npBgAnimated.stop()
            b.npBgAnimated.visibility = View.GONE
        }
        // The Screensaver background is shown as it is: no dimming / white filter over it.
        b.npBgScrim.visibility = View.GONE
        if (!wantsAnimated && !wantsImage) {
            screensaverBgShown = false
            return
        }
        screensaverBgShown = true

        if (wantsAnimated) {
            b.npBgAnimated.style = when (ScreensaverPrefs.animatedStyle(this)) {
                ScreensaverPrefs.STYLE_RAIN -> SleepBackgroundView.Style.RAIN
                ScreensaverPrefs.STYLE_STARFIELD -> SleepBackgroundView.Style.STARFIELD
                else -> SleepBackgroundView.Style.NIGHT_SKY
            }
            b.npBgAnimated.visibility = View.VISIBLE
            b.npBgAnimated.start()
            return
        }
        // Photo: decode off the main thread, shrunk to about the screen size.
        if (uri == bgImageUri && b.npBgImage.drawable != null) {
            b.npBgImage.visibility = View.VISIBLE
            return
        }
        bgImageUri = uri
        val dm = resources.displayMetrics
        val app = applicationContext
        lyricsExecutor.execute {
            val bmp = decodeBackground(app, android.net.Uri.parse(uri), dm.widthPixels, dm.heightPixels)
            handler.post {
                if (isDestroyed || token != bgToken) return@post
                if (bmp == null) {
                    screensaverBgShown = false
                    bgImageUri = null
                    syncWash()
                    return@post
                }
                b.npBgImage.setImageBitmap(bmp)
                b.npBgImage.visibility = View.VISIBLE
            }
        }
    }

    private fun decodeBackground(ctx: Context, uri: android.net.Uri, reqW: Int, reqH: Int): android.graphics.Bitmap? = try {
        fun sample(w: Int, h: Int): Int {
            var s = 1
            while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2
            return s
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val s = sample(info.size.width, info.size.height)
                if (s > 1) dec.setTargetSampleSize(s)
            }
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight) }
            ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Throwable) { null }

    override fun onDestroy() {
        lyricsExecutor.shutdown()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_down)
    }

    override fun onMusicStateChanged() {
        val s = MusicService.instance
        val t = s?.current
        if (s == null || t == null) { finish(); return }
        binding.npTitle.text = t.title
        val from = s.playingFrom
        binding.npFromBox.visibility = if (from == null) View.GONE else View.VISIBLE
        if (from != null) binding.npFromName.text = from
        if (lyricsMode && t.key != lyricsKey) loadLyrics(t)
        val artistLine = t.artist.ifBlank { t.album }
        binding.npArtist.text = if (s.isSmartPick(t)) "\u2726 " + getString(R.string.np_smart_pick) + " \u2022 " + artistLine else artistLine
        val live = t.source == Track.SOURCE_RADIO
        binding.npTotal.text = if (live) getString(R.string.np_live) else formatDuration(s.duration.toLong())
        binding.npSeek.max = s.duration.coerceAtLeast(0)
        binding.npSeek.isEnabled = !live
        binding.npPlay.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        CoverUi.apply(binding.npArt, s.artwork, MusicPrefs.saver(this), 80)
        applyCoverTheme(s.artwork)
        updateCoverFx()

        // Live radio has no queue order to shuffle or repeat: hide both (equal width, so play stays centred).
        val radioVis = if (live) View.GONE else View.VISIBLE
        binding.npShuffle.visibility = radioVis
        binding.npRepeat.visibility = radioVis

        val accent = themeAccent()
        val inactive = color(R.attr.colorTextSecondary)
        binding.npShuffle.setImageResource(if (s.smartShuffle) R.drawable.ic_shuffle_smart else R.drawable.ic_shuffle)
        binding.npShuffle.contentDescription = getString(if (s.smartShuffle) R.string.shuffle_mode_smart else R.string.music_shuffle)
        binding.npShuffle.imageTintList = ColorStateList.valueOf(if (s.shuffle) accent else inactive)
        binding.npRepeat.setImageResource(
            if (s.repeatMode == MusicService.REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        binding.npRepeat.imageTintList =
            ColorStateList.valueOf(if (s.repeatMode == MusicService.REPEAT_OFF) inactive else accent)
        updateProgress()
        updateQualityBadge(t)
    }

    private var qualityKey: String? = null

    /** Shows the file's format / sample rate / bitrate to the right of the artist (read once per song, then cached). */
    private fun updateQualityBadge(t: Track) {
        val tv = binding.npQuality
        fun showText(text: String?) {
            if (text.isNullOrEmpty()) { tv.visibility = View.GONE; return }
            tv.text = text
            tv.visibility = View.VISIBLE
        }
        fun show(info: AudioInfo?) {
            val quality = info?.let { if (it.hiRes) "${it.badge()}  \u2022 Hi-Res" else it.badge() }
            showText(quality)
        }
        // YouTube: the format can change while playing (stream resolved, or a fall-back to the web player),
        // and nothing needs reading from disk, so it is refreshed on every update.
        if (t.source == Track.SOURCE_YOUTUBE) {
            val svc = MusicService.instance
            val info = svc?.youtubeAudioInfo
            when {
                info != null -> show(info)
                else -> showText(null)
            }
            return
        }
        if (qualityKey == t.key && tv.visibility == View.VISIBLE) return
        qualityKey = t.key
        if (t.source == Track.SOURCE_RADIO) { show(AudioQuality.radioInfo(t)); return }
        if (AudioQuality.isCached(t)) { show(AudioQuality.cached(t)); return }
        tv.visibility = View.GONE
        // Only device files can be probed on the spot; other sources fill the cache themselves
        // (a downloaded Drive copy).
        if (t.source != Track.SOURCE_LOCAL) return
        val ctx = applicationContext
        lyricsExecutor.execute {
            val info = AudioQuality.probe(ctx, t)
            runOnUiThread { if (!isDestroyed && qualityKey == t.key) show(info) }
        }
    }

    private fun updateProgress() {
        val s = MusicService.instance ?: return
        if (dragging) return
        val pos = if (s.current?.source == Track.SOURCE_RADIO) 0 else s.position
        binding.npSeek.progress = pos
        binding.npElapsed.text = formatDuration(pos.toLong())
        if (lyricsMode) updateLyricHighlight(pos)
    }

    // ---------------------------------------------------------------- lyrics mode

    private fun setLyricsMode(on: Boolean) {
        lyricsMode = on
        // The cover frame doesn't clip (so the neon can spill out); lyrics must stay inside it,
        // otherwise the scrolled text is drawn over the whole screen.
        (binding.npArt.parent as? android.view.ViewGroup)?.clipChildren = on
        binding.npArt.visibility = if (on) View.GONE else View.VISIBLE
        binding.npWaves.visibility = if (on) View.GONE else View.VISIBLE
        updateCoverFx()
        binding.npLyricsScroll.visibility = if (on) View.VISIBLE else View.GONE
        binding.npLyricsModes.visibility = if (on) View.VISIBLE else View.GONE
        binding.npLyricsBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            (if (on) themeAccent() else color(R.attr.colorTextSecondary))
        )
        if (on) MusicService.instance?.current?.let { if (it.key != lyricsKey) loadLyrics(it) else applyLyricsView() }
    }

    private fun showLyricsMessage(res: Int) {
        lyrics = null
        lyricLineStarts = IntArray(0)
        binding.npLyrics.setTextColor(color(R.attr.colorTextSecondary))
        binding.npLyrics.setText(res)
    }

    private fun loadLyrics(t: Track) {
        lyricsKey = t.key
        lyrics = null
        romanLines = null
        transLines = null
        lyricIdx = -2
        binding.npLyricsScroll.scrollTo(0, 0)
        if (t.source == Track.SOURCE_RADIO) { showLyricsMessage(R.string.np_lyrics_none); return }
        val hit = LyricsApi.cached(t.key)
        if (hit != null) { lyrics = hit; applyLyricsView(); return }
        showLyricsMessage(R.string.np_lyrics_loading)
        val key = t.key
        lyricsExecutor.execute {
            var res: LyricsApi.Result? = null
            var net = false
            try {
                res = LyricsApi.fetch(key, t.title, t.artist, t.durationMs,
                    if (t.source == Track.SOURCE_YOUTUBE) t.remoteId else "")
            } catch (e: java.io.IOException) {
                net = true
            } catch (e: Exception) {
                // malformed reply: treated as "not found"
            }
            handler.post {
                if (isDestroyed || key != lyricsKey) return@post // another song started meanwhile
                if (res != null) { lyrics = res; applyLyricsView() }
                else showLyricsMessage(if (net) R.string.np_lyrics_network else R.string.np_lyrics_none)
            }
        }
    }

    private fun originalLines(r: LyricsApi.Result): List<String> =
        if (r.synced.isNotEmpty()) r.synced.map { it.text } else r.plain.lines()

    private fun updateModeChips() {
        binding.npModeOriginal.isSelected = lyricsView == LyricsView.ORIGINAL
        binding.npModeRoman.isSelected = lyricsView == LyricsView.ROMAN
        binding.npModeTranslate.isSelected = lyricsView == LyricsView.TRANSLATE
        binding.npModeTranslate.setText(
            if (MusicPrefs.lyricsTransLang(this) == "en") R.string.np_lyrics_translate_en else R.string.np_lyrics_translate
        )
    }

    private fun showTransLangDialog() {
        val langs = arrayOf("id", "en")
        val labels = arrayOf(getString(R.string.np_lyrics_translate), getString(R.string.np_lyrics_translate_en))
        val cur = MusicPrefs.lyricsTransLang(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.np_lyrics_translate_to)
            .setSingleChoiceItems(labels, langs.indexOf(cur)) { d, which ->
                d.dismiss()
                if (langs[which] != cur) {
                    MusicPrefs.setLyricsTransLang(this, langs[which])
                    transLines = null
                    updateModeChips()
                    applyLyricsView()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setLyricsView(v: LyricsView) {
        lyricsView = v
        updateModeChips()
        applyLyricsView()
    }

    private fun backToOriginal(msg: Int) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        lyricsView = LyricsView.ORIGINAL
        updateModeChips()
        renderLyrics()
    }

    /** Shows the lyrics in the chosen view, computing the romanized / translated text if needed. */
    private fun applyLyricsView() {
        val r = lyrics ?: return
        val key = lyricsKey ?: return
        when (lyricsView) {
            LyricsView.ORIGINAL -> renderLyrics()
            LyricsView.ROMAN -> {
                if (romanLines != null) { renderLyrics(); return }
                val src = originalLines(r)
                if (!LyricsText.needsRoman(src)) { backToOriginal(R.string.np_lyrics_already_latin); return }
                lyricsExecutor.execute {
                    val out = try { LyricsText.romanize(src) } catch (e: Throwable) { null }
                    handler.post {
                        if (isDestroyed || key != lyricsKey) return@post
                        if (out == null) { backToOriginal(R.string.np_lyrics_none); return@post }
                        romanLines = out
                        if (lyricsView == LyricsView.ROMAN) renderLyrics()
                    }
                }
            }
            LyricsView.TRANSLATE -> {
                if (transLines != null) { renderLyrics(); return }
                lyricLineStarts = IntArray(0)
                binding.npLyrics.setTextColor(color(R.attr.colorTextSecondary))
                binding.npLyrics.setText(R.string.np_lyrics_translating)
                val src = originalLines(r)
                val lang = MusicPrefs.lyricsTransLang(this)
                lyricsExecutor.execute {
                    val out = try { LyricsText.translate(src, lang) } catch (e: Exception) { null }
                    handler.post {
                        if (isDestroyed || key != lyricsKey) return@post
                        if (out == null) {
                            if (lyricsView == LyricsView.TRANSLATE) backToOriginal(R.string.np_lyrics_translate_failed)
                            return@post
                        }
                        if (lang != MusicPrefs.lyricsTransLang(this)) return@post // language changed meanwhile
                        transLines = out
                        if (lyricsView == LyricsView.TRANSLATE) renderLyrics()
                    }
                }
            }
        }
    }

    /** Fills the lyrics view: synced lyrics get a highlighted current line, plain ones just text. */
    private fun renderLyrics() {
        val r = lyrics ?: return
        val tv = binding.npLyrics
        lyricIdx = -2
        val lines = when (lyricsView) {
            LyricsView.ROMAN -> romanLines
            LyricsView.TRANSLATE -> transLines
            else -> null
        } ?: originalLines(r)
        if (r.synced.isNotEmpty() && lines.size == r.synced.size) {
            val sb = StringBuilder()
            lyricLineStarts = IntArray(lines.size)
            lines.forEachIndexed { i, l ->
                lyricLineStarts[i] = sb.length
                sb.append(l.ifBlank { "♪" })
                if (i < lines.size - 1) sb.append('\n')
            }
            lyricsText = sb.toString()
            updateLyricHighlight(MusicService.instance?.position ?: 0)
        } else {
            lyricLineStarts = IntArray(0)
            tv.setTextColor(color(R.attr.colorTextPrimary))
            tv.text = lines.joinToString("\n")
        }
    }

    private var lyricsText = ""

    private fun updateLyricHighlight(posMs: Int) {
        val r = lyrics ?: return
        if (r.synced.isEmpty() || lyricLineStarts.size != r.synced.size) return
        var idx = -1
        for (i in r.synced.indices) { if (r.synced[i].timeMs <= posMs + 250) idx = i else break }
        if (idx == lyricIdx) return
        lyricIdx = idx
        val accent = themeAccent()
        val dim = color(R.attr.colorTextSecondary)
        val sp = SpannableString(lyricsText)
        sp.setSpan(ForegroundColorSpan(dim), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (idx >= 0) {
            val start = lyricLineStarts[idx]
            val end = if (idx + 1 < lyricLineStarts.size) lyricLineStarts[idx + 1] - 1 else sp.length
            sp.setSpan(ForegroundColorSpan(accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val tv = binding.npLyrics
        tv.text = sp
        if (idx >= 0) tv.post {
            val layout = tv.layout ?: return@post
            val first = layout.getLineForOffset(lyricLineStarts[idx])
            val endOff = if (idx + 1 < lyricLineStarts.size) lyricLineStarts[idx + 1] - 1 else lyricsText.length
            val last = layout.getLineForOffset((endOff - 1).coerceAtLeast(lyricLineStarts[idx]))
            val mid = (layout.getLineTop(first) + layout.getLineBottom(last)) / 2
            val y = tv.paddingTop + mid - binding.npLyricsScroll.height / 2
            binding.npLyricsScroll.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    /** Menampilkan panel volume bawaan perangkat (stream media), tanpa mengubah level. */
    private fun showSystemVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    /** Cover art: a square view sized to the free space (max 360dp), clipped to a square or a circle (Settings > Player). */
    private fun setupCover() {
        val art = binding.npArt
        art.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (MusicPrefs.coverCircle(this@NowPlayingActivity)) outline.setOval(0, 0, view.width, view.height)
                else outline.setRoundRect(0, 0, view.width, view.height, coverCornerPx())
            }
        }
        art.clipToOutline = true
        binding.npWaves.target = art
        // Let the waves spill outside the frame around the (full-size) cover.
        var v: View? = art.parent as? View
        while (v is android.view.ViewGroup) { v.clipChildren = false; v.clipToPadding = false; v = v.parent as? View }
        binding.npWaves.cornerRadius = coverCornerPx()
        binding.npWaves.waveColor = accentColor()
        (art.parent as View).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCoverSize() }
    }

    /** Corner radius of the square cover and of the waves around it (rounded, not sharp). */
    private fun coverCornerPx(): Float = 24f * resources.displayMetrics.density

    /** Cover bulat dan kotak berukuran sama (penuh, max 360dp); gelombang meluber keluar frame. */
    private fun applyCoverSize() {
        val art = binding.npArt
        val frame = art.parent as View
        val maxSize = (360 * resources.displayMetrics.density).toInt()
        val free = minOf(
            frame.width - frame.paddingLeft - frame.paddingRight,
            frame.height - frame.paddingTop - frame.paddingBottom
        )
        val base = minOf(free, maxSize)
        val size = base.coerceAtLeast(0) // square cover = same size as the circle one
        val lp = art.layoutParams
        if (lp.width != size || lp.height != size) {
            lp.width = size
            lp.height = size
            art.post { art.layoutParams = lp }
        }
    }

    private var fxRunning = false
    private var fxLastNs = 0L

    /** One frame of the cover effect: rotates the circle cover (20 s per turn) or advances the waves. */
    private val fxFrame = object : Runnable {
        override fun run() {
            if (!fxRunning) return
            val now = System.nanoTime()
            val dt = ((now - fxLastNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
            fxLastNs = now
            if (MusicPrefs.coverCircle(this@NowPlayingActivity)) {
                binding.npArt.rotation = (binding.npArt.rotation + dt * 18f) % 360f
            } else {
                binding.npWaves.advance(dt)
            }
            binding.npArt.postOnAnimation(this)
        }
    }

    private fun startCoverLoop() {
        if (fxRunning) return
        fxRunning = true
        fxLastNs = System.nanoTime()
        binding.npArt.postOnAnimation(fxFrame)
    }

    private fun stopCoverLoop() {
        fxRunning = false
        binding.npArt.removeCallbacks(fxFrame)
    }

    /**
     * Efek cover sesuai bentuk: bulat -> berputar saat lagu diputar (berhenti di sudut terakhir saat
     * pause); kotak -> gelombang memancar keluar saat lagu diputar. Digerakkan frame loop sendiri
     * (bukan ValueAnimator) supaya tetap jalan walau animasi sistem dimatikan / hemat baterai.
     */
    private fun updateCoverFx() {
        val playing = MusicService.instance?.isPlaying == true && !lyricsMode && MusicPrefs.coverAnim(this)
        val circle = MusicPrefs.coverCircle(this)
        if (!circle) binding.npArt.rotation = 0f // waves mode: cover stays upright
        if (!MusicPrefs.coverAnim(this)) binding.npArt.rotation = 0f
        binding.npWaves.glowOn = !circle && MusicPrefs.coverAnim(this) && !lyricsMode // neon: square cover only
        if (circle) binding.npWaves.reset() // paused square cover keeps the light where it stopped
        if (playing) startCoverLoop() else stopCoverLoop()
    }

    /** Bottom sheet with the play queue; the current song is highlighted, tapping a row plays it. */
    private fun showQueue() {
        val svc = MusicService.instance ?: return
        val tracks = svc.queueTracks
        if (tracks.isEmpty()) {
            Toast.makeText(this, R.string.np_queue_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dp = resources.displayMetrics.density
        val accent = themeAccent()
        val primary = color(R.attr.colorTextPrimary)
        val currentIdx = svc.queueIndex
        val dialog = BottomSheetDialog(this)

        val title = TextView(this).apply {
            text = getString(R.string.np_queue_title, tracks.size)
            setTextColor(primary)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (8 * dp).toInt())
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NowPlayingActivity)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = tracks.size
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val row = layoutInflater.inflate(R.layout.item_track, parent, false) as LinearLayout
                    // Per-row "⋮" menu: play this song next.
                    val more = android.widget.ImageButton(this@NowPlayingActivity).apply {
                        id = R.id.queueRowMore
                        setImageResource(R.drawable.ic_more_vert)
                        imageTintList = ColorStateList.valueOf(color(R.attr.colorTextSecondary))
                        background = null
                        contentDescription = getString(R.string.np_queue_row_menu)
                        val s = (40 * dp).toInt()
                        layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = (4 * dp).toInt() }
                    }
                    row.addView(more)
                    return object : RecyclerView.ViewHolder(row) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                    val t = tracks[pos]
                    val v = h.itemView
                    val name = v.findViewById<TextView>(R.id.trackTitle)
                    name.text = t.title
                    name.setTextColor(if (pos == currentIdx) accent else primary)
                    val artistText = t.artist.ifBlank { t.album }
                    v.findViewById<TextView>(R.id.trackArtist).text =
                        if (MusicService.instance?.isSmartPick(t) == true) "\u2726 " + artistText else artistText
                    v.findViewById<TextView>(R.id.trackDuration).text =
                        if (t.durationMs > 0) formatDuration(t.durationMs) else ""
                    v.setOnClickListener {
                        MusicService.instance?.playAt(pos)
                        dialog.dismiss()
                    }
                    val more = v.findViewById<View>(R.id.queueRowMore)
                    more.visibility = if (pos == currentIdx) View.INVISIBLE else View.VISIBLE
                    more.setOnClickListener { anchor ->
                        val pm = PopupMenu(this@NowPlayingActivity, anchor, Gravity.END)
                        pm.menu.add(0, 1, 0, R.string.np_play_next)
                        pm.menu.add(0, 2, 1, R.string.np_queue_remove)
                        pm.setOnMenuItemClickListener { item ->
                            if (item.itemId == 2) {
                                MusicService.instance?.removeFromQueue(pos)
                                Toast.makeText(this@NowPlayingActivity, R.string.np_queue_removed, Toast.LENGTH_SHORT).show()
                            } else {
                                MusicService.instance?.moveToPlayNext(pos)
                                Toast.makeText(this@NowPlayingActivity, R.string.np_play_next_done, Toast.LENGTH_SHORT).show()
                            }
                            dialog.dismiss()
                            showQueue() // reopen with the new order
                            true
                        }
                        pm.show()
                    }
                }
            }
            scrollToPosition((currentIdx - 2).coerceAtLeast(0))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.attr.colorBg))
            addView(title)
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * dp).toInt()))
        }
        dialog.setContentView(root)
        dialog.show()
    }

    /** Top-right three-dot menu: playback speed, sleep timer, share, download, song info. */
    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        val isRadio = MusicService.instance?.current?.source == Track.SOURCE_RADIO
        // Listen together only for songs every guest can stream too (YouTube, Online tab, radio), not for files on this device or Drive.
        // Same order value, so items keep the order they are added in: Listen together comes first, then Audio language.
        val src = MusicService.instance?.current?.source
        if (src == Track.SOURCE_YOUTUBE || src == Track.SOURCE_ONLINE || src == Track.SOURCE_RADIO) {
            popup.menu.add(0, MENU_LISTEN, 0, R.string.np_listen)
        }
        if (MusicService.instance?.current?.source == Track.SOURCE_YOUTUBE) {
            popup.menu.add(0, MENU_AUDIO, 0, R.string.np_audio_track)
        }
        if (!isRadio) { // live radio cannot be sped up: no item at all
            popup.menu.add(0, MENU_SPEED, 0, getString(R.string.np_speed_fmt, speedLabel(MusicService.instance?.playbackSpeed ?: 1f)))
        }
        if (src == Track.SOURCE_YOUTUBE) popup.menu.add(0, MENU_QUALITY, 0, R.string.np_audio_quality)
        popup.menu.add(0, MENU_TIMER, 1, R.string.np_sleep_timer)
        if (MusicService.instance?.current?.source != Track.SOURCE_RADIO) {
            popup.menu.add(0, MENU_DOWNLOAD, 2, R.string.np_download)
        }
        popup.menu.add(0, MENU_SHARE, 3, R.string.np_share)
        popup.menu.add(0, MENU_INFO, 5, R.string.np_info)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_TIMER -> showSleepTimerDialog()
                MENU_SHARE -> shareCurrentSong()
                MENU_LISTEN -> ListenTogetherUi.show(this)
                MENU_DOWNLOAD -> MusicService.instance?.current?.let { t -> SongDownloader.download(this, t) }
                MENU_INFO -> showSongInfo()
                MENU_SPEED -> showSpeedDialog()
                MENU_AUDIO -> showAudioTrackDialog()
                MENU_QUALITY -> StreamQuality.showDialog(this) { MusicService.instance?.reloadCurrent() } // applies to the song playing now
            }
            true
        }
        popup.show()
    }

    private fun speedLabel(s: Float): String =
        (if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0')) + "x"

    /** Lists the audio languages (dubs) of the current YouTube video; the choice is remembered for all videos. */
    private fun showAudioTrackDialog() {
        val svc = MusicService.instance ?: return
        val t = svc.current ?: return
        if (t.source != Track.SOURCE_YOUTUBE) return
        Toast.makeText(this, R.string.np_audio_loading, Toast.LENGTH_SHORT).show()
        lyricsExecutor.execute {
            val choices = try { youtubeAudioChoices(t.remoteId) } catch (e: Exception) { null }
            handler.post {
                if (isDestroyed) return@post
                if (choices == null) { Toast.makeText(this, R.string.np_audio_failed, Toast.LENGTH_SHORT).show(); return@post }
                if (choices.size <= 1) { Toast.makeText(this, R.string.np_audio_none, Toast.LENGTH_LONG).show(); return@post }
                val labels = choices.map { if (it.lang.isEmpty()) getString(R.string.np_audio_original) else it.label }.toTypedArray()
                val cur = MusicPrefs.audioLang(this)
                val checked = choices.indexOfFirst { it.lang == cur }.coerceAtLeast(0)
                AlertDialog.Builder(this)
                    .setTitle(R.string.np_audio_track)
                    .setSingleChoiceItems(labels, checked) { d, which ->
                        d.dismiss()
                        if (choices[which].lang != cur) {
                            MusicPrefs.setAudioLang(this, choices[which].lang)
                            MusicService.instance?.reloadCurrent()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showSpeedDialog() {
        val svc = MusicService.instance ?: return
        if (svc.current?.source == Track.SOURCE_RADIO) return
        val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val checked = speeds.indexOfFirst { kotlin.math.abs(it - svc.playbackSpeed) < 0.01f }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.np_speed)
            .setSingleChoiceItems(speeds.map { speedLabel(it) }.toTypedArray(), checked) { d, which ->
                svc.setPlaybackSpeed(speeds[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSleepTimerDialog() {
        val track = MusicService.instance?.current ?: return
        val options = intArrayOf(0, 5, 15, 30, 45, 60, 90, 120)
        val labels = options.map {
            if (it == 0) getString(R.string.np_timer_off) else getString(R.string.bedtime_minutes, it)
        }.toTypedArray()
        val title = if (SleepTimerService.isRunning())
            getString(R.string.np_timer_running, formatDuration(SleepTimerService.remainingMs()))
        else getString(R.string.np_sleep_timer)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val m = options[which]
                if (m == 0) {
                    SleepTimerService.cancel(this)
                    Toast.makeText(this, R.string.np_timer_cancelled, Toast.LENGTH_SHORT).show()
                } else {
                    SleepTimerService.start(this, m, track.source)
                    Toast.makeText(this, getString(R.string.np_timer_set, m), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun trackLink(t: Track): String =
        when {
            t.source == Track.SOURCE_YOUTUBE && t.remoteId.isNotBlank() -> "https://youtu.be/${t.remoteId}"
            t.source == Track.SOURCE_RADIO -> t.remoteId
            else -> ""
        }

    private fun shareCurrentSong() {
        val svc = MusicService.instance ?: return
        val t = svc.current ?: return
        // Spotify-style image card (cover, title, artist) + the song link as text.
        SongShareCard.share(this, t, svc.artwork, trackLink(t), getString(R.string.np_share_via))
    }

    private fun showSongInfo() {
        val t = MusicService.instance?.current ?: return
        val rows = mutableListOf(getString(R.string.np_info_title) to t.title)
        if (t.artist.isNotBlank()) rows += getString(R.string.np_info_artist) to t.artist
        if (t.album.isNotBlank()) rows += getString(R.string.np_info_album) to t.album
        val dur = MusicService.instance?.duration?.toLong()?.takeIf { it > 0 } ?: t.durationMs
        if (dur > 0) rows += getString(R.string.np_info_duration) to formatDuration(dur)
        AudioQuality.cached(t)?.let { rows += getString(R.string.np_info_quality) to it.details() }
        rows += getString(R.string.np_info_source) to (getString(
            when (t.source) {
                Track.SOURCE_YOUTUBE -> R.string.source_youtube
                Track.SOURCE_RADIO -> R.string.source_radio
                Track.SOURCE_DRIVE -> R.string.source_drive
                else -> R.string.source_local
            }
        ) + (RadioOrigin.label(this, t)?.let { " \u00B7 $it" } ?: ""))
        trackLink(t).takeIf { it.isNotBlank() }?.let { rows += getString(R.string.np_info_link) to it }
        AlertDialog.Builder(this)
            .setTitle(R.string.np_info)
            .setMessage(rows.joinToString("\n\n") { (k, v) -> "$k\n$v" })
            .setPositiveButton(R.string.np_info_close, null)
            .show()
    }


    // ---- accent from the cover (Settings > Player > "Colours from the cover") ----
    private var coverAccent: Int? = null
    private var shownAccent = 0
    private var accentAnim: android.animation.ValueAnimator? = null

    /** The cover's accent while the feature is on and the cover has a colour, else the app's accent. */
    /** The app's own accent (Settings > Appearance): used where the cover colour is deliberately NOT applied. */
    private fun themeAccent(): Int = color(androidx.appcompat.R.attr.colorAccent)

    private fun accentColor(): Int = coverAccent ?: color(androidx.appcompat.R.attr.colorAccent)

    private fun applyCoverTheme(art: android.graphics.Bitmap?) {
        val target = CoverColors.accentFor(this, art)
        coverAccent = target
        val to = target ?: color(androidx.appcompat.R.attr.colorAccent)
        if (shownAccent == 0 || shownAccent == to) { paintAccent(to, target != null); shownAccent = to; return }
        accentAnim?.cancel()
        val from = shownAccent
        accentAnim = android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, to).apply {
            duration = 450
            addUpdateListener { paintAccent(it.animatedValue as Int, target != null) }
            start()
        }
        shownAccent = to
    }

    private fun paintAccent(c: Int, themed: Boolean) {
        val b = binding
        b.npWaves.waveColor = c
        // Soft wash of the cover colour over the plain background -- but not when a Screensaver background
        // (animated style / photo) covers it.
        val base = color(R.attr.colorBg)
        val bgShown = screensaverBgShown
        if (themed && !bgShown) {
            // Cover colour fading toward the plain background at the bottom.
            val top = CoverColors.pageBg(this, c)
            val bottom = CoverColors.wash(base, c, if (CoverColors.isNight(this)) 0.12f else 0.08f)
            b.root.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
            )
        } else {
            b.root.setBackgroundColor(base)
        }
    }

    /** Re-applies the accent after the background mode changed (toggle button, Settings, photo failed to load). */
    private fun syncWash() {
        paintAccent(if (shownAccent != 0) shownAccent else accentColor(), coverAccent != null)
    }

    private fun color(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {
        /** Set by the charging screensaver so the player shows above the lock screen. */
        const val EXTRA_OVER_LOCK = "over_lock"
        private const val MENU_TIMER = 1
        private const val MENU_SHARE = 2
        private const val MENU_LISTEN = 9
        private const val MENU_INFO = 3
        private const val MENU_DOWNLOAD = 4
        private const val MENU_SPEED = 5
        private const val MENU_AUDIO = 6
        private const val MENU_QUALITY = 7
    }

    // ------------------------------------------------------------------ audio -> video

    private var resumeAudioAfterVideo = false

    /** Back from the video: the audio continues from the second the user reached in the video. */
    private val videoLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
            // The video's close arrow leaves the player altogether: this music player closes as well.
            if (res.data?.getBooleanExtra(VideoPlayerActivity.EXTRA_CLOSE_ALL, false) == true) finish()
            val svc = MusicService.instance ?: return@registerForActivityResult
            val pos = res.data?.getLongExtra(VideoPlayerActivity.EXTRA_POSITION_MS, -1L) ?: -1L
            // The "video" button of the video player switches back to music mode: the audio resumes even if it was paused.
            val toMusic = res.data?.getBooleanExtra(VideoPlayerActivity.EXTRA_TO_MUSIC, false) == true
            if (pos >= 0) {
                svc.seekTo(pos.toInt())
                if (resumeAudioAfterVideo || toMusic) {
                    svc.play()
                    // The first play() can be refused while the video still holds the audio focus: retry.
                    val key = svc.current?.key
                    val h = android.os.Handler(android.os.Looper.getMainLooper())
                    var tries = 0
                    val r = object : Runnable {
                        override fun run() {
                            tries++
                            if (svc.current?.key != key || svc.isPlaying) return
                            svc.play()
                            if (tries < 6) h.postDelayed(this, 400L)
                        }
                    }
                    h.postDelayed(r, 400L)
                }
            }
            resumeAudioAfterVideo = false
        }

    /** Switches the current song from audio to its video (YouTube songs), starting at the current position. */
    private fun openVideo() {
        val svc = MusicService.instance ?: return
        val t = svc.current ?: return
        PlayerMode.init(this)
        val tvAudio = PlayerMode.isTvAudio(t) // a live TV channel playing as audio: back to its video
        if (t.source != Track.SOURCE_YOUTUBE && !tvAudio) {
            android.widget.Toast.makeText(this, R.string.video_not_available, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        resumeAudioAfterVideo = svc.isPlaying
        val pos = if (tvAudio) 0L else svc.position.toLong() // live: no position
        if (svc.isPlaying) svc.pause()
        videoLauncher.launch(PlayerMode.videoIntent(this, svc, t, pos, true))
    }
}
