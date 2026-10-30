package com.fxsoundlite.app

import android.content.Context

/**
 * Which player the mini bar opens: the full music player, or the video of the current song / TV channel.
 * Set when the user leaves the video player (the song goes on as audio in the mini bar), cleared when the music
 * player is used again. The mode stays when the song changes: in video mode the bar opens the video of the new song.
 *
 * The mode is saved, so it survives closing the app: call [init] once (MusicService / MainActivity do).
 * A live TV channel plays as a radio track in the service, marked with [TV_ALBUM], so every channel of the queue
 * can be turned back into its video (and is kept out of "Recently played").
 */
object PlayerMode {
    /** Album of a TV channel that plays as audio: tells it apart from a real radio station. */
    const val TV_ALBUM = "\u200BTV"

    private const val PREFS = "player_mode"
    @Volatile private var video = false
    @Volatile private var loaded = false
    private var appCtx: Context? = null

    @Synchronized
    fun init(c: Context) {
        if (loaded) return
        appCtx = c.applicationContext
        video = appCtx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("video", false)
        loaded = true
    }

    private fun save() {
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("video", video)?.apply()
    }

    fun setVideo(@Suppress("UNUSED_PARAMETER") t: Track? = null) { video = true; save() }

    fun setMusic() { video = false; save() }

    /** True for a TV channel that plays as audio (a radio track made by [tvToAudio]). */
    fun isTvAudio(t: Track?): Boolean = t != null && t.source == Track.SOURCE_RADIO && t.album == TV_ALBUM

    /** The audio twin of a TV channel: played by the service like a radio station. */
    fun tvToAudio(t: Track): Track = t.copy(source = Track.SOURCE_RADIO, durationMs = 0L, album = TV_ALBUM)

    /** True when the mini bar should open the video of [t]: a YouTube song, or a TV channel playing as audio. */
    fun isVideo(t: Track?): Boolean = video && t != null && (t.source == Track.SOURCE_YOUTUBE || isTvAudio(t))

    /**
     * Intent for the video player of the playing song [cur]. A TV channel playing as audio takes the other channels of the
     * audio queue along, so next / previous work in the video like in the music player (a YouTube song stays single).
     */
    fun videoIntent(c: Context, svc: MusicService, cur: Track, startMs: Long, fromPlayer: Boolean): android.content.Intent {
        if (isTvAudio(cur)) {
            val tv = svc.queueTracks.filter { isTvAudio(it) }
            val i = tv.indexOfFirst { it.key == cur.key }
            if (tv.size > 1 && i >= 0) return VideoPlayerActivity.queueIntent(c, tv, i, 0L, fromPlayer)
        }
        return VideoPlayerActivity.intent(c, videoTrack(cur), startMs, fromPlayer)
    }

    /** The track to hand to the video player: the TV channel for its audio twin, else [t] itself. */
    fun videoTrack(t: Track): Track = if (isTvAudio(t)) t.copy(source = Track.SOURCE_TV, album = "") else t
}
