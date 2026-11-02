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

    /** Album of the audio twin of a video FILE (device / Drive): tells it apart from a real song. */
    const val VIDEO_ALBUM = "\u200BVID"

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

    /** A raw live TV channel (SOURCE_TV, e.g. saved in a playlist by an older version) becomes its audio twin; others stay as they are. */
    fun normalize(t: Track): Track = if (t.source == Track.SOURCE_TV && t.remoteId.isNotBlank()) tvToAudio(t) else t

    /** True for the audio twin of a video file (made by [videoToAudio]). */
    fun isVideoAudio(t: Track?): Boolean = t != null && t.album == VIDEO_ALBUM &&
        ((t.source == Track.SOURCE_LOCAL && t.remoteId.isNotBlank() && t.id < 0L) || t.source == Track.SOURCE_DRIVE)

    /**
     * The audio twin of a video file: a device video becomes a local song read from the same content uri, a Drive video a
     * Drive song with the same file id. The id is negated so it never collides with a real song; [videoTrack] undoes it.
     */
    fun videoToAudio(t: Track): Track = when (t.source) {
        Track.SOURCE_VIDEO -> t.copy(source = Track.SOURCE_LOCAL, id = -t.id - 1L, album = VIDEO_ALBUM)
        Track.SOURCE_DRIVE_VIDEO -> t.copy(source = Track.SOURCE_DRIVE, album = VIDEO_ALBUM)
        else -> t
    }

    /** True when the mini bar should open the video of [t]: a YouTube song, a TV channel or a video file playing as audio. */
    fun isVideo(t: Track?): Boolean = video && t != null && (t.source == Track.SOURCE_YOUTUBE || isTvAudio(t) || isVideoAudio(t))

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
    fun videoTrack(t: Track): Track = when {
        isTvAudio(t) -> t.copy(source = Track.SOURCE_TV, album = "")
        isVideoAudio(t) && t.source == Track.SOURCE_LOCAL -> t.copy(source = Track.SOURCE_VIDEO, id = -t.id - 1L, album = "")
        isVideoAudio(t) -> t.copy(source = Track.SOURCE_DRIVE_VIDEO, album = "")
        else -> t
    }
}
