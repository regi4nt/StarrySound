package com.fxsoundlite.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a PUBLIC Spotify playlist from its link without any account, Client ID or Premium: the page that Spotify
 * serves for its embedded player (open.spotify.com/embed/playlist/ID) carries the song list as JSON. It is not an
 * official API, so Spotify may change it, and it only lists the first ~100 songs of the playlist.
 */
object SpotifyEmbed {
    class Playlist(val name: String, val songs: List<SpotifyApi.Song>)

    private val ID = Regex("""(?:playlist/|playlist:)([A-Za-z0-9]{10,30})""")

    fun idFrom(link: String): String? = ID.find(link.trim())?.groupValues?.get(1)

    /** Blocking. Throws [ApiException] when the link is wrong, the playlist is private, or the page changed. */
    fun fetch(link: String): Playlist {
        val id = idFrom(link) ?: throw ApiException(400, "not a Spotify playlist link")
        val r = Http.request("GET", "https://open.spotify.com/embed/playlist/$id", mapOf("User-Agent" to "Mozilla/5.0"), null, null)
        if (r.code !in 200..299) throw ApiException(r.code, "HTTP ${r.code} (private playlist?)")
        val m = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(r.body)
            ?: throw ApiException(0, "playlist page not readable")
        val entity = findEntity(JSONObject(m.groupValues[1])) ?: throw ApiException(0, "playlist data not found")
        val list = entity.optJSONArray("trackList") ?: throw ApiException(0, "no songs in the page")
        val songs = ArrayList<SpotifyApi.Song>()
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val title = t.optString("title")
            if (title.isBlank()) continue
            val artists = t.optString("subtitle").replace('\u00a0', ' ').trim()
            songs += SpotifyApi.Song(title, artists.substringBefore(",").trim(), artists, t.optLong("duration", 0L), "")
        }
        if (songs.isEmpty()) throw ApiException(0, "no songs in the page")
        return Playlist(entity.optString("name").ifBlank { "Spotify" }, songs)
    }

    /** The object that holds "trackList", wherever the page nests it. */
    private fun findEntity(n: Any?): JSONObject? {
        when (n) {
            is JSONObject -> {
                if (n.has("trackList")) return n
                for (k in n.keys()) findEntity(n.opt(k))?.let { return it }
            }
            is JSONArray -> for (i in 0 until n.length()) findEntity(n.opt(i))?.let { return it }
        }
        return null
    }
}
