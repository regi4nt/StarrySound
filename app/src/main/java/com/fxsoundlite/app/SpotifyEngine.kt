package com.fxsoundlite.app

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Spotify as a playback backend of MusicService, like YouTube: a Spotify song is played from the StarrySound
 * player (full screen player, mini player, notification) and not from a separate screen.
 *
 * Spotify audio only plays inside the Spotify app, so this engine is a remote: it tells Spotify what to play,
 * pause or seek through the Web API (Premium + an active Spotify device), and polls what Spotify is doing every
 * few seconds to keep the clock, the play/pause state and the end of the song in sync. The StarrySound
 * equalizer still reaches the audio through [SpotifyEffects].
 */
class SpotifyEngine(
    context: Context,
    private val handler: Handler,
    private val cb: RemoteEngine.Callback
) : TimedEngine() {

    private val app = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()

    /** Spotify holds its own audio focus; taking ours would make Spotify pause itself. */
    override val needsAudioFocus: Boolean get() = false

    private var uri = ""
    private var gen = 0                // changes on load / release: answers of an older request are dropped
    private var released = false
    private var confirmed = false      // Spotify reported our song as the current one
    private var started = false        // onStarted was sent for this load
    private var ended = false
    private var loadedAt = 0L
    private var quietUntil = 0L        // right after our own command a poll may still show the old state
    private var failedPolls = 0

    /** The clock only runs once Spotify really plays our song (see TimedEngine.audible). */
    override val audible: Boolean get() = confirmed

    private val pollRun = Runnable { poll() }

    // ------------------------------------------------------------------ RemoteEngine

    override fun load(track: Track) {
        val my = ++gen
        uri = track.remoteId
        dur = track.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        confirmed = false
        started = false
        ended = false
        failedPolls = 0
        loadedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(pollRun)
        setClock(0, true) // optimistic, like the other engines
        if (!SpotifyAuth.isConnected(app)) {
            handler.post { if (my == gen && !released) fail(401) }
            return
        }
        send(my) { SpotifyRemote.playTrack(app, uri) }
    }

    override fun play() {
        val now = SystemClock.elapsedRealtime()
        if (now - loadedAt < 1500L) return // load() has just started the song
        quietUntil = now + 1500L
        setClock(position, true)
        send(gen) { SpotifyRemote.resume(app) }
    }

    override fun pause() {
        quietUntil = SystemClock.elapsedRealtime() + 1500L
        setClock(position, false)
        send(gen) { SpotifyRemote.pausePlayback(app) }
    }

    override fun seekTo(ms: Int) {
        quietUntil = SystemClock.elapsedRealtime() + 1500L
        setClock(ms, playing)
        send(gen) { SpotifyRemote.seek(app, ms) }
    }

    /** Spotify's own volume is not touched (fades and ducking only apply to the local players). */
    override fun setVolume(v: Float) {}

    override fun release() {
        released = true
        gen++
        handler.removeCallbacks(pollRun)
        val wasPlaying = playing
        playing = false
        try {
            // Another song / source took over (or the player stopped): Spotify must not keep playing on its own.
            if (wasPlaying) io.execute { try { SpotifyRemote.pausePlayback(app) } catch (e: Exception) { android.util.Log.d("SpotifyEngine", "ignored: $e") } }
            io.shutdown()
        } catch (e: Exception) {
            android.util.Log.d("SpotifyEngine", "ignored: $e")
        }
    }

    // ------------------------------------------------------------------ requests

    /** Runs a player request off the main thread; a refusal is reported once, a success starts the polling. */
    private fun send(my: Int, block: () -> Int) {
        try {
            io.execute {
                val code = try { block() } catch (e: SpotifyApi.ApiException) { e.code } catch (e: Exception) { -1 }
                handler.post {
                    if (my != gen || released) return@post
                    if (code in 200..299) schedulePoll(700L) else fail(code)
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("SpotifyEngine", "ignored: $e") // executor shut down: the engine is gone
        }
    }

    private fun fail(code: Int) {
        handler.removeCallbacks(pollRun)
        setClock(position, false)
        cb.onError(
            when (code) {
                404 -> RemoteEngine.ERR_SP_NO_DEVICE
                403 -> RemoteEngine.ERR_SP_FORBIDDEN
                401 -> RemoteEngine.ERR_SP_NOT_CONNECTED
                -1 -> RemoteEngine.ERR_NETWORK
                else -> RemoteEngine.ERR_GENERIC
            }
        )
    }

    // ------------------------------------------------------------------ polling

    private fun schedulePoll(delay: Long) {
        handler.removeCallbacks(pollRun)
        if (!released && !ended) handler.postDelayed(pollRun, delay)
    }

    private fun poll() {
        if (released || ended) return
        val my = gen
        try {
            io.execute {
                var json: JSONObject? = null
                var failed = false
                try { json = SpotifyApi.player(app) } catch (e: Exception) { failed = true }
                handler.post { if (my == gen && !released) onPoll(json, failed) }
            }
        } catch (e: Exception) {
            android.util.Log.d("SpotifyEngine", "ignored: $e")
        }
    }

    private fun onPoll(p: JSONObject?, failed: Boolean) {
        if (failed) { // offline for a moment: keep the clock, try again
            failedPolls++
            schedulePoll(5000L)
            return
        }
        failedPolls = 0
        val item = p?.optJSONObject("item")
        val itemUri = item?.optString("uri").orEmpty()
        val spPlaying = p?.optBoolean("is_playing") == true
        val progress = (p?.optLong("progress_ms", 0L) ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val spDur = (item?.optLong("duration_ms", 0L) ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        if (!confirmed) {
            if (itemUri == uri) {
                confirmed = true
                if (spDur > 0) dur = spDur
                setClock(progress, spPlaying)
                if (spPlaying && !started) { started = true; cb.onStarted() } else cb.onStateChanged()
                schedulePoll(2500L)
            } else if (SystemClock.elapsedRealtime() - loadedAt > 15_000L) {
                fail(0) // Spotify never switched to this song (not available, or refused silently)
            } else {
                schedulePoll(1200L)
            }
            return
        }

        if (SystemClock.elapsedRealtime() < quietUntil) { schedulePoll(1000L); return }

        // Spotify went on to another song: ours is over (it finished, or another song was picked in the Spotify app).
        if (itemUri.isNotEmpty() && itemUri != uri) { finish(); return }

        if (spDur > 0) dur = spDur
        val wasPlaying = playing
        val expected = position
        if (wasPlaying && !spPlaying && dur > 0 && expected >= dur - 4000) { // stopped at the end of the song
            finish()
            return
        }
        if (spPlaying != wasPlaying || !spPlaying || abs(progress - expected) > 2000) setClock(progress, spPlaying)
        if (spPlaying != wasPlaying) cb.onStateChanged()
        schedulePoll(if (spPlaying) 2500L else 6000L)
    }

    private fun finish() {
        if (ended) return
        ended = true
        handler.removeCallbacks(pollRun)
        setClock(dur, false)
        cb.onEnded()
    }
}
