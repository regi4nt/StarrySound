package com.fxsoundlite.app

import android.content.Context
import android.os.Handler
import android.util.Log

/**
 * YouTube playback with two backends, tried in order:
 *  1. [YouTubeStreamEngine]: NewPipeExtractor resolves a direct audio stream (no embed, no error 153).
 *  2. [YouTubeEmbedEngine]: the official IFrame player (android-youtube-player) in a hidden WebView,
 *     used when the first cannot resolve or play the video (e.g. extractor broken by a YouTube change).
 *  3. [YouTubeWebEngine]: the real youtube.com watch page in a hidden WebView, like a browser.
 * If all fail, the error of the last one is reported.
 */
class YouTubeEngine(
    context: Context,
    private val handler: Handler,
    private val cb: RemoteEngine.Callback
) : RemoteEngine {

    private val appContext = context.applicationContext

    private val stream = YouTubeStreamEngine(context, handler, forward { onStreamError(it) })
    private val embed by lazy { YouTubeEmbedEngine(context, handler, forward { onEmbedError(it) }) }
    private val web by lazy { YouTubeWebEngine(context, handler, forward(null)) }

    private var useEmbed = false
    private var useWeb = false
    private var current: Track? = null
    private var vol = 1f

    private val active: RemoteEngine get() = if (useWeb) web else if (useEmbed) embed else stream

    /** Only events from the engine that is currently active reach MusicService. */
    private fun forward(onError: ((Int) -> Unit)?) = object : RemoteEngine.Callback {
        override fun onStarted() = cb.onStarted()
        override fun onStateChanged() = cb.onStateChanged()
        override fun onEnded() = cb.onEnded()
        override fun onError(code: Int) { if (onError != null) onError(code) else cb.onError(code) }
    }

    private fun onStreamError(code: Int) {
        val t = current
        // A removed/private video will not play in the embed either; anything else is worth a try.
        if (useEmbed || t == null || code == RemoteEngine.ERR_NETWORK || MusicPrefs.saver(appContext)) {
            cb.onError(code)
            return
        }
        Log.w("YouTubeEngine", "Stream engine failed ($code), trying the embedded player")
        useEmbed = true
        embed.setVolume(vol)
        embed.load(t) // loadVideo() autoplays once the player is ready
        cb.onStateChanged()
    }

    private fun onEmbedError(code: Int) {
        val t = current
        if (useWeb || t == null || code == RemoteEngine.ERR_NETWORK || MusicPrefs.saver(appContext)) { cb.onError(code); return }
        Log.w("YouTubeEngine", "Embedded player failed ($code), trying the youtube.com page")
        embed.pause()
        useWeb = true
        web.setVolume(vol)
        web.load(t) // starts playing by itself (load() is optimistic, like play())
        cb.onStateChanged()
    }

    /** Format of the direct stream, or null while resolving / when a WebView player is in use (format unknown). */
    val audioInfo: AudioInfo? get() = if (useEmbed || useWeb) null else stream.currentInfo

    /** True when playing through the embedded / youtube.com player: YouTube picks the audio itself. */
    val usingWebPlayer: Boolean get() = useEmbed || useWeb

    override val needsAudioFocus get() = true
    override val isPlaying get() = active.isPlaying
    override val position get() = active.position
    override val duration get() = active.duration

    override fun load(track: Track) {
        current = track
        if (useEmbed) embed.pause()
        if (useWeb) web.pause()
        useEmbed = false
        useWeb = false
        stream.load(track)
    }

    override fun play() = active.play()
    override fun pause() = active.pause()
    override fun seekTo(ms: Int) = active.seekTo(ms)
    override fun setVolume(v: Float) { vol = v; active.setVolume(v) }

    override fun setSpeed(s: Float) = stream.setSpeed(s) // embed / web fallbacks play at normal speed

    override fun release() {
        stream.release()
        if (useEmbed) embed.release()
        if (useWeb) web.release()
    }
}
