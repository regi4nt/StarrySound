package com.fxsoundlite.app

import android.content.Context

/**
 * Which player the mini bar opens: the full music player, or the video of the current song / TV channel.
 * Set when the user leaves the video player (the song goes on as audio in the mini bar), cleared when the music
 * player is used again. The mode stays when the song changes: in video mode the bar opens the video of the new song.
 *
 * The mode is saved, so it survives closing the app: call [init] once (MusicService / MainActivity do).
 * A live TV channel plays as a radio track in the service; the channel is remembered here so the bar can reopen it.
 */
object PlayerMode {
    private const val PREFS = "player_mode"
    @Volatile private var video = false
    @Volatile private var tv: Track? = null
    @Volatile private var loaded = false
    private var appCtx: Context? = null

    @Synchronized
    fun init(c: Context) {
        if (loaded) return
        appCtx = c.applicationContext
        val p = appCtx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        video = p.getBoolean("video", false)
        val url = p.getString("tvUrl", "") ?: ""
        tv = if (url.isNotBlank()) Track(
            id = 0L, title = p.getString("tvTitle", "") ?: "", artist = p.getString("tvArtist", "") ?: "",
            album = "", durationMs = 0L, source = Track.SOURCE_TV, remoteId = url, artUrl = p.getString("tvArt", "") ?: ""
        ) else null
        loaded = true
    }

    private fun save() {
        val c = appCtx ?: return
        val t = tv
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("video", video)
            .putString("tvUrl", t?.remoteId ?: "")
            .putString("tvTitle", t?.title ?: "")
            .putString("tvArtist", t?.artist ?: "")
            .putString("tvArt", t?.artUrl ?: "")
            .apply()
    }

    fun setVideo(t: Track? = null) {
        video = true
        tv = if (t != null && t.source == Track.SOURCE_TV) t else null
        save()
    }

    fun setMusic() { video = false; tv = null; save() }

    private fun isTvAudio(t: Track?): Boolean {
        val c = tv
        return c != null && t != null && t.source == Track.SOURCE_RADIO && t.remoteId == c.remoteId
    }

    /** True when the mini bar should open the video of [t]: a YouTube song, or the TV channel that is playing as audio. */
    fun isVideo(t: Track?): Boolean = video && t != null && (t.source == Track.SOURCE_YOUTUBE || isTvAudio(t))

    /** The track to hand to the video player: the TV channel for its audio twin, else [t] itself. */
    fun videoTrack(t: Track): Track = if (isTvAudio(t)) tv ?: t else t
}
