package com.fxsoundlite.app

/**
 * Which player the mini bar opens: the full music player, or the video of the current song.
 * Set when the user leaves the video player (the song goes on as audio in the mini bar), cleared when the music
 * player is used again. The mode stays when the song changes: in video mode the bar opens the video of the new song.
 */
object PlayerMode {
    @Volatile private var video = false

    fun setVideo(@Suppress("UNUSED_PARAMETER") t: Track? = null) { video = true }
    fun setMusic() { video = false }

    /** True when the mini bar should open the video of [t] (only YouTube songs have one; others open the music player). */
    fun isVideo(t: Track?): Boolean = video && t != null && t.source == Track.SOURCE_YOUTUBE
}
