package com.fxsoundlite.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Full-screen video: a YouTube video through the official IFrame player (with its own controls), or a video file
 * of the device through VideoView. It never touches [MusicService]: callers pause their audio first and get the
 * position the user reached in the result ([EXTRA_POSITION_MS]), so the audio can continue from there.
 */
class VideoPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_REMOTE_ID = "remote_id"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_POSITION_MS = "position_ms"

        fun intent(c: Context, t: Track, startMs: Long = 0L): Intent =
            Intent(c, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_SOURCE, t.source)
                .putExtra(EXTRA_REMOTE_ID, t.remoteId)
                .putExtra(EXTRA_START_MS, startMs)
    }

    private var posMs = 0L
    private var ytView: YouTubePlayerView? = null
    private var fileView: VideoView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            fileView?.let { if (it.isPlaying) posMs = it.currentPosition.toLong() }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        val source = intent.getIntExtra(EXTRA_SOURCE, -1)
        val id = intent.getStringExtra(EXTRA_REMOTE_ID).orEmpty()
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        posMs = startMs
        when {
            source == Track.SOURCE_YOUTUBE && id.isNotBlank() -> setupYoutube(root, id, startMs)
            source == Track.SOURCE_VIDEO && id.isNotBlank() -> setupFile(root, id, startMs)
            else -> { Toast.makeText(this, R.string.video_not_available, Toast.LENGTH_LONG).show(); finish(); return }
        }

        root.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            contentDescription = getString(android.R.string.cancel)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams((48 * resources.displayMetrics.density).toInt(), (48 * resources.displayMetrics.density).toInt(), Gravity.TOP or Gravity.START))
    }

    private fun setupYoutube(root: FrameLayout, id: String, startMs: Long) {
        val v = LayoutInflater.from(this).inflate(R.layout.view_youtube_player, root, false) as YouTubePlayerView
        v.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        root.addView(v)
        lifecycle.addObserver(v)
        val options = IFramePlayerOptions.Builder(this)
            .controls(1)
            .rel(0)
            .fullscreen(0) // we are already full screen
            .origin("https://" + packageName.lowercase())
            .build()
        v.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                youTubePlayer.loadVideo(id, startMs / 1000f)
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                posMs = (second * 1000).toLong()
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                // The owner may forbid embedding: hand over to the YouTube app / browser at the same second.
                Toast.makeText(this@VideoPlayerActivity, R.string.video_error, Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id&t=${posMs / 1000}s")))
                } catch (_: Exception) { }
                finish()
            }
        }, false, options)
        ytView = v
    }

    private fun setupFile(root: FrameLayout, uri: String, startMs: Long) {
        val vv = VideoView(this)
        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        root.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        vv.setOnPreparedListener { mp ->
            if (startMs > 0) mp.seekTo(startMs.toInt())
            vv.start()
        }
        vv.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.video_error_file, Toast.LENGTH_LONG).show()
            finish()
            true
        }
        vv.setVideoURI(Uri.parse(uri))
        fileView = vv
        handler.post(poll)
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
        super.onDestroy()
    }
}
