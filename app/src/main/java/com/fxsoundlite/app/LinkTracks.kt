package com.fxsoundlite.app

import android.net.Uri

/**
 * Lets the search boxes accept a pasted link: a YouTube link in the YouTube search, a radio stream
 * link in the radio search. A link gives a one-song result list instead of a keyword search.
 */
object LinkTracks {

    /** watch?v=ID, youtu.be/ID, /shorts/ID, /embed/ID, /live/ID, /v/ID (music.youtube.com too). */
    private val YOUTUBE_ID = Regex(
        "(?:youtu\\.be/|(?:youtube|youtube-nocookie)\\.com/(?:watch\\?(?:[^#\\s]*?&)?v=|shorts/|embed/|live/|v/))" +
            "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])"
    )
    private val HTTP_LINK = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    /** The video id when [text] is a YouTube video link, else null. */
    fun youtubeId(text: String): String? {
        val t = text.trim()
        if (t.contains(' ') || (!t.contains("youtu", ignoreCase = true))) return null
        return YOUTUBE_ID.find(t)?.groupValues?.get(1)
    }

    private val YOUTUBE_PLAYLIST = Regex("^https?://(?:www\\.|m\\.|music\\.)?youtube\\.com/playlist\\?(?:[^#\\s]*?&)?list=([A-Za-z0-9_-]{10,})", RegexOption.IGNORE_CASE)

    /** The list id when [text] is a youtube.com/playlist?list=... link, else null. */
    fun youtubePlaylistId(text: String): String? = YOUTUBE_PLAYLIST.find(text.trim())?.groupValues?.get(1)

    private fun isYoutubeHost(url: String): Boolean {
        val host = try { Uri.parse(url).host.orEmpty().lowercase() } catch (_: Exception) { "" }
        return host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")
    }

    /** The stream / playlist (.pls, .m3u) link when [text] is an http(s) link that is not a YouTube link, else null. */
    fun radioUrl(text: String): String? {
        val t = text.trim()
        if (!HTTP_LINK.matches(t) || isYoutubeHost(t)) return null
        return t
    }

    /** A radio station for a pasted link; the name is the site's host name (the stream name is only known once it plays). */
    fun radioTrack(url: String): Track {
        val host = try { Uri.parse(url).host.orEmpty() } catch (_: Exception) { "" }
            .removePrefix("www.").ifBlank { url }
        return Track(
            id = 0L, title = host, artist = url, album = "", durationMs = 0L,
            source = Track.SOURCE_RADIO, remoteId = url
        )
    }
}
