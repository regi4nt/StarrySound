package com.fxsoundlite.app

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
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
import androidx.core.content.ContextCompat
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
        private const val M_QUALITY = 6
        private const val M_PIP = 7
        private const val M_CAPTIONS = 8
        private const val CC_PREFS = "video_captions"
        private const val CC_KEY = "on" // captions (subtitles) of YouTube videos: off by default
        private const val PIP_ACTION = "com.fxsoundlite.app.VIDEO_PIP_ACTION"
        private const val PIP_EXTRA = "pip_cmd"
        private const val PIP_PLAY_PAUSE = 1
        private const val PIP_PREV = 2
        private const val PIP_NEXT = 3
        private const val VQ_PREFS = "video_quality"
        private const val VQ_KEY = "height" // 0 = automatic (the YouTube player)

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

        binding.vpClose.setOnClickListener { leave(closeArrow = true) }
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
        binding.vpLandVolume.setOnClickListener { showSystemVolume() }
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
                    else -> leave(closeArrow = false)
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

    // The music service is only bound (not started) while nothing plays, and the page under this player unbinds when
    // it stops: without this the service is gone by the time "switch to music" is tapped ("not ready").
    private var musicBound = false
    private val musicConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {}
        override fun onServiceDisconnected(name: android.content.ComponentName?) {}
    }

    override fun onStart() {
        super.onStart()
        applyPlayerBackground()
        if (!musicBound) musicBound = bindService(Intent(this, MusicService::class.java), musicConn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        binding.vpBgAnimated.stop() // no animation loop while off screen
        if (musicBound) { musicBound = false; unbindService(musicConn) }
        super.onStop()
        // The X of the PiP window: on some Android versions the mode change arrives before onStop (page still
        // "started"), so it looked like an expand. A stop right after leaving PiP means it was closed: stop and close.
        if (inPip || pipClosedMaybe) {
            pipClosedMaybe = false
            pausePlayback()
            finish()
        }
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
        showQuality(null)
        val id = t.remoteId
        when (t.source) {
            Track.SOURCE_YOUTUBE -> {
                val wantH = videoQualityPref()
                if (wantH > 0 && ytFallbackFor != t.key) {
                    playYoutubeAsFile(t, startMs, wantH) // the quality the user picked; falls back to the YouTube player if it is gone
                } else {
                    dropFile()
                    val p = yt
                    if (p != null) p.loadVideo(id, startMs / 1000f) else setupYoutube(id, startMs)
                }
            }
            Track.SOURCE_VIDEO -> { dropYoutube(); setupFile(Uri.parse(id), null, startMs) }
            Track.SOURCE_TV -> { dropYoutube(); setupFile(Uri.parse(id), null, 0L) } // live stream: no start position
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
        if (inPip) { applyPipLayout(); return }
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
        !inPip && (full || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

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
                top.addView(panel, top.indexOfChild(binding.vpLandVolume),
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
            // Portrait: as tall as the text, exactly like the music player's time row (so the title sits at the same place).
            t.layoutParams = t.layoutParams.apply { height = if (on) dp(24) else ViewGroup.LayoutParams.WRAP_CONTENT }
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
        if (inPip) return
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
            .ccLoadPolicy(if (captionsPref()) 1 else 0) // 1 = captions shown from the start (when the video has them)
            .langPref(java.util.Locale.getDefault().language)
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

            override fun onPlaybackQualityChange(youTubePlayer: YouTubePlayer, playbackQuality: PlayerConstants.PlaybackQuality) {
                if (youTubePlayer !== yt) return
                showQuality(when (playbackQuality) {
                    PlayerConstants.PlaybackQuality.SMALL -> "240p"
                    PlayerConstants.PlaybackQuality.MEDIUM -> "360p"
                    PlayerConstants.PlaybackQuality.LARGE -> "480p"
                    PlayerConstants.PlaybackQuality.HD720 -> "720p"
                    PlayerConstants.PlaybackQuality.HD1080 -> "1080p"
                    PlayerConstants.PlaybackQuality.HIGH_RES -> "HD+"
                    else -> return // unknown / default: keep what is shown
                })
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                durMs = (duration * 1000).toLong()
                updateUi()
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                // The owner may forbid embedding (or the embedded player refuses): first try the video's own stream link
                // in the plain video view; only when that fails too, skip / hand over to the YouTube app / browser.
                val t = queue.getOrNull(index)
                if (t != null && t.source == Track.SOURCE_YOUTUBE && ytFallbackFor != t.key) {
                    ytFallbackFor = t.key
                    playYoutubeAsFile(t, posMs)
                    return
                }
                youtubeFailed(id)
            }
        }, false, options)
        ytView = v
    }

    /** Key of the video that already got its stream-link fallback (one try per video). */
    private var ytFallbackFor: String? = null

    /** Embedded player failed: resolve the video's MP4 stream and play it in the plain video view instead. */
    private fun playYoutubeAsFile(t: Track, startMs: Long, height: Int = 0) {
        val token = loadToken
        val app = applicationContext
        Thread {
            val url = try {
                (if (height > 0) YouTubeStreamEngine.videoChoices(t.remoteId).let { l -> (l.firstOrNull { it.first == height } ?: l.lastOrNull { it.first < height })?.second } else null)
                    ?: YouTubeStreamEngine.videoLink(app, t.remoteId)
            } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed || token != loadToken) return@runOnUiThread
                if (url == null) {
                    if (height > 0) { ytFallbackFor = t.key; playIndex(index, startMs); return@runOnUiThread } // chosen height unavailable: YouTube player
                    youtubeFailed(t.remoteId); return@runOnUiThread
                }
                dropYoutube()
                setupFile(Uri.parse(url), mapOf("User-Agent" to YouTubeStreamEngine.HTTP_USER_AGENT), startMs)
                updateUi()
            }
        }.start()
    }

    private fun youtubeFailed(id: String) {
        Toast.makeText(this, R.string.video_error, Toast.LENGTH_LONG).show()
        if (queue.size > 1) { step(1); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id&t=${posMs / 1000}s")))
        } catch (_: Exception) { }
        finish()
    }

    /** Shows the video quality (e.g. 1080p) to the right of the artist; null / empty hides it. */
    private fun showQuality(text: String?) {
        val tv = binding.vpQuality
        if (text.isNullOrEmpty()) { tv.visibility = View.GONE; return }
        tv.text = text
        tv.visibility = View.VISIBLE
    }

    /** Standard resolution name of a video of [w] x [h] pixels (1920x804 and 1080x1920 both count as 1080p). */
    private fun qualityLabel(w: Int, h: Int): String? {
        if (w <= 0 || h <= 0) return null
        val ref = maxOf(maxOf(w, h) * 9 / 16, minOf(w, h))
        val std = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)
        val side = std.lastOrNull { it <= ref * 105 / 100 } ?: std[0]
        return if (side >= 2160) "${side}p  \u2022 4K" else "${side}p"
    }

    private fun setupFile(uri: Uri, headers: Map<String, String>?, startMs: Long) {
        dropFile()
        val vv = VideoView(this)
        binding.vpVideoFrame.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        vv.setOnPreparedListener { mp ->
            filePlayer = mp
            showQuality(qualityLabel(mp.videoWidth, mp.videoHeight))
            updatePipParams() // PiP window gets this video's own ratio
            mp.setOnVideoSizeChangedListener { m, w, h -> if (m === filePlayer) { showQuality(qualityLabel(w, h)); updatePipParams() } }
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
        if (current.source == Track.SOURCE_TV) return // live
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
        val live = current.source == Track.SOURCE_TV
        binding.vpSeek.isEnabled = !live
        if (live) { // live TV: nothing to seek in
            binding.vpTotal.text = "LIVE"
            binding.vpElapsed.text = ""
            binding.vpSeek.max = 1
            binding.vpSeek.progress = 0
        } else {
            binding.vpTotal.text = formatDuration(durMs)
            binding.vpSeek.max = (durMs / 1000L).toInt().coerceAtLeast(1)
            if (!dragging) {
                binding.vpSeek.progress = (posMs / 1000L).toInt()
                binding.vpElapsed.text = formatDuration(posMs)
            }
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
        refreshPipActions()
    }

    // ------------------------------------------------------------------ music mode

    /** Switches the current YouTube song from video to music, continuing at the second the video reached. */
    private fun switchToMusic() {
        val t = current
        if (t.source == Track.SOURCE_TV) {
            val svc = MusicService.instance
            if (svc == null) {
                Toast.makeText(this, R.string.video_music_not_ready, Toast.LENGTH_LONG).show()
                return
            }
            pausePlayback() // the video must let go of the sound (and audio focus) before the audio starts
            if (!playTvAsAudio(svc, t)) return
            resultPos = -1L // live: nothing for the caller to seek
            if (!fromPlayer) startActivity(Intent(this, NowPlayingActivity::class.java))
            finish()
            return
        }
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
        PlayerMode.setMusic() // the mini bar opens the music player again
        pausePlayback() // the video must let go of the sound (and audio focus) before the music starts
        if (svc.current?.key == t.key) {
            // The music player already has this song (the video was opened from it): continue it from here.
            if (fromPlayer) {
                toMusic = true // its caller seeks and resumes
            } else {
                svc.seekTo(pos.toInt())
                svc.play()
                ensurePlaying(svc, t.key)
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

    /**
     * The user leaves the video (back / close arrow). Like the music player, the song stays in the mini player:
     * a YouTube video carries on as music from the same second (paused stays paused) and the mini bar of the page
     * underneath shows it. Opened from the music player, or a video file (no audio version): plain close.
     */
    private fun leave(closeArrow: Boolean) {
        val t = current
        val svc = MusicService.instance
        if (fromPlayer) {
            // Opened from the music player: back returns to it (music mode); the close arrow leaves both and the
            // mini bar then opens the video again.
            closeAll = closeArrow
            if (closeArrow && t.source == Track.SOURCE_YOUTUBE) PlayerMode.setVideo(t) else PlayerMode.setMusic()
            finish(); return
        }
        if (t.source == Track.SOURCE_TV && svc != null) {
            // Live TV: carry on as audio only (a paused channel is simply closed: no position to come back to).
            val wasPlaying = playing
            pausePlayback()
            if (wasPlaying && playTvAsAudio(svc, t)) {
                PlayerMode.setVideo(t) // the mini bar opens the channel's video again
                resultPos = -1L
            }
            finish(); return
        }
        if (t.source != Track.SOURCE_YOUTUBE || svc == null) { finish(); return }
        val pos = posMs
        val wasPlaying = playing
        pausePlayback()
        if (svc.current?.key == t.key) {
            // The service already has this song (the video was opened from the mini bar): continue it from here.
            svc.seekTo(pos.toInt())
            if (wasPlaying) svc.play() else svc.pause()
        } else {
            val songs = queue.filter { it.source == Track.SOURCE_YOUTUBE }
            svc.setQueueAndPlay(songs, songs.indexOfFirst { it.key == t.key }.coerceAtLeast(0))
            seekWhenReady(svc, t.key, pos, wasPlaying)
        }
        PlayerMode.setVideo(t) // the mini bar opens the video again
        resultPos = -1L // nothing for the caller to seek
        finish()
    }

    /** Plays the live TV channel [t] (and the other channels of the queue) as audio, like a radio station. */
    private fun playTvAsAudio(svc: MusicService, t: Track): Boolean {
        val channels = queue.filter { it.source == Track.SOURCE_TV && it.remoteId.isNotBlank() }
        if (channels.isEmpty()) return false
        val radio = channels.map { it.copy(source = Track.SOURCE_RADIO, durationMs = 0L, album = it.album.ifBlank { "TV" }) }
        svc.setQueueAndPlay(radio, channels.indexOfFirst { it.key == t.key }.coerceAtLeast(0))
        return true
    }

    /** The first play() can be refused while the video still holds the audio focus: try again a few times. */
    private fun ensurePlaying(svc: MusicService, key: String) {
        val h = Handler(Looper.getMainLooper())
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

    /** The new song loads in the service; once it is ready, jump to the video's position (and pause if it was paused). */
    private fun seekWhenReady(svc: MusicService, key: String, pos: Long, play: Boolean = true) {
        if (pos < 3000L && play) return
        val h = Handler(Looper.getMainLooper())
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                tries++
                if (svc.current?.key == key && svc.duration > 0) {
                    if (pos >= 3000L) svc.seekTo(pos.toInt())
                    if (!play) svc.pause()
                    return
                }
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
        if (t.source == Track.SOURCE_YOUTUBE) popup.menu.add(0, M_QUALITY, 0, R.string.np_video_quality)
        // Captions only exist in the YouTube player (the plain video view has no subtitle track).
        if (t.source == Track.SOURCE_YOUTUBE && ytView != null) {
            popup.menu.add(0, M_CAPTIONS, 0, getString(R.string.np_captions_fmt,
                getString(if (captionsPref()) R.string.np_captions_on else R.string.np_captions_off)))
        }
        popup.menu.add(0, M_TIMER, 1, R.string.np_sleep_timer)
        if (t.source == Track.SOURCE_YOUTUBE) popup.menu.add(0, M_DOWNLOAD, 2, R.string.np_download)
        if (pipSupported()) popup.menu.add(0, M_PIP, 3, R.string.np_pip)
        popup.menu.add(0, M_SHARE, 3, R.string.np_share)
        popup.menu.add(0, M_INFO, 5, R.string.np_info)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                M_SPEED -> showSpeedDialog()
                M_QUALITY -> showVideoQualityDialog()
                M_CAPTIONS -> toggleCaptions(t)
                M_TIMER -> showSleepTimerDialog()
                M_DOWNLOAD -> SongDownloader.download(this, t)
                M_PIP -> enterPip()
                M_SHARE -> SongShareCard.share(this, t, null, trackLink(t), getString(R.string.np_share_via))
                M_INFO -> showInfo(t)
            }
            true
        }
        popup.show()
    }

    private fun captionsPref(): Boolean = getSharedPreferences(CC_PREFS, Context.MODE_PRIVATE).getBoolean(CC_KEY, false)

    /**
     * Captions on / off of the YouTube player. The IFrame player only reads its caption policy when it is created, so the
     * player is rebuilt with the new setting and goes on from the current second.
     */
    private fun toggleCaptions(t: Track) {
        if (t.source != Track.SOURCE_YOUTUBE || ytView == null) return
        val on = !captionsPref()
        getSharedPreferences(CC_PREFS, Context.MODE_PRIVATE).edit().putBoolean(CC_KEY, on).apply()
        Toast.makeText(this, if (on) R.string.np_captions_toast_on else R.string.np_captions_toast_off, Toast.LENGTH_SHORT).show()
        val start = posMs
        val wasPlaying = playing
        showQuality(null)
        dropYoutube()
        setupYoutube(t.remoteId, start)
        if (!wasPlaying) handler.postDelayed({ if (!isDestroyed) yt?.pause() }, 1500L)
        updateUi()
    }

    private fun videoQualityPref(): Int = getSharedPreferences(VQ_PREFS, Context.MODE_PRIVATE).getInt(VQ_KEY, 0)

    /**
     * Video quality of a YouTube video: "Automatic" = the YouTube player (it picks the quality itself), or one of the
     * progressive MP4 heights YouTube offers for this video (picture + sound in one stream), played in the plain video view.
     */
    private fun showVideoQualityDialog() {
        val t = queue.getOrNull(index) ?: return
        if (t.source != Track.SOURCE_YOUTUBE) return
        Toast.makeText(this, R.string.video_quality_loading, Toast.LENGTH_SHORT).show()
        val token = loadToken
        Thread {
            val choices = try { YouTubeStreamEngine.videoChoices(t.remoteId) } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed || token != loadToken) return@runOnUiThread
                if (choices == null) { Toast.makeText(this, R.string.video_quality_failed, Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                if (choices.isEmpty()) { Toast.makeText(this, R.string.video_quality_none, Toast.LENGTH_LONG).show() }
                val heights = listOf(0) + choices.map { it.first }
                val labels = heights.map { if (it == 0) getString(R.string.video_quality_auto) else "${it}p" }.toTypedArray()
                val cur = videoQualityPref()
                val checked = heights.indexOf(cur).let { if (it < 0) 0 else it }
                AlertDialog.Builder(this)
                    .setTitle(R.string.np_video_quality)
                    .setSingleChoiceItems(labels, checked) { d, which ->
                        d.dismiss()
                        val h = heights[which]
                        getSharedPreferences(VQ_PREFS, Context.MODE_PRIVATE).edit().putInt(VQ_KEY, h).apply()
                        if (h == cur) return@setSingleChoiceItems
                        applyVideoQuality(t, h, choices)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    /** Switches the playing YouTube video to [height] (0 = back to the YouTube player), continuing from the current second. */
    private fun applyVideoQuality(t: Track, height: Int, choices: List<Pair<Int, String>>) {
        val start = posMs
        showQuality(null)
        if (height == 0) {
            ytFallbackFor = null
            dropFile()
            if (yt != null) yt?.loadVideo(t.remoteId, start / 1000f) else setupYoutube(t.remoteId, start)
        } else {
            val url = choices.firstOrNull { it.first == height }?.second ?: return
            dropYoutube()
            setupFile(Uri.parse(url), mapOf("User-Agent" to YouTubeStreamEngine.HTTP_USER_AGENT), start)
        }
        updateUi()
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
        // The PiP window keeps playing. onPause comes before onPictureInPictureModeChanged when PiP starts, so
        // isInPictureInPictureMode alone is not reliable here: also check our own flags.
        if (!isInPictureInPictureMode && !inPip && !pipEntering) fileView?.pause()
    }

    override fun onResume() {
        super.onResume()
        pipClosedMaybe = false // back on screen: the PiP window was expanded, not closed
    }

    // ------------------------------------------------------------------ picture-in-picture

    /** True while the video is shown in the small PiP window (only the video, no controls of this page). */
    private var inPip = false
    private var pipReceiver: BroadcastReceiver? = null
    private var pipShownPlaying: Boolean? = null
    private var pipEntering = false      // enterPip() was called, the mode change did not arrive yet
    private var pipClosedMaybe = false   // left PiP while not resumed: expand (onResume follows) or close (onStop follows)

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Window shape of the PiP: the video's own ratio (kept inside what Android accepts), 16:9 until it is known. */
    private fun pipRatio(): Rational {
        val mp = filePlayer
        var w = mp?.videoWidth ?: 0
        var h = mp?.videoHeight ?: 0
        if (w <= 0 || h <= 0) { w = 16; h = 9 }
        val r = w.toFloat() / h
        return when {
            r > 2.39f -> Rational(239, 100)
            r < 0.42f -> Rational(42, 100)
            else -> Rational(w, h)
        }
    }

    private fun pipAction(icon: Int, title: String, cmd: Int): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            this, cmd, Intent(PIP_ACTION).setPackage(packageName).putExtra(PIP_EXTRA, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pi)
    }

    private fun pipParams(): PictureInPictureParams {
        val actions = ArrayList<RemoteAction>()
        val multi = queue.size > 1
        if (multi) actions.add(pipAction(R.drawable.ic_prev, getString(R.string.pip_prev), PIP_PREV))
        actions.add(
            if (playing) pipAction(R.drawable.ic_pause, getString(R.string.pip_pause), PIP_PLAY_PAUSE)
            else pipAction(R.drawable.ic_play, getString(R.string.pip_play), PIP_PLAY_PAUSE))
        if (multi) actions.add(pipAction(R.drawable.ic_next, getString(R.string.pip_next), PIP_NEXT))
        return PictureInPictureParams.Builder().setAspectRatio(pipRatio()).setActions(actions).build()
    }

    /** Menu "Picture-in-Picture": the video goes on in a small floating window over the other apps. */
    private fun enterPip() {
        if (!pipSupported()) { Toast.makeText(this, R.string.pip_not_supported, Toast.LENGTH_SHORT).show(); return }
        try {
            pipEntering = true
            if (enterPictureInPictureMode(pipParams())) pipShownPlaying = playing else pipEntering = false
        } catch (_: Exception) {
            pipEntering = false
            Toast.makeText(this, R.string.pip_not_supported, Toast.LENGTH_SHORT).show()
        }
    }

    /** Window shape + buttons again (e.g. the real video size is known only after the video is prepared, or the next video has another ratio). */
    private fun updatePipParams() {
        if (!inPip) return
        pipShownPlaying = playing
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) { }
    }

    /** Refreshes the play / pause button of the PiP window when the state changed. */
    private fun refreshPipActions() {
        if (!inPip || pipShownPlaying == playing) return
        pipShownPlaying = playing
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) { }
    }

    /** PiP window: header, title, controls and second row are hidden, the video fills the whole window. */
    private fun applyPipLayout() {
        handler.removeCallbacks(hideControls)
        binding.vpHeader.visibility = View.GONE
        binding.vpInfo.visibility = View.GONE
        binding.vpRow2.visibility = View.GONE
        binding.vpControls.visibility = View.GONE
        binding.vpLandTop.visibility = View.GONE
        binding.vpCenter.visibility = View.GONE
        binding.vpContent.setPadding(0, 0, 0, 0)
        (binding.vpStage.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = 0
        binding.vpStage.requestLayout()
        for (v in listOf<View>(binding.vpVideoFrame, binding.vpTouch)) {
            v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.CENTER
            }
        }
    }

    private fun registerPipReceiver() {
        if (pipReceiver != null) return
        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, i: Intent?) {
                when (i?.getIntExtra(PIP_EXTRA, 0)) {
                    PIP_PLAY_PAUSE -> togglePlay()
                    PIP_PREV -> step(-1)
                    PIP_NEXT -> step(1)
                }
                pipShownPlaying = null // the state may change a moment later: let the next update redraw the button
                handler.postDelayed({ refreshPipActions() }, 400L)
            }
        }
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterPipReceiver() {
        pipReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        pipReceiver = null
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        pipEntering = false
        if (isInPictureInPictureMode) {
            registerPipReceiver()
            applyPipLayout()
        } else {
            unregisterPipReceiver()
            pipShownPlaying = null
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) pipClosedMaybe = true
            // The X of the PiP window closes it while this page is no longer visible: stop the video and close.
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                pausePlayback()
                finish()
                return
            }
            // Back to the full page (tap on the PiP window): bring the normal layout back.
            (binding.vpStage.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = dp(12)
            applyOrientation()
            binding.vpStage.post { applyVideoSize() }
        }
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
        unregisterPipReceiver()
        handler.removeCallbacks(poll)
        handler.removeCallbacks(sleepRun)
        try { ytView?.release() } catch (_: Exception) { }
        fileView?.stopPlayback()
        loadToken++
        super.onDestroy()
    }
}
