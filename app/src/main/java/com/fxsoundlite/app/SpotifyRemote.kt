package com.fxsoundlite.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Controls the Spotify app (it keeps playing the audio itself): play / pause / next / previous through its media
 * session (needs the notification access StarrySound already asks for under Settings > Online music) and
 * "play this playlist" through the Web API (Premium, and Spotify must have been opened once so it has a device).
 * The equalizer reaches that audio through [SpotifyEffects].
 */
object SpotifyRemote {
    fun isInstalled(c: Context): Boolean =
        try { c.packageManager.getPackageInfo(SpotifyAuth.PKG, 0); true } catch (_: Exception) { false }

    fun open(c: Context): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(SpotifyAuth.PKG) ?: return false
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun controller(c: Context): MediaController? = try {
        c.getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(c, NowPlayingListenerService::class.java))
            ?.firstOrNull { it.packageName == SpotifyAuth.PKG }
    } catch (_: SecurityException) { null } // notification access not granted

    fun playPause(c: Context) {
        val ctl = controller(c)
        if (ctl != null) {
            if (ctl.playbackState?.state == PlaybackState.STATE_PLAYING) ctl.transportControls.pause() else ctl.transportControls.play()
        } else key(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun next(c: Context) { controller(c)?.transportControls?.skipToNext() ?: key(c, KeyEvent.KEYCODE_MEDIA_NEXT) }
    fun previous(c: Context) { controller(c)?.transportControls?.skipToPrevious() ?: key(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

    /** Fallback when the media session is not readable: a media key sent straight to the Spotify app. */
    private fun key(c: Context, code: Int) {
        try {
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                c.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(SpotifyAuth.PKG)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code)))
            }
        } catch (e: Exception) { android.util.Log.d("SpotifyRemote", "ignored: " + e) }
    }

    /**
     * Blocking. Starts one song on the active Spotify device (or, with none active, on the first device Spotify lists).
     * Returns the HTTP code: 2xx = started, 404 = no device at all (open the Spotify app first), 403 = refused (Premium).
     */
    fun playTrack(c: Context, uri: String): Int {
        val body = org.json.JSONObject().put("uris", org.json.JSONArray().put(uri)).toString()
        val first = SpotifyApi.put(c, "/me/player/play", body)
        if (first.code != 404) return first.code
        val device = SpotifyApi.firstDeviceId(c) ?: return 404
        return SpotifyApi.put(c, "/me/player/play?device_id=" + java.net.URLEncoder.encode(device, "UTF-8"), body).code
    }

    /** Blocking. Resume / pause / seek through the Web API; the HTTP code is returned (2xx = done). */
    fun resume(c: Context): Int = SpotifyApi.put(c, "/me/player/play", "{}").code
    fun pausePlayback(c: Context): Int = SpotifyApi.put(c, "/me/player/pause", "{}").code
    fun seek(c: Context, ms: Int): Int = SpotifyApi.put(c, "/me/player/seek?position_ms=$ms", "{}").code

    /** Blocking. True when Spotify accepted the request. 404 = no active Spotify device (open the app first). */
    fun playPlaylist(c: Context, playlistId: String): Int {
        val r = SpotifyApi.put(c, "/me/player/play", "{\"context_uri\":\"spotify:playlist:$playlistId\"}")
        return r.code
    }
}
