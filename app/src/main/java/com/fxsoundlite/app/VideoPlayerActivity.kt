package com.fxsoundlite.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fxsoundlite.app.databinding.ActivityVideoPlayerBinding
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Video player styled like the music player: the video sits where the cover is, with the same title / artist,
 * seek bar and round play button below it (own controls; the YouTube player's controls are switched off).
 * Sources: a YouTube video (official IFrame player) or a video file of the device (VideoView).
 *
 * It never touches [MusicService]: callers pause their audio first and get the position the user reached in the
 * result ([EXTRA_POSITION_MS]), so the audio can continue from there.
 */
class VideoPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_REMOTE_ID = "remote_id"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_POSITION_MS = "position_ms"
        private const val EXTRA_Q_SOURCES = "q_sources"
        private const val EXTRA_Q_IDS = "q_ids"
        private const val EXTRA_Q_TITLES = "q_titles"
        private const val EXTRA_Q_ARTISTS = "q_artists"
        private const val EXTRA_INDEX = "q_index"

        fun intent(c: Context, t: Track, startMs: Long = 0L): Intent = queueIntent(c, listOf(t), 0, startMs)

        /** A playlist as a video queue: plays [index] first and goes on with the next ones. Only [isVideoPlayable] tracks should be passed. */
        fun queueIntent(c: Context, tracks: List<Track>, index: Int, startMs: Long = 0L): Intent =
            Intent(c, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_Q_SOURCES, IntArray(tracks.size) { tracks[it].source })
                .putExtra(EXTRA_Q_IDS, Array(tracks.size) { tracks[it].remoteId })
                .putExtra(EXTRA_Q_TITLES, Array(tracks.size) { tracks[it].title })
                .putExtra(EXTRA_Q_ARTISTS, Array(tracks.size) { tracks[it].artist })
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_START_MS, startMs)
    }

    private var qSources = IntArray(0)
    private var qIds = emptyArray<String>()
    private var qTitles = emptyArray<String>()
    private var qArtists = emptyArray<String>()
    private var index = 0
    private var loadToken = 0

    private lateinit var binding: ActivityVideoPlayerBinding
    private val handler = Handler(Looper.getMainLooper())

    private var ytView: YouTubePlayerView? = null
    private var yt: YouTubePlayer? = null
    private var fileView: VideoView? = null

    private var posMs = 0L
    private var durMs = 0L
    private var playing = false
    private var dragging = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        qSources = intent.getIntArrayExtra(EXTRA_Q_SOURCES) ?: IntArray(0)
        qIds = intent.getStringArrayExtra(EXTRA_Q_IDS) ?: emptyArray()
        qTitles = intent.getStringArrayExtra(EXTRA_Q_TITLES) ?: emptyArray()
        qArtists = intent.getStringArrayExtra(EXTRA_Q_ARTISTS) ?: emptyArray()
        val n = minOf(qSources.size, qIds.size, qTitles.size, qArtists.size)
        if (n == 0) { Toast.makeText(this, R.string.video_not_available, Toast.LENGTH_LONG).show(); finish(); return }
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(0, n - 1)
        val startMs = savedInstanceState?.getLong(EXTRA_POSITION_MS) ?: intent.getLongExtra(EXTRA_START_MS, 0L)

        binding.vpPrev.setOnClickListener { step(-1) }
        binding.vpNext.setOnClickListener { step(1) }
        val multi = n > 1
        binding.vpPrev.visibility = if (multi) View.VISIBLE else View.GONE
        binding.vpNext.visibility = if (multi) View.VISIBLE else View.GONE

        binding.vpClose.setOnClickListener { finish() }
        binding.vpPlay.setOnClickListener { togglePlay() }
        binding.vpBack.setOnClickListener { seekTo(posMs - 10_000L) }
        binding.vpForward.setOnClickListener { seekTo(posMs + 10_000L) }
        binding.vpRotate.setOnClickListener {
            val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
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

        applyOrientation()
        playIndex(index, startMs)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
        outState.putLong(EXTRA_POSITION_MS, posMs)
    }

    // ------------------------------------------------------------------ queue

    /** Next (+1) / previous (-1) video; wraps around. */
    private fun step(d: Int) {
        val n = qIds.size
        if (n < 2) return
        playIndex(((index + d) % n + n) % n, 0L)
    }

    /** Plays the video at [i]: swaps the video view when the kind of source changes. */
    private fun playIndex(i: Int, startMs: Long) {
        index = i
        val token = ++loadToken
        posMs = startMs
        durMs = 0L
        playing = false
        binding.vpTitle.text = qTitles[i]
        binding.vpArtist.text = qArtists[i]
        binding.vpArtist.visibility = if (qArtists[i].isBlank()) View.GONE else View.VISIBLE
        val id = qIds[i]
        when (qSources[i]) {
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
                            if (qIds.size > 1) step(1) else finish()
                        } else setupFile(Uri.parse(req.first), req.second, startMs)
                    }
                }.start()
            }
            else -> {
                Toast.makeText(this, R.string.video_not_available, Toast.LENGTH_SHORT).show()
                if (qIds.size > 1) step(1) else finish()
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
    }

    // ------------------------------------------------------------------ layout

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Portrait: header, 16:9 video, title, controls. Landscape: system bars hidden, the video takes the screen and only a control strip stays. */
    private fun applyOrientation() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !land)
        if (land) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        binding.vpHeader.visibility = if (land) View.GONE else View.VISIBLE
        binding.vpInfo.visibility = if (land) View.GONE else View.VISIBLE
        val dm = resources.displayMetrics
        val lp = binding.vpVideoFrame.layoutParams as LinearLayout.LayoutParams
        if (land) {
            val h = (dm.heightPixels - dp(120)).coerceAtLeast(dp(120))
            lp.width = minOf(dm.widthPixels, h * 16 / 9)
            lp.height = h
            lp.gravity = Gravity.CENTER_HORIZONTAL
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = dm.widthPixels * 9 / 16
            lp.gravity = Gravity.NO_GRAVITY
        }
        binding.vpVideoFrame.layoutParams = lp
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
                updateUi()
                if (state == PlayerConstants.PlayerState.ENDED && qIds.size > 1) step(1)
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
                if (qIds.size > 1) { step(1); return }
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
            durMs = mp.duration.toLong().coerceAtLeast(0L)
            if (startMs > 0) mp.seekTo(startMs.toInt())
            vv.start()
        }
        vv.setOnCompletionListener { playing = false; updateUi(); if (qIds.size > 1) step(1) }
        vv.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.video_error_file, Toast.LENGTH_LONG).show()
            if (qIds.size > 1) step(1) else finish()
            true
        }
        if (headers != null) vv.setVideoURI(uri, headers) else vv.setVideoURI(uri)
        fileView = vv
        handler.post(poll)
    }

    // ------------------------------------------------------------------ controls

    private fun togglePlay() {
        yt?.let { if (playing) it.pause() else it.play(); return }
        fileView?.let { if (it.isPlaying) it.pause() else it.start(); playing = it.isPlaying; updateUi() }
    }

    private fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE)
        posMs = target
        yt?.seekTo(target / 1000f)
        fileView?.seekTo(target.toInt())
        updateUi()
    }

    private fun updateUi() {
        if (!::binding.isInitialized) return
        binding.vpPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.vpTotal.text = formatDuration(durMs)
        binding.vpSeek.max = (durMs / 1000L).toInt().coerceAtLeast(1)
        if (!dragging) {
            binding.vpSeek.progress = (posMs / 1000L).toInt()
            binding.vpElapsed.text = formatDuration(posMs)
        }
    }

    override fun onPause() {
        super.onPause()
        fileView?.pause()
    }

    override fun finish() {
        fileView?.let { posMs = it.currentPosition.toLong() }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_POSITION_MS, posMs))
        super.finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        try { ytView?.release() } catch (_: Exception) { }
        fileView?.stopPlayback()
        loadToken++
        super.onDestroy()
    }
}
