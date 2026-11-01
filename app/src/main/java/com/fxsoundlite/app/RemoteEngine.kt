package com.fxsoundlite.app

import android.os.SystemClock

/**
 * A playback backend other than the framework MediaPlayer: the YouTube player (hidden WebView).
 * MusicService owns one at a time.
 * Every method and every callback runs on the main thread.
 */
interface RemoteEngine {

    interface Callback {
        /** The track actually started playing (first time after load/play). */
        fun onStarted()
        /** Play/pause state or duration changed on its own (e.g. paused from elsewhere). */
        fun onStateChanged()
        fun onEnded()
        fun onError(code: Int)
    }

    /** Whether MusicService must hold audio focus while this engine plays. */
    val needsAudioFocus: Boolean
    val isPlaying: Boolean
    /** Current position in ms (interpolated between updates from the real player). */
    val position: Int
    /** Duration in ms, 0 when unknown. */
    val duration: Int

    fun load(track: Track)
    fun play()
    fun pause()
    fun seekTo(ms: Int)
    fun setVolume(v: Float)
    /** Playback speed (1f = normal). Engines that cannot change speed ignore it. */
    fun setSpeed(s: Float) {}
    fun release()

    companion object {
        const val ERR_GENERIC = 1
        const val ERR_NETWORK = 2
        const val ERR_YT_NO_KEY = 3
        /** A radio station could not be played (stream down / unsupported). */
        const val ERR_RADIO = 30
        /** The first radio connection failed; the service is trying again (not final). */
        const val ERR_RADIO_RETRY = 31
        /** A Google Drive song could not be downloaded / played. */
        const val ERR_DRIVE = 32
        const val ERR_YT_UNPLAYABLE = 20
        const val ERR_YT_CONFIG = 21 // legacy (embedded-player error 153), no longer produced
        const val ERR_YT_TIMEOUT = 22 // the embedded player never started (no error, no playback)
        /** YouTube player errors other than 153 are reported as BASE + the player's own code. */
        const val ERR_YT_PLAYER_BASE = 1000
    }
}

/**
 * Keeps a locally interpolated clock, so the seek bar moves smoothly while the real player is
 * only polled every second or few seconds.
 */
abstract class TimedEngine : RemoteEngine {
    protected var playing = false
    protected var basePos = 0
    protected var baseTime = 0L
    protected var dur = 0
    protected var clockSpeed = 1f

    override val isPlaying: Boolean get() = playing
    override val duration: Int get() = dur
    /**
     * False until the real player is producing sound. [playing] is optimistic (set at load()/play()), so without
     * this the clock ran while the stream was still being resolved / buffered, and then jumped back to the start
     * as soon as the audio really began (the seek bar "restarted").
     */
    protected open val audible: Boolean get() = true

    override val position: Int
        get() {
            if (!playing || !audible) return basePos
            val p = basePos + ((SystemClock.elapsedRealtime() - baseTime) * clockSpeed).toInt()
            return if (dur > 0) p.coerceAtMost(dur) else p
        }

    protected fun setClock(pos: Int, isPlaying: Boolean) {
        basePos = pos
        baseTime = SystemClock.elapsedRealtime()
        playing = isPlaying
    }
}
