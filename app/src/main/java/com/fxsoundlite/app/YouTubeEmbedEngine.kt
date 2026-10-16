package com.fxsoundlite.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Plays YouTube with the official "android-youtube-player" library (a wrapper around YouTube's
 * IFrame Player API). Its [YouTubePlayerView] is never shown: it is owned by MusicService (a
 * foreground service), so playback continues with the app closed or the screen off, while the
 * service provides the media notification and lock-screen controls.
 *
 * Videos whose owner blocks embedding (or any embed that refuses to start) fall back to the audio
 * proxy of the StarryNightMPlayer website, played with MediaPlayer.
 *
 * Note: YouTube's terms discourage audio-only/background playback of the embedded player.
 */
class YouTubeEmbedEngine(
    context: Context,
    private val handler: Handler,
    private val cb: RemoteEngine.Callback
) : TimedEngine() {

    companion object {
        private val ID_RE = Regex("[A-Za-z0-9_-]{6,20}")

        private const val TAG = "YouTubeEmbed"
        /** If the player has neither started nor reported an error by then, try the fallback. */
        private const val START_TIMEOUT_MS = 15_000L

        /** Website that serves /api/yt-audio, the audio-stream proxy used by the fallback. */
        const val WEB_ORIGIN = "https://starrynight-mplayer.vercel.app"

        /** Embed errors where the audio proxy is worth a try (not 100 = video removed/private). */
        private val FALLBACK_ERRORS = setOf(5, 101, 150, 153)
    }

    override val needsAudioFocus = true

    /** Calls into the WebView-backed player can throw (WebView updating, view released, ...):
     *  that must never take the whole app down. */
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "YouTube player call failed", e)
        }
    }

    private val appContext = context.applicationContext
    private var view: YouTubePlayerView? = null
    private var player: YouTubePlayer? = null
    private var playerReady = false
    private var videoId: String? = null
    private var started = false
    private var released = false

    /** Embedder identity tried in order on error 153 (app id first, as android-youtube-player 13 does). */
    private val origins = listOf("https://" + appContext.packageName.lowercase(), "https://www.youtube.com")
    private var originIdx = 0

    // Fallback: plain audio stream from the website's /api/yt-audio proxy, played by MediaPlayer.
    private var fb: MediaPlayer? = null
    private var usingFallback = false
    private var fbFailCode = 0

    private val startTimeout = Runnable {
        if (released || started || !playing) return@Runnable
        Log.w(TAG, "No playback after $START_TIMEOUT_MS ms (playerReady=$playerReady, video=$videoId)")
        if (startFallback(RemoteEngine.ERR_YT_TIMEOUT)) return@Runnable
        setClock(basePos, false)
        handler.removeCallbacks(poll)
        cb.onError(RemoteEngine.ERR_YT_TIMEOUT)
    }

    /** Only used by the fallback: the library reports the position by itself. */
    private val poll = object : Runnable {
        override fun run() {
            if (released || !playing) return
            val m = fb ?: return
            try { setClock(m.currentPosition, true) } catch (_: Exception) { }
            handler.postDelayed(this, 1000)
        }
    }

    private val listener = object : AbstractYouTubePlayerListener() {
        override fun onReady(youTubePlayer: YouTubePlayer) {
            if (released) return
            player = youTubePlayer
            playerReady = true
            videoId?.let { if (!usingFallback) safe { youTubePlayer.loadVideo(it, 0f) } }
        }

        override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
            if (usingFallback) return
            when (state) {
                PlayerConstants.PlayerState.PLAYING -> handleState(1)
                PlayerConstants.PlayerState.PAUSED -> handleState(2)
                PlayerConstants.PlayerState.ENDED -> handleState(0)
                else -> Unit // buffering / unstarted / cued: the clock is re-synced by onCurrentSecond
            }
        }

        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
            if (released || usingFallback || !playing) return
            setClock((second * 1000).toInt(), true)
        }

        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
            if (released || usingFallback) return
            val d = (duration * 1000).toInt()
            if (d > 0 && d != dur) {
                dur = d
                cb.onStateChanged()
            }
        }

        override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
            if (released || usingFallback) return
            // Same numbers as YouTube's own error codes (kept for the messages in MusicFragment).
            val code = when (error) {
                PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST -> 2
                PlayerConstants.PlayerError.HTML_5_PLAYER -> 5
                PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> 100
                PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> 101
                PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER -> 153
                else -> 150
            }
            Log.w(TAG, "YouTube player error $error ($code) for video $videoId")
            val reported = if (code == 153) RemoteEngine.ERR_YT_CONFIG else RemoteEngine.ERR_YT_PLAYER_BASE + code
            handler.removeCallbacks(startTimeout)
            if (code == 153 && originIdx < origins.lastIndex) {
                originIdx++
                Log.w(TAG, "Error 153, retrying with origin ${origins[originIdx]}")
                handler.post { if (!released && !usingFallback) recreateView() }
                return
            }
            if (code in FALLBACK_ERRORS && startFallback(reported)) return
            setClock(basePos, false)
            handler.removeCallbacks(poll)
            cb.onError(reported)
        }
    }

    // ------------------------------------------------------------------ RemoteEngine

    override fun load(track: Track) {
        stopFallback()
        started = false
        dur = track.durationMs.toInt()
        setClock(0, false)
        handler.removeCallbacks(poll)
        if (!track.remoteId.matches(ID_RE)) {
            videoId = null
            cb.onError(RemoteEngine.ERR_YT_UNPLAYABLE)
            return
        }
        videoId = track.remoteId
        if (!ensureView()) {
            // The embedded player cannot be created on this device (no / broken WebView, ...):
            // go straight to the audio proxy instead of crashing.
            if (!startFallback(RemoteEngine.ERR_YT_CONFIG)) {
                handler.post { if (!released) cb.onError(RemoteEngine.ERR_YT_CONFIG) }
            }
            return
        }
        handler.removeCallbacks(startTimeout)
        handler.postDelayed(startTimeout, START_TIMEOUT_MS)
        if (playerReady) safe { player?.loadVideo(track.remoteId, 0f) } // else onReady() loads it
    }

    override fun play() {
        setClock(basePos, true) // optimistic; the real state arrives via onStateChange
        if (usingFallback) {
            try { fb?.start() } catch (_: Exception) { }
            handler.removeCallbacks(poll)
            handler.postDelayed(poll, 1000)
            return
        }
        safe { player?.play() }
    }

    override fun pause() {
        setClock(position, false)
        handler.removeCallbacks(poll)
        if (usingFallback) {
            try { fb?.pause() } catch (_: Exception) { }
            return
        }
        safe { player?.pause() }
    }

    override fun seekTo(ms: Int) {
        setClock(ms, playing)
        if (usingFallback) {
            try { fb?.seekTo(ms) } catch (_: Exception) { }
            return
        }
        safe { player?.seekTo(ms / 1000f) }
    }

    override fun setVolume(v: Float) {
        if (usingFallback) {
            try { fb?.setVolume(v, v) } catch (_: Exception) { }
            return
        }
        safe { player?.setVolume((v * 100).toInt().coerceIn(0, 100)) }
    }

    override fun release() {
        released = true
        stopFallback()
        handler.removeCallbacks(poll)
        handler.removeCallbacks(startTimeout)
        try {
            view?.release()
        } catch (_: Exception) {
        }
        view = null
        player = null
    }

    // ------------------------------------------------------------------ fallback

    /**
     * The embed refused this video (or never started): play its audio from the website's
     * /api/yt-audio proxy instead. Returns false when that is not possible.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun startFallback(origError: Int): Boolean {
        if (true) return false // audio proxy dropped: YouTubeEngine falls back to the stream engine first
        val id = videoId ?: return false
        if (usingFallback || released) return false
        usingFallback = true
        fbFailCode = origError
        handler.removeCallbacks(startTimeout)
        handler.removeCallbacks(poll)
        safe { player?.pause() }
        val startAt = basePos
        setClock(startAt, true)
        try {
            val mp = MediaPlayer()
            fb = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            mp.setWakeMode(appContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource("$WEB_ORIGIN/api/yt-audio?videoId=$id")
            mp.setOnPreparedListener {
                if (released || fb !== it) return@setOnPreparedListener
                if (it.duration > 0) dur = it.duration
                if (startAt > 0) it.seekTo(startAt)
                if (playing) it.start()
                started = true
                setClock(startAt, playing)
                handler.removeCallbacks(poll)
                if (playing) handler.postDelayed(poll, 1000)
                cb.onStarted()
            }
            mp.setOnCompletionListener {
                if (released || fb !== it) return@setOnCompletionListener
                handler.removeCallbacks(poll)
                setClock(0, false)
                started = false
                cb.onEnded()
            }
            mp.setOnErrorListener { m, what, extra ->
                Log.w(TAG, "Audio fallback failed: $what/$extra")
                if (!released && fb === m) {
                    handler.removeCallbacks(poll)
                    setClock(position, false)
                    cb.onError(fbFailCode)
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Audio fallback could not start", e)
            stopFallback()
            usingFallback = true // keep embed events ignored; report the original error
            handler.post { if (!released) cb.onError(origError) }
        }
        return true
    }

    private fun stopFallback() {
        fb?.let {
            try { it.setOnErrorListener(null); it.release() } catch (_: Exception) { }
        }
        fb = null
        usingFallback = false
    }

    // ------------------------------------------------------------------ internals

    private fun handleState(state: Int) {
        if (released) return
        when (state) {
            1 -> { // playing
                handler.removeCallbacks(startTimeout)
                val first = !started
                started = true
                setClock(basePos, true)
                if (first) cb.onStarted() else cb.onStateChanged()
            }
            2 -> { // paused (by us, by the system, or a remote command)
                if (started) {
                    setClock(position, false)
                    cb.onStateChanged()
                }
            }
            0 -> { // ended
                setClock(0, false)
                started = false
                cb.onEnded()
            }
        }
    }

    /** Creates the hidden player. Returns false (instead of throwing) when that is impossible. */
    private fun ensureView(): Boolean {
        if (view != null) return true
        return try {
            // Inflated from XML so that app:enableAutomaticInitialization="false" is honoured
            // (see view_youtube_player.xml); we initialize it ourselves just below.
            val v = LayoutInflater.from(appContext)
                .inflate(R.layout.view_youtube_player, null, false) as YouTubePlayerView
            v.enableBackgroundPlayback(true)
            // YouTube's documented identity for an Android app embed: https://<application id>.
            val origin = origins[originIdx]
            val options = IFramePlayerOptions.Builder(appContext)
                .controls(0)
                .rel(0)
                .fullscreen(0)
                .origin(origin)
                .build()
            v.initialize(listener, false, options)
            // Never attached to a window: give it a real size anyway, the player refuses tiny viewports.
            v.measure(
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
            )
            v.layout(0, 0, 640, 360)
            // A detached WebView pauses itself and its timers; keep the inner one running.
            findWebView(v)?.let { it.onResume(); it.resumeTimers() }
            view = v
            playerReady = false
            player = null
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not create the YouTube player view", e)
            view = null
            false
        }
    }

    /** Throws the player away and builds a new one (with the current origin); onReady() reloads the video. */
    private fun recreateView() {
        try { view?.release() } catch (_: Exception) { }
        view = null
        player = null
        playerReady = false
        if (!ensureView()) {
            if (!startFallback(RemoteEngine.ERR_YT_CONFIG)) cb.onError(RemoteEngine.ERR_YT_CONFIG)
            return
        }
        handler.removeCallbacks(startTimeout)
        handler.postDelayed(startTimeout, START_TIMEOUT_MS)
    }

    private fun findWebView(root: View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            findWebView(root.getChildAt(i))?.let { return it }
        }
        return null
    }
}
