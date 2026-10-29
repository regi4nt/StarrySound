package com.fxsoundlite.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fxsoundlite.app.databinding.ActivityVideoPlayerBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Video player built like the music player (same theme, background, paddings, buttons and menus): the video sits where
 * the cover is, with the same title / artist, seek bar and round play button below it (own controls; the YouTube
 * player's controls are switched off). Sources: a YouTube video (official IFrame player) or a video file of the
 * device / Drive (VideoView).
 *
 * The video queue (shuffle, repeat, queue list) is its own: it never touches [MusicService] while the video plays.
 * Callers pause their audio first and get the position the user reached in the result ([EXTRA_POSITION_MS]), so the
 * audio can continue from there. The "video" button switches the current YouTube song back to music mode.
 */
class VideoPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_REMOTE_ID = "remote_id"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_POSITION_MS = "position_ms"
        /** Result: the user switched to music mode and the music player already has this song, so the caller resumes it. */
        const val EXTRA_TO_MUSIC = "to_music"
        /** Result: the close arrow was used on a video opened from the music player, so the caller closes too (back to the page before both players). */
        const val EXTRA_CLOSE_ALL = "close_all"
        private const val EXTRA_FROM_PLAYER = "from_player"
        private const val EXTRA_Q_SOURCES = "q_sources"
        private const val EXTRA_Q_IDS = "q_ids"
        private const val EXTRA_Q_TITLES = "q_titles"
        private const val EXTRA_Q_ARTISTS = "q_artists"
        private const val EXTRA_Q_DURS = "q_durs"
        private const val EXTRA_Q_ARTS = "q_arts"
        private const val EXTRA_INDEX = "q_index"

        private const val REPEAT_OFF = 0
        private const val REPEAT_ALL = 1
        private const val REPEAT_ONE = 2

        private const val M_SPEED = 1
        private const val M_TIMER = 2
        private const val M_DOWNLOAD = 3
        private const val M_SHARE = 4
        private const val M_INFO = 5

        /** [fromPlayer] = opened from the music player (NowPlayingActivity), which takes the result and resumes its audio. */
        fun intent(c: Context, t: Track, startMs: Long = 0L, fromPlayer: Boolean = false): Intent =
            queueIntent(c, listOf(t), 0, startMs, fromPlayer)

        /** A playlist as a video queue: plays [index] first and goes on with the next ones. Only [isVideoPlayable] tracks should be passed. */
        fun queueIntent(c: Context, tracks: List<Track>, index: Int, startMs: Long = 0L, fromPlayer: Boolean = false): Intent =
            Intent(c, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_Q_SOURCES, IntArray(tracks.size) { tracks[it].source })
                .putExtra(EXTRA_Q_IDS, Array(tracks.size) { tracks[it].remoteId })
                .putExtra(EXTRA_Q_TITLES, Array(tracks.size) { tracks[it].title })
                .putExtra(EXTRA_Q_ARTISTS, Array(tracks.size) { tracks[it].artist })
                .putExtra(EXTRA_Q_DURS, LongArray(tracks.size) { tracks[it].durationMs })
                .putExtra(EXTRA_Q_ARTS, Array(tracks.size) { tracks[it].artUrl })
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_START_MS, startMs)
                .putExtra(EXTRA_FROM_PLAYER, fromPlayer)
    }

    /** Video queue in play order (shuffled when [shuffle] is on); [original] keeps the order it came in. */
    private var queue = mutableListOf<Track>()
    private var original = listOf<Track>()
    private var index = 0
    private var loadToken = 0
    private var shuffle = false
    private var repeat = REPEAT_OFF
    private var fromPlayer = false

    // Full screen (long press on the video): only the video, turned to landscape.
    private var full = false
    private var orientationBeforeFull = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // Landscape controls: portrait look to restore, and the cut-out / bar insets.
    private var panelLp: ViewGroup.LayoutParams? = null
    private var seekDefaultTrack: android.graphics.drawable.Drawable? = null
    private var landInsets = androidx.core.graphics.Insets.NONE

    private lateinit var binding: ActivityVideoPlayerBinding
    private val handler = Handler(Looper.getMainLooper())

    private var ytView: YouTubePlayerView? = null
    private var yt: YouTubePlayer? = null
    private var fileView: VideoView? = null
    private var filePlayer: MediaPlayer? = null

    private var posMs = 0L
    private var durMs = 0L
    private var playing = false
    private var dragging = false
    private var speed = 1f

    // Result handed back to the caller.
    private var resultPos: Long? = null
    private var toMusic = false
    private var closeAll = false

    // Sleep timer (own: the music service's timer would not stop a video).
    private var sleepAt = 0L
    private val sleepRun = Runnable { sleepAt = 0L; pausePlayback() }

    private val poll = object : Runnable {
        override fun run() {
            fileView?.let {
                playing = it.isPlaying
                if (playing) posMs = it.currentPosition.toLong()
                if (durMs <= 0) durMs = it.duration.toLong().coerceAtLeast(0L)
            }
            updateUi()
            handler.postDelayed(this, 500)
        }
    }

    private val current: Track get() = queue[index]

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this) // same light / dark mode and accent colour as the music player
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        overridePendingTransition(R.anim.slide_up, R.anim.stay) // opens with the same slide as the music player
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC // the hardware volume keys control the media volume
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        panelLp = binding.vpPanel.layoutParams
        seekDefaultTrack = binding.vpSeek.progressDrawable

        fromPlayer = intent.getBooleanExtra(EXTRA_FROM_PLAYER, false)
        val sources = intent.getIntArrayExtra(EXTRA_Q_SOURCES) ?: IntArray(0)
        val ids = intent.getStringArrayExtra(EXTRA_Q_IDS) ?: emptyArray()
        val titles = intent.getStringArrayExtra(EXTRA_Q_TITLES) ?: emptyArray()
        val artists = intent.getStringArrayExtra(EXTRA_Q_ARTISTS) ?: emptyArray()
        val durs = intent.getLongArrayExtra(EXTRA_Q_DURS) ?: LongArray(0)
        val arts = intent.getStringArrayExtra(EXTRA_Q_ARTS) ?: emptyArray()
        val n = minOf(sources.size, ids.size, titles.size, artists.size)
        if (n == 0) { Toast.makeText(this, R.string.video_not_available, Toast.LENGTH_LONG).show(); finish(); return }
        original = List(n) {
            Track(0L, titles[it], artists[it], "", durs.getOrElse(it) { 0L }, sources[it], ids[it], arts.getOrElse(it) { "" })
        }
        queue = original.toMutableList()
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(0, n - 1)
        val startMs = savedInstanceState?.getLong(EXTRA_POSITION_MS) ?: intent.getLongExtra(EXTRA_START_MS, 0L)

        binding.vpClose.setOnClickListener { closeAll = fromPlayer; finish() }
        binding.vpVolume.setOnClickListener { showSystemVolume() }
        binding.vpMore.setOnClickListener { showMoreMenu(it) }
        binding.vpPlay.setOnClickListener { togglePlay() }
        // The time labels skip: elapsed time = back 10 s, total time = forward 10 s.
        binding.vpElapsed.setOnClickListener { seekTo(posMs - 10_000L) }
        binding.vpTotal.setOnClickListener { seekTo(posMs + 10_000L) }
        binding.vpPrev.setOnClickListener { step(-1) }
        binding.vpNext.setOnClickListener { step(1) }
        binding.vpShuffle.setOnClickListener { toggleShuffle() }
        binding.vpRepeat.setOnClickListener { repeat = (repeat + 1) % 3; updateUi() }
        binding.vpRotate.setOnClickListener {
            val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        binding.vpLandBack.setOnClickListener { exitToPortrait() }
        binding.vpLandMore.setOnClickListener { showMoreMenu(it) }
        // Keep the landscape bars clear of a camera cut-out / rounded corners.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.vpRoot) { _, insets ->
            landInsets = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            applyLandInsets()
            insets
        }
        binding.vpTouch.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            setFullscreen(!full)
            true
        }
        // In landscape / full screen the controls lie over the video: a tap shows or hides them.
        binding.vpTouch.setOnClickListener {
            if (isOverlay()) showControls(binding.vpControls.visibility != View.VISIBLE)
        }
        // Back: leaves full screen first, then landscape, then the player.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    full -> exitToPortrait()
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ->
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> finish()
                }
            }
        })
        binding.vpMusic.setOnClickListener { switchToMusic() }
        binding.vpAddPlaylist.setOnClickListener { PlaylistUi.addToPlaylist(this, current) { } }
        binding.vpBg.setOnClickListener {
            MusicPrefs.setPlayerBgScreensaver(this, !MusicPrefs.playerBgScreensaver(this))
            applyPlayerBackground()
        }
        binding.vpQueue.setOnClickListener { showQueue() }
        binding.vpSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.vpElapsed.text = formatDuration(progress * 1000L)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                dragging = false
                seekTo((seekBar?.progress ?: 0) * 1000L)
            }
        })
        // The video takes the free space of the cover area (full width, 16:9; smaller only when the space is short).
        binding.vpStage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoSize() }

        applyOrientation()
        playIndex(index, startMs)
    }

    override fun onStart() {
        super.onStart()
        applyPlayerBackground()
    }

    override fun onStop() {
        binding.vpBgAnimated.stop() // no animation loop while off screen
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
        outState.putLong(EXTRA_POSITION_MS, posMs)
    }

    // ------------------------------------------------------------------ queue

    /** Next (+1) / previous (-1) video; wraps around. */
    private fun step(d: Int) {
        val n = queue.size
        if (n < 2) return
        playIndex(((index + d) % n + n) % n, 0L)
    }

    /** The video ended: repeat it, go on with the next one, or stop at the end of the queue. */
    private fun onEnded() {
        playing = false
        when {
            repeat == REPEAT_ONE || (repeat == REPEAT_ALL && queue.size == 1) -> playIndex(index, 0L)
            queue.size > 1 && (repeat == REPEAT_ALL || index < queue.size - 1) -> step(1)
            else -> updateUi()
        }
    }

    private fun toggleShuffle() {
        val cur = current
        if (!shuffle) {
            queue = (listOf(cur) + original.filter { it !== cur }.shuffled()).toMutableList()
            index = 0
        } else {
            queue = original.toMutableList()
            index = original.indexOfFirst { it === cur }.coerceAtLeast(0)
        }
        shuffle = !shuffle
        updateUi()
    }

    /** Plays the video at [i]: swaps the video view when the kind of source changes. */
    private fun playIndex(i: Int, startMs: Long) {
        index = i
        val token = ++loadToken
        posMs = startMs
        durMs = 0L
        playing = false
        val t = queue[i]
        binding.vpTitle.text = t.title
        binding.vpLandTitle.text = t.title
        binding.vpArtist.text = t.artist
        binding.vpArtist.visibility = if (t.artist.isBlank()) View.GONE else View.VISIBLE
        val id = t.remoteId
        when (t.source) {
            Track.SOURCE_YOUTUBE -> {
                dropFile()
                val p = yt
                if (p != null) p.loadVideo(id, startMs / 1000f) else setupYoutube(id, startMs)
            }
            Track.SOURCE_VIDEO -> { dropYoutube(); setupFile(Uri.parse(id), null, startMs) }
            Track.SOURCE_DRIVE_VIDEO -> {
                dropYoutube()
                val app = applicationContext
                Thread {
                    val req = try { DriveMusic.streamRequest(app, id) } catch (e: Exception) { null }
                    runOnUiThread {
                        if (isDestroyed || token != loadToken) return@runOnUiThread
                        if (req == null) {
                            Toast.makeText(this, R.string.video_error_drive, Toast.LENGTH_LONG).show()
                            if (queue.size > 1) step(1) else finish()
                        } else setupFile(Uri.parse(req.first), req.second, startMs)
                    }
                }.start()
            }
            else -> {
                Toast.makeText(this, R.string.video_not_available, Toast.LENGTH_SHORT).show()
                if (queue.size > 1) step(1) else finish()
            }
        }
        updateUi()
    }

    private fun dropYoutube() {
        ytView?.let {
            lifecycle.removeObserver(it)
            try { it.release() } catch (_: Exception) { }
            binding.vpVideoFrame.removeView(it)
        }
        ytView = null
        yt = null
    }

    private fun dropFile() {
        fileView?.let {
            handler.removeCallbacks(poll)
            it.stopPlayback()
            binding.vpVideoFrame.removeView(it)
        }
        fileView = null
        filePlayer = null
    }

    // ------------------------------------------------------------------ layout

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun color(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun themeAccent(): Int = color(androidx.appcompat.R.attr.colorAccent)

    private fun setFullscreen(on: Boolean) {
        if (full == on) return
        full = on
        if (on) {
            orientationBeforeFull = requestedOrientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = orientationBeforeFull
        }
        applyOrientation()
    }

    /**
     * Portrait: header, video in the cover area, title, controls, second row.
     * Landscape / full screen: system bars, header, title and second row hidden; the video fills the screen and
     * the controls lie over it (tap to show / hide).
     */
    private fun applyOrientation() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val immersive = land || full
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !immersive)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        binding.vpHeader.visibility = if (immersive) View.GONE else View.VISIBLE
        binding.vpInfo.visibility = if (immersive) View.GONE else View.VISIBLE
        binding.vpRow2.visibility = if (immersive) View.GONE else View.VISIBLE
        if (immersive) binding.vpContent.setPadding(0, 0, 0, 0)
        else binding.vpContent.setPadding(0, dp(12), 0, dp(24))
        placeControls(immersive)
        applyVideoSize()
    }

    /** Top-left button in landscape / full screen: always returns to portrait. */
    private fun exitToPortrait() {
        full = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyOrientation()
    }

    private fun isOverlay(): Boolean =
        full || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val hideControls = Runnable {
        if (isOverlay() && playing) setOverlayShown(false)
    }

    /**
     * Portrait: the controls stay in the column under the title.
     * Landscape / full screen (YouTube-like): top bar (back, title, shuffle / repeat, menu), previous / play / next in
     * the middle of the video, and a slim bottom bar with the seek bar and the times.
     */
    private fun placeControls(overlay: Boolean) {
        val c = binding.vpControls
        val parent = c.parent as ViewGroup
        moveTransport(overlay)
        if (overlay) {
            if (parent !== binding.vpRoot) {
                parent.removeView(c)
                binding.vpRoot.addView(c, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            }
            c.setBackgroundResource(R.drawable.bg_overlay_bottom)
            compactControls(true)
            applyLandInsets()
            showControls(true)
        } else {
            handler.removeCallbacks(hideControls)
            if (parent !== binding.vpContent) {
                parent.removeView(c)
                binding.vpContent.addView(c, binding.vpContent.indexOfChild(binding.vpRow2),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            c.background = null
            c.setPadding(0, 0, 0, 0)
            compactControls(false)
            c.visibility = View.VISIBLE
            binding.vpLandTop.visibility = View.GONE
            binding.vpCenter.visibility = View.GONE
        }
    }

    /** Landscape: prev / play / next go to the middle of the video, shuffle / repeat to the top bar. Portrait: back into the row. */
    private fun moveTransport(overlay: Boolean) {
        val panel = binding.vpPanel
        val center = binding.vpCenter
        val top = binding.vpLandTop
        val trio = listOf(binding.vpPrev, binding.vpPlay, binding.vpNext)
        if (overlay) {
            if (binding.vpPlay.parent !== center) {
                for (v in trio) { (v.parent as ViewGroup).removeView(v); center.addView(v) }
            }
            if (panel.parent !== top) {
                (panel.parent as ViewGroup).removeView(panel)
                top.addView(panel, top.indexOfChild(binding.vpLandMore),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        } else {
            if (binding.vpPlay.parent !== panel) {
                for (v in trio) (v.parent as ViewGroup).removeView(v)
                panel.addView(binding.vpPrev, 1)
                panel.addView(binding.vpPlay, 2)
                panel.addView(binding.vpNext, 3)
            }
            if (panel.parent !== binding.vpControls) {
                (panel.parent as ViewGroup).removeView(panel)
                binding.vpControls.addView(panel, panelLp)
            }
        }
    }

    private fun applyLandInsets() {
        val i = landInsets
        binding.vpLandTop.setPadding(dp(8) + i.left, dp(4) + i.top, dp(8) + i.right, dp(20))
        val c = binding.vpControls
        if (c.parent === binding.vpRoot) c.setPadding(i.left, dp(24), i.right, dp(4) + i.bottom)
    }

    private fun setOverlayShown(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        binding.vpControls.visibility = v
        val over = isOverlay()
        binding.vpLandTop.visibility = if (over) v else View.GONE
        binding.vpCenter.visibility = if (over) v else View.GONE
    }

    private fun sizeOf(v: View, size: Int, pad: Int) {
        v.layoutParams = v.layoutParams.apply { width = dp(size); height = dp(size) }
        v.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    /** Landscape / full screen: slim bottom bar, thin seek track, white times; portrait keeps its normal look. */
    private fun compactControls(on: Boolean) {
        sizeOf(binding.vpPlay, if (on) 60 else 68, if (on) 14 else 18)
        sizeOf(binding.vpPrev, if (on) 48 else 56, 12)
        sizeOf(binding.vpNext, if (on) 48 else 56, 12)
        sizeOf(binding.vpShuffle, if (on) 36 else 48, if (on) 8 else 12)
        sizeOf(binding.vpRepeat, if (on) 36 else 48, if (on) 8 else 12)
        (binding.vpPlay.layoutParams as ViewGroup.MarginLayoutParams).apply {
            leftMargin = dp(if (on) 20 else 8)
            rightMargin = dp(if (on) 20 else 8)
        }
        (binding.vpSeek.layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(if (on) 0 else 16)
        binding.vpSeek.progressDrawable =
            if (on) androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.seek_thin_overlay)
            else seekDefaultTrack
        for (t in listOf(binding.vpElapsed, binding.vpTotal)) {
            t.layoutParams = t.layoutParams.apply { height = dp(if (on) 24 else 40) }
            t.setTextColor(if (on) android.graphics.Color.WHITE else color(R.attr.colorTextSecondary))
        }
        binding.vpControls.requestLayout()
    }

    private fun showControls(show: Boolean) {
        handler.removeCallbacks(hideControls)
        setOverlayShown(show)
        if (show) handler.postDelayed(hideControls, 3500L)
    }

    /** Any touch on the controls keeps them shown a few seconds longer. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP && isOverlay() && binding.vpControls.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideControls)
            handler.postDelayed(hideControls, 3500L)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Full screen width at 16:9, like before; if the free space is shorter than that, the video shrinks to fit it. */
    private fun applyVideoSize() {
        val sh = binding.vpStage.height
        if (sh <= 0) return
        var w = resources.displayMetrics.widthPixels
        var h = w * 9 / 16
        if (h > sh) { h = sh; w = h * 16 / 9 }
        val lp = binding.vpVideoFrame.layoutParams as FrameLayout.LayoutParams
        if (lp.width == w && lp.height == h && lp.gravity == Gravity.CENTER) return
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.CENTER
        binding.vpVideoFrame.layoutParams = lp
        // The layer that catches the long press has the video's size.
        val tl = binding.vpTouch.layoutParams as FrameLayout.LayoutParams
        tl.width = w
        tl.height = h
        tl.gravity = Gravity.CENTER
        binding.vpTouch.layoutParams = tl
    }

    /** Same player background as the music player: the Screensaver's animated style, or the plain theme colour. */
    private fun applyPlayerBackground() {
        val bgOn = MusicPrefs.playerBgScreensaver(this)
        binding.vpBg.imageTintList = ColorStateList.valueOf(if (bgOn) themeAccent() else color(R.attr.colorTextSecondary))
        val animated = bgOn && ScreensaverPrefs.mediaType(this) == ScreensaverPrefs.TYPE_ANIMATED
        if (!animated) {
            binding.vpBgAnimated.stop()
            binding.vpBgAnimated.visibility = View.GONE
            return
        }
        binding.vpBgAnimated.style = when (ScreensaverPrefs.animatedStyle(this)) {
            ScreensaverPrefs.STYLE_RAIN -> SleepBackgroundView.Style.RAIN
            ScreensaverPrefs.STYLE_STARFIELD -> SleepBackgroundView.Style.STARFIELD
            else -> SleepBackgroundView.Style.NIGHT_SKY
        }
        binding.vpBgAnimated.visibility = View.VISIBLE
        binding.vpBgAnimated.start()
    }

    // ------------------------------------------------------------------ sources

    private fun setupYoutube(id: String, startMs: Long) {
        val v = LayoutInflater.from(this).inflate(R.layout.view_youtube_player, binding.vpVideoFrame, false) as YouTubePlayerView
        v.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        binding.vpVideoFrame.addView(v)
        lifecycle.addObserver(v)
        val options = IFramePlayerOptions.Builder(this)
            .controls(0) // own controls below the video
            .rel(0)
            .fullscreen(0)
            .origin("https://" + packageName.lowercase())
            .build()
        v.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                yt = youTubePlayer
                youTubePlayer.loadVideo(id, startMs / 1000f)
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                playing = state == PlayerConstants.PlayerState.PLAYING || state == PlayerConstants.PlayerState.BUFFERING
                if (state == PlayerConstants.PlayerState.PLAYING) applySpeed()
                updateUi()
                if (state == PlayerConstants.PlayerState.ENDED) onEnded()
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                posMs = (second * 1000).toLong()
                updateUi()
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                durMs = (duration * 1000).toLong()
                updateUi()
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                // The owner may forbid embedding: hand over to the YouTube app / browser at the same second.
                Toast.makeText(this@VideoPlayerActivity, R.string.video_error, Toast.LENGTH_LONG).show()
                if (queue.size > 1) { step(1); return }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id&t=${posMs / 1000}s")))
                } catch (_: Exception) { }
                finish()
            }
        }, false, options)
        ytView = v
    }

    private fun setupFile(uri: Uri, headers: Map<String, String>?, startMs: Long) {
        dropFile()
        val vv = VideoView(this)
        binding.vpVideoFrame.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        vv.setOnPreparedListener { mp ->
            filePlayer = mp
            durMs = mp.duration.toLong().coerceAtLeast(0L)
            if (startMs > 0) mp.seekTo(startMs.toInt())
            vv.start()
            applySpeed()
        }
        vv.setOnCompletionListener { onEnded() }
        vv.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.video_error_file, Toast.LENGTH_LONG).show()
            if (queue.size > 1) step(1) else finish()
            true
        }
        if (headers != null) vv.setVideoURI(uri, headers) else vv.setVideoURI(uri)
        fileView = vv
        handler.post(poll)
    }

    // ------------------------------------------------------------------ controls

    private fun togglePlay() {
        yt?.let { if (playing) it.pause() else it.play(); return }
        fileView?.let {
            if (it.isPlaying) it.pause() else { it.start(); applySpeed() }
            playing = it.isPlaying
            updateUi()
        }
    }

    private fun pausePlayback() {
        yt?.pause()
        fileView?.pause()
    }

    private fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE)
        posMs = target
        yt?.seekTo(target / 1000f)
        fileView?.seekTo(target.toInt())
        updateUi()
    }

    private fun applySpeed() {
        yt?.setPlaybackRate(
            when (speed) {
                0.5f -> PlayerConstants.PlaybackRate.RATE_0_5
                1.5f -> PlayerConstants.PlaybackRate.RATE_1_5
                2f -> PlayerConstants.PlaybackRate.RATE_2
                else -> PlayerConstants.PlaybackRate.RATE_1
            }
        )
        val mp = filePlayer ?: return
        if (Build.VERSION.SDK_INT >= 23 && fileView?.isPlaying == true) { // a paused player would start playing when the speed is set
            try { mp.playbackParams = mp.playbackParams.setSpeed(speed) } catch (_: Exception) { }
        }
    }

    private fun updateUi() {
        if (!::binding.isInitialized || queue.isEmpty()) return
        binding.vpPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.vpTotal.text = formatDuration(durMs)
        binding.vpSeek.max = (durMs / 1000L).toInt().coerceAtLeast(1)
        if (!dragging) {
            binding.vpSeek.progress = (posMs / 1000L).toInt()
            binding.vpElapsed.text = formatDuration(posMs)
        }
        // A single video has nothing to shuffle / skip to: hidden, but keeping their room so play stays centred.
        val multi = queue.size > 1
        val vis = if (multi) View.VISIBLE else View.INVISIBLE
        binding.vpShuffle.visibility = vis
        binding.vpPrev.visibility = vis
        binding.vpNext.visibility = vis
        val accent = themeAccent()
        val inactive = color(R.attr.colorTextSecondary)
        binding.vpShuffle.imageTintList = ColorStateList.valueOf(if (shuffle) accent else inactive)
        binding.vpRepeat.setImageResource(if (repeat == REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        binding.vpRepeat.imageTintList = ColorStateList.valueOf(if (repeat == REPEAT_OFF) inactive else accent)
    }

    // ------------------------------------------------------------------ music mode

    /** Switches the current YouTube song from video to music, continuing at the second the video reached. */
    private fun switchToMusic() {
        val t = current
        if (t.source != Track.SOURCE_YOUTUBE) {
            Toast.makeText(this, R.string.video_music_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val svc = MusicService.instance
        if (svc == null) {
            Toast.makeText(this, R.string.video_music_not_ready, Toast.LENGTH_LONG).show()
            return
        }
        val pos = fileView?.currentPosition?.toLong() ?: posMs
        if (svc.current?.key == t.key) {
            // The music player already has this song (the video was opened from it): continue it from here.
            if (fromPlayer) {
                toMusic = true // its caller seeks and resumes
            } else {
                svc.seekTo(pos.toInt())
                svc.play()
            }
            resultPos = pos
        } else {
            val songs = queue.filter { it.source == Track.SOURCE_YOUTUBE }
            svc.setQueueAndPlay(songs, songs.indexOfFirst { it.key == t.key }.coerceAtLeast(0))
            seekWhenReady(svc, t.key, pos)
            resultPos = -1L // nothing for the caller to seek
        }
        // Opened from a list / search page: the music player opens (closing it returns to that page).
        if (!fromPlayer) startActivity(Intent(this, NowPlayingActivity::class.java))
        finish()
    }

    /** The new song loads in the service; once it is ready, jump to the video's position. */
    private fun seekWhenReady(svc: MusicService, key: String, pos: Long) {
        if (pos < 3000L) return
        val h = Handler(Looper.getMainLooper())
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                tries++
                if (svc.current?.key == key && svc.duration > 0) { svc.seekTo(pos.toInt()); return }
                if (tries < 40) h.postDelayed(this, 250L)
            }
        }
        h.postDelayed(r, 250L)
    }

    // ------------------------------------------------------------------ volume / menu / queue

    /** Shows the device's own volume panel (media stream) without changing the level. */
    private fun showSystemVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    private fun showMoreMenu(anchor: View) {
        val t = current
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(0, M_SPEED, 0, getString(R.string.np_speed_fmt, speedLabel(speed)))
        popup.menu.add(0, M_TIMER, 1, R.string.np_sleep_timer)
        if (t.source == Track.SOURCE_YOUTUBE) popup.menu.add(0, M_DOWNLOAD, 2, R.string.np_download)
        popup.menu.add(0, M_SHARE, 3, R.string.np_share)
        popup.menu.add(0, M_INFO, 5, R.string.np_info)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                M_SPEED -> showSpeedDialog()
                M_TIMER -> showSleepTimerDialog()
                M_DOWNLOAD -> SongDownloader.download(this, t)
                M_SHARE -> SongShareCard.share(this, t, null, trackLink(t), getString(R.string.np_share_via))
                M_INFO -> showInfo(t)
            }
            true
        }
        popup.show()
    }

    private fun speedLabel(s: Float): String =
        (if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0')) + "x"

    private fun showSpeedDialog() {
        val speeds = floatArrayOf(0.5f, 1f, 1.5f, 2f)
        AlertDialog.Builder(this)
            .setTitle(R.string.np_speed)
            .setSingleChoiceItems(speeds.map { speedLabel(it) }.toTypedArray(), speeds.indexOfFirst { it == speed }) { d, which ->
                speed = speeds[which]
                applySpeed()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSleepTimerDialog() {
        val options = intArrayOf(0, 5, 15, 30, 45, 60, 90, 120)
        val labels = options.map {
            if (it == 0) getString(R.string.np_timer_off) else getString(R.string.bedtime_minutes, it)
        }.toTypedArray()
        val remaining = sleepAt - System.currentTimeMillis()
        val title = if (sleepAt > 0 && remaining > 0) getString(R.string.np_timer_running, formatDuration(remaining))
        else getString(R.string.np_sleep_timer)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val m = options[which]
                handler.removeCallbacks(sleepRun)
        handler.removeCallbacks(hideControls)
                if (m == 0) {
                    sleepAt = 0L
                    Toast.makeText(this, R.string.np_timer_cancelled, Toast.LENGTH_SHORT).show()
                } else {
                    sleepAt = System.currentTimeMillis() + m * 60_000L
                    handler.postDelayed(sleepRun, m * 60_000L)
                    Toast.makeText(this, getString(R.string.video_timer_set, m), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun trackLink(t: Track): String =
        if (t.source == Track.SOURCE_YOUTUBE && t.remoteId.isNotBlank()) "https://youtu.be/${t.remoteId}" else ""

    private fun showInfo(t: Track) {
        val rows = mutableListOf(getString(R.string.np_info_title) to t.title)
        if (t.artist.isNotBlank()) rows += getString(R.string.np_info_artist) to t.artist
        val dur = durMs.takeIf { it > 0 } ?: t.durationMs
        if (dur > 0) rows += getString(R.string.np_info_duration) to formatDuration(dur)
        rows += getString(R.string.np_info_source) to getString(
            when (t.source) {
                Track.SOURCE_YOUTUBE -> R.string.source_youtube
                Track.SOURCE_DRIVE_VIDEO -> R.string.source_drive
                else -> R.string.source_local
            }
        )
        trackLink(t).takeIf { it.isNotBlank() }?.let { rows += getString(R.string.np_info_link) to it }
        AlertDialog.Builder(this)
            .setTitle(R.string.np_info)
            .setMessage(rows.joinToString("\n\n") { (k, v) -> "$k\n$v" })
            .setPositiveButton(R.string.np_info_close, null)
            .show()
    }

    /** Bottom sheet with the video queue (in play order); the current video is highlighted, tapping a row plays it. */
    private fun showQueue() {
        val tracks = queue.toList()
        val d = resources.displayMetrics.density
        val accent = themeAccent()
        val primary = color(R.attr.colorTextPrimary)
        val currentIdx = index
        val dialog = BottomSheetDialog(this)
        val title = TextView(this).apply {
            text = getString(R.string.np_queue_title, tracks.size)
            setTextColor(primary)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (8 * d).toInt())
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@VideoPlayerActivity)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = tracks.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                    object : RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.item_track, parent, false)) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                    val t = tracks[pos]
                    val v = h.itemView
                    val name = v.findViewById<TextView>(R.id.trackTitle)
                    name.text = t.title
                    name.setTextColor(if (pos == currentIdx) accent else primary)
                    v.findViewById<TextView>(R.id.trackArtist).text = t.artist
                    v.findViewById<TextView>(R.id.trackDuration).text =
                        if (t.durationMs > 0) formatDuration(t.durationMs) else ""
                    v.setOnClickListener {
                        dialog.dismiss()
                        if (pos != currentIdx) playIndex(pos, 0L)
                    }
                }
            }
            scrollToPosition((currentIdx - 2).coerceAtLeast(0))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.attr.colorBg))
            addView(title)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (420 * d).toInt()))
        }
        dialog.setContentView(root)
        dialog.show()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onPause() {
        super.onPause()
        fileView?.pause()
    }

    override fun finish() {
        fileView?.let { posMs = it.currentPosition.toLong() }
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_POSITION_MS, resultPos ?: posMs)
            .putExtra(EXTRA_TO_MUSIC, toMusic)
            .putExtra(EXTRA_CLOSE_ALL, closeAll))
        super.finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_down) // same slide as the music player
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(sleepRun)
        try { ytView?.release() } catch (_: Exception) { }
        fileView?.stopPlayback()
        loadToken++
        super.onDestroy()
    }
}
