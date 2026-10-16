package com.fxsoundlite.app

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Android only lets an app read other apps' media sessions (the title/artist of whatever YouTube Music,
 * a podcast app, etc. is playing) through MediaSessionManager if the app holds "notification access",
 * and that permission is granted per NotificationListenerService. This class is what appears in that
 * system list; it never reads any notification.
 *
 * Two jobs:
 *  - When the system drops the connection (after the app process crashed or was killed) it asks to be
 *    bound again, otherwise reading media sessions silently fails until the user toggles the permission.
 *  - Taste learning: while [TasteSignals.externalEnabled] is on, it watches YouTube Music, YouTube and Spotify and,
 *    once a song has really been heard for [StatsStore.PLAY_THRESHOLD_MS], tells [TasteSignals] the artist.
 *    Nothing is recorded in private mode, and only artist names are kept (no titles, no timestamps).
 *    [NowPlayingTracker] (the Screensaver's now-playing line) is separate and unaffected.
 */
class NowPlayingListenerService : NotificationListenerService() {

    companion object {
        private const val PKG_YT_MUSIC = "com.google.android.apps.youtube.music"
        private const val PKG_YT = "com.google.android.youtube"
        private const val PKG_SPOTIFY = "com.spotify.music"
        /** Spotify also plays podcasts and ads: only songs of a plausible length count there. */
        private const val SP_MIN_MS = 30_000L
        private const val SP_MAX_MS = 20 * 60_000L
        /** The YouTube app plays every kind of video: only songs of a plausible length count there. */
        private const val YT_MIN_MS = 60_000L
        private const val YT_MAX_MS = 10 * 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var listening = false
    private var controllers: List<MediaController> = emptyList()

    // The song currently being timed.
    private var curKey: String? = null
    private var curArtist = ""
    private var heardMs = 0L
    private var playingSince = 0L   // elapsedRealtime when the current stretch of playing began; 0 = not playing
    private var counted = false

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate()
        override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
        override fun onSessionDestroyed() = evaluate()
    }

    private val tick = Runnable { evaluate() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        startListening()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        stopListening()
        try {
            requestRebind(ComponentName(this, NowPlayingListenerService::class.java))
        } catch (e: Exception) {
            // Access revoked in the meantime: nothing to rebind.
        }
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    private fun startListening() {
        if (listening) return
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        manager = m
        try {
            val me = ComponentName(this, NowPlayingListenerService::class.java)
            m.addOnActiveSessionsChangedListener(sessionsListener, me, handler)
            listening = true
            attach(m.getActiveSessions(me))
        } catch (e: Exception) {
            // Not (yet) allowed to read sessions; the system reconnects us and calls onListenerConnected again.
            listening = false
        }
    }

    private fun stopListening() {
        handler.removeCallbacks(tick)
        if (listening) {
            try { manager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) { }
            listening = false
        }
        detach()
        playingSince = 0L
    }

    private fun attach(list: List<MediaController>) {
        detach()
        controllers = list.filter { it.packageName in setOf(PKG_YT_MUSIC, PKG_YT, PKG_SPOTIFY) }
        controllers.forEach { it.registerCallback(callback, handler) }
        evaluate()
    }

    private fun detach() {
        controllers.forEach { try { it.unregisterCallback(callback) } catch (_: Exception) { } }
        controllers = emptyList()
    }

    /** One song heard in YouTube (Music) or Spotify right now, or null. */
    private class Heard(val key: String, val artist: String)

    private fun current(): Heard? {
        for (c in controllers) {
            if (c.playbackState?.state != PlaybackState.STATE_PLAYING) continue
            val m = c.metadata ?: continue
            val title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            if (title.isNullOrBlank() || artist.isNullOrBlank()) continue
            if (c.packageName == PKG_YT) {
                val d = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
                if (d !in YT_MIN_MS..YT_MAX_MS) continue   // unknown length (live) or too short / long for a song
            }
            if (c.packageName == PKG_SPOTIFY) {
                // Episodes and ads carry a non-track Spotify URI as media id (when Spotify provides one).
                val id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
                if (id.startsWith("spotify:") && !id.startsWith("spotify:track:")) continue
                if (artist.equals("Spotify", ignoreCase = true) || title.equals("Advertisement", ignoreCase = true)) continue
                val d = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
                if (d > 0 && d !in SP_MIN_MS..SP_MAX_MS) continue
            }
            return Heard("${c.packageName}|$title|$artist", artist)
        }
        return null
    }

    /** Called on every session change and by [tick]; keeps the play time of the current song up to date. */
    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        if (playingSince != 0L) {
            heardMs += now - playingSince
            playingSince = 0L
        }
        handler.removeCallbacks(tick)
        if (!TasteSignals.externalEnabled(this) || MusicPrefs.privateMode(this)) return

        val h = current() ?: return           // paused: keep heardMs, so pause + resume still adds up
        if (h.key != curKey) {
            curKey = h.key
            curArtist = h.artist
            heardMs = 0L
            counted = false
        }
        playingSince = now
        if (counted) return
        if (heardMs >= StatsStore.PLAY_THRESHOLD_MS) {
            counted = true
            TasteSignals.recordExternalPlay(this, curArtist)
        } else {
            handler.postDelayed(tick, StatsStore.PLAY_THRESHOLD_MS - heardMs)
        }
    }
}
