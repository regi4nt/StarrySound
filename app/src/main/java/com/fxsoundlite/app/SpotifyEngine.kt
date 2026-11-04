package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.service.notification.NotificationListenerService
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
 *
 * Besides the polling, the engine listens to Spotify's own media session (needs notification access): play / pause,
 * seeking and song changes made in the Spotify app, its notification or a headset reach the player at once, and
 * play / pause / seek are sent through the session first, which also works without Premium or an active Web API device.
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
    private var sessionRetries = 0
    private val reconnectRun = Runnable { connectSessions() }
    // "Sound appeared / stopped" from the system: play / pause reaches the player even when Spotify's session cannot be read.
    private val audioWatch = SpotifyAudioWatch(context.applicationContext, handler) { onAudio(it) }
    private val syncRun = Runnable { syncFromSession() }

    // ---- Spotify's media session
    private var controller: MediaController? = null
    private var sessionsRegistered = false
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attachSession(list.orEmpty()) }
    private val sessionCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = syncFromSession()
        override fun onMetadataChanged(metadata: MediaMetadata?) = syncFromSession()
        override fun onSessionDestroyed() { attachSession(emptyList()) }
    }

    private companion object {
        val TOLERATE_PLAY = setOf(403)
        val TOLERATE_PAUSE = setOf(403, 404)
    }

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
        sessionRetries = 0
        connectSessions()
        audioWatch.start()
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
        val at = position // read on the main thread; the request runs on the io thread
        setClock(at, true)
        if (session { it.play() }) { handler.removeCallbacks(syncRun); handler.postDelayed(syncRun, 1600L); return }
        send(gen, tolerate = TOLERATE_PLAY) { SpotifyRemote.resume(app, uri, at) }
    }

    override fun pause() {
        quietUntil = SystemClock.elapsedRealtime() + 1500L
        setClock(position, false)
        if (session { it.pause() }) { handler.removeCallbacks(syncRun); handler.postDelayed(syncRun, 1600L); return }
        send(gen, tolerate = TOLERATE_PAUSE) { SpotifyRemote.pausePlayback(app) }
    }

    override fun seekTo(ms: Int) {
        quietUntil = SystemClock.elapsedRealtime() + 1500L
        setClock(ms, playing)
        if (session { it.seekTo(ms.toLong()) }) { handler.removeCallbacks(syncRun); handler.postDelayed(syncRun, 1600L); return }
        send(gen, tolerate = TOLERATE_PLAY) { SpotifyRemote.seek(app, ms) }
    }

    /** Spotify's own volume is not touched (fades and ducking only apply to the local players). */
    override fun setVolume(v: Float) {}

    override fun release() {
        released = true
        gen++
        handler.removeCallbacks(pollRun)
        handler.removeCallbacks(syncRun)
        handler.removeCallbacks(reconnectRun)
        audioWatch.stop()
        disconnectSessions()
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

    // ------------------------------------------------------------------ media session

    /** A transport command on Spotify's session, only once Spotify plays our song; false = use the Web API instead. */
    private fun session(block: (MediaController.TransportControls) -> Unit): Boolean {
        val c = controller ?: return false
        if (!confirmed) return false
        return try { block(c.transportControls); true } catch (e: Exception) { false }
    }

    private fun connectSessions() {
        if (!NowPlayingTracker.isAccessGranted(app)) return
        val m = app.getSystemService(MediaSessionManager::class.java) ?: return
        val me = ComponentName(app, NowPlayingListenerService::class.java)
        try {
            if (!sessionsRegistered) {
                m.addOnActiveSessionsChangedListener(sessionsListener, me, handler)
                sessionsRegistered = true
            }
            attachSession(m.getActiveSessions(me))
        } catch (e: SecurityException) {
            // Access is on but the system has not (re)connected the listener service (after an install / update / crash):
            // ask for a rebind and try again a few times. Meanwhile the audio signal and the Web API poll cover.
            sessionsRegistered = false
            try { NotificationListenerService.requestRebind(me) } catch (_: Exception) {}
            if (!released && sessionRetries++ < 5) { handler.removeCallbacks(reconnectRun); handler.postDelayed(reconnectRun, 3000L) }
        }
    }

    /** The phone started / stopped making media sound. Only trusted once Spotify confirmed our song, and not right after our own command. */
    private fun onAudio(active: Boolean) {
        if (released || ended || !confirmed) return
        if (SystemClock.elapsedRealtime() < quietUntil) return
        if (active == playing) return
        setClock(position, active)
        cb.onStateChanged()
        schedulePoll(1200L) // the poll / session then confirms (or corrects, e.g. when another app made the sound)
    }

    private fun disconnectSessions() {
        if (sessionsRegistered) {
            try { app.getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { android.util.Log.d("SpotifyEngine", "ignored: $e") }
            sessionsRegistered = false
        }
        controller?.let { try { it.unregisterCallback(sessionCallback) } catch (e: Exception) { android.util.Log.d("SpotifyEngine", "ignored: $e") } }
        controller = null
    }

    private fun attachSession(list: List<MediaController>) {
        if (released) return
        val next = list.firstOrNull { it.packageName == SpotifyAuth.PKG }
        if (next != null && next.sessionToken == controller?.sessionToken) { syncFromSession(); return }
        controller?.let { try { it.unregisterCallback(sessionCallback) } catch (e: Exception) { android.util.Log.d("SpotifyEngine", "ignored: $e") } }
        controller = next
        next?.registerCallback(sessionCallback, handler)
        syncFromSession()
    }

    private fun sessionPos(st: PlaybackState): Int {
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return pos.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Brings the clock / play state in line with Spotify's session: instant, no waiting for the next poll. */
    private fun syncFromSession() {
        if (released || ended) return
        val c = controller ?: return
        val st = c.playbackState ?: return
        val md = c.metadata
        val mid = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val spPlaying = st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING
        val pos = sessionPos(st)

        if (!confirmed) {
            if (mid.isNotEmpty() && mid == uri) { // Spotify switched to our song
                confirmed = true
                val d = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (d > 0) dur = d.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                setClock(pos, spPlaying)
                if (spPlaying && !started) { started = true; cb.onStarted() } else cb.onStateChanged()
                schedulePoll(2500L)
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now < quietUntil) { // right after our own command: look again once the dust has settled
            handler.removeCallbacks(syncRun)
            handler.postDelayed(syncRun, quietUntil - now + 100L)
            return
        }
        // Another song was picked in the Spotify app (or ours finished and Spotify went on): ours is over.
        if (mid.startsWith("spotify:track:") && mid != uri) { finish(); return }
        if (mid.isNotEmpty() && mid != uri) return // an ad / episode in between: leave the state alone

        val d = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (d > 0) dur = d.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val wasPlaying = playing
        if (wasPlaying && !spPlaying && dur > 0 && position >= dur - 4000) { finish(); return }
        if (spPlaying != wasPlaying || abs(pos - position) > 2000) setClock(pos, spPlaying)
        if (spPlaying != wasPlaying) cb.onStateChanged()
    }

    // ------------------------------------------------------------------ requests

    /** Runs a player request off the main thread; a refusal is reported once, a success starts the polling. */
    private fun send(my: Int, tolerate: Set<Int> = emptySet(), block: () -> Int) {
        try {
            io.execute {
                val code = try { block() } catch (e: SpotifyApi.ApiException) { e.code } catch (e: Exception) { -1 }
                handler.post {
                    if (my != gen || released) return@post
                    // Spotify answers 403 "restriction violated" when asked to pause / play what is already paused / playing
                    // (e.g. the Spotify app's own notification got there first): not an error, the poll reconciles the state.
                    if (code in 200..299 || code in tolerate) schedulePoll(700L) else fail(code)
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
