package com.fxsoundlite.app

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The user's own YouTube playlists, read through the YouTube Data API with the Google account signed in
 * under Settings > Google account. Needs the read-only YouTube scope, asked for the first time the
 * Playlist page is opened: [GoogleAuthUtil] throws UserRecoverableAuthException with the consent screen's
 * intent, which the caller launches. Nothing in the YouTube account is changed.
 *
 * Playlists are read live from the account (not copied into [PlaylistStore]); their songs are plain
 * YouTube [Track]s and play through the normal YouTube engine.
 */
object YouTubeAccount {
    const val SCOPE_YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"
    /** Needed only to put a local playlist into the account (Playlist page > Convert). */
    const val SCOPE_YOUTUBE = "https://www.googleapis.com/auth/youtube"

    /** Playlist ids of the account are shown in the Playlist page with this prefix. */
    const val ID_PREFIX = "yt:"

    private const val MAX_PLAYLISTS = 200
    private const val MAX_TRACKS = 500

    class Pl(val id: String, val title: String, val count: Int, val thumb: String)

    class HttpException(val code: Int, message: String) : IOException(message)

    private val cachedTokens = HashMap<String, String>()

    fun isSignedIn(c: Context): Boolean = DriveSync.isSignedIn(c)

    fun isYtId(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)

    // ------------------------------------------------------------------ token + http

    /** May throw UserRecoverableAuthException (consent needed): the caller shows [e.intent]. */
    @Synchronized
    private fun token(c: Context, refresh: Boolean, scope: String): String {
        val account = GoogleSignIn.getLastSignedInAccount(c)?.account ?: throw DriveMusic.NotSignedInException()
        if (refresh) cachedTokens[scope]?.let { try { GoogleAuthUtil.clearToken(c, it) } catch (_: Exception) { } }
        val t = GoogleAuthUtil.getToken(c, account, "oauth2:$scope")
        cachedTokens[scope] = t
        return t
    }

    /** Asks for the write scope; throws UserRecoverableAuthException when the user still has to allow it. */
    fun ensureWriteAccess(c: Context) { token(c.applicationContext, false, SCOPE_YOUTUBE) }

    private fun open(c: Context, url: String, refresh: Boolean, method: String, scope: String, hasBody: Boolean): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer ${token(c, refresh, scope)}")
        if (hasBody) {
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
        }
        return conn
    }

    /** GET returning the JSON body; one retry on 401 (stale token). */
    private fun getJson(c: Context, url: String): JSONObject = requestJson(c, "GET", url, null, SCOPE_YOUTUBE_READONLY)

    private fun requestJson(c: Context, method: String, url: String, payload: JSONObject?, scope: String): JSONObject {
        val bytes = payload?.toString()?.toByteArray(Charsets.UTF_8)
        fun start(refresh: Boolean): HttpURLConnection {
            val cn = open(c, url, refresh, method, scope, bytes != null)
            if (bytes != null) cn.outputStream.use { it.write(bytes) }
            return cn
        }
        var conn = start(false)
        try {
            if (conn.responseCode == 401) {
                conn.disconnect()
                conn = start(true)
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
                throw HttpException(code, "HTTP $code" + if (msg.isBlank()) "" else ": " + msg.take(140))
            }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ playlists

    /** The account's own and saved playlists. Blocking: call off the main thread. */
    fun listPlaylists(c: Context): List<Pl> {
        val app = c.applicationContext
        val out = ArrayList<Pl>()
        var page: String? = null
        do {
            val url = "https://www.googleapis.com/youtube/v3/playlists?part=snippet,contentDetails&mine=true&maxResults=50" +
                (page?.let { "&pageToken=" + enc(it) } ?: "")
            val json = getJson(app, url)
            val items = json.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                val sn = o.optJSONObject("snippet")
                out += Pl(
                    id = id,
                    title = sn?.optString("title").orEmpty().ifBlank { id },
                    count = o.optJSONObject("contentDetails")?.optInt("itemCount") ?: 0,
                    thumb = thumbOf(sn)
                )
            }
            page = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null && out.size < MAX_PLAYLISTS)
        return out
    }

    private fun thumbOf(snippet: JSONObject?): String {
        val th = snippet?.optJSONObject("thumbnails") ?: return ""
        for (k in arrayOf("medium", "default", "high")) {
            val u = th.optJSONObject(k)?.optString("url").orEmpty()
            if (u.isNotBlank()) return u
        }
        return ""
    }

    /** The songs of one playlist ([playlistId] without [ID_PREFIX]). Blocking: call off the main thread. */
    fun listTracks(c: Context, playlistId: String): List<Track> {
        val app = c.applicationContext
        val out = ArrayList<Track>()
        var page: String? = null
        do {
            val url = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50" +
                "&playlistId=" + enc(playlistId) + (page?.let { "&pageToken=" + enc(it) } ?: "")
            val json = getJson(app, url)
            val items = json.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val sn = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                val vid = sn.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                val title = sn.optString("title")
                if (vid.isBlank() || title == "Deleted video" || title == "Private video") continue
                val artist = sn.optString("videoOwnerChannelTitle").removeSuffix(" - Topic")
                out += Track(
                    id = 0L, title = title, artist = artist, album = "", durationMs = 0L,
                    source = Track.SOURCE_YOUTUBE, remoteId = vid, artUrl = thumbOf(sn)
                )
            }
            page = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null && out.size < MAX_TRACKS)
        return out
    }

    // ------------------------------------------------------------------ taste signals (read-only)

    private const val MAX_LIKE_PAGES = 4   // 200 liked videos
    private const val MAX_SUB_PAGES = 4    // 200 subscriptions

    /**
     * Artists of the music videos the account has liked, with how many of them each has. Only videos in the
     * YouTube "Music" category (id 10) count; the artist is the channel name ("Artist - Topic" -> "Artist").
     * Blocking: call off the main thread.
     */
    fun likedMusicArtists(c: Context): Map<String, Int> {
        val app = c.applicationContext
        val out = LinkedHashMap<String, Int>()
        var page: String? = null
        var pages = 0
        do {
            val url = "https://www.googleapis.com/youtube/v3/videos?part=snippet&myRating=like&maxResults=50" +
                (page?.let { "&pageToken=" + enc(it) } ?: "")
            val json = getJson(app, url)
            val items = json.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val sn = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                if (sn.optString("categoryId") != "10") continue
                val artist = sn.optString("channelTitle").removeSuffix(" - Topic").trim()
                if (artist.isNotBlank()) out[artist] = (out[artist] ?: 0) + 1
            }
            page = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null && ++pages < MAX_LIKE_PAGES)
        return out
    }

    /**
     * Names of the subscribed channels that are about music (so a gaming or cooking channel never ends up
     * as an "artist"). A channel counts when its topic categories mention music, or, as a fallback, when it
     * is an auto-generated "- Topic" / VEVO channel. Blocking: call off the main thread.
     */
    fun musicSubscriptions(c: Context): List<String> {
        val app = c.applicationContext
        val subs = ArrayList<Pair<String, String>>() // channelId to title
        var page: String? = null
        var pages = 0
        do {
            val url = "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=50&order=relevance" +
                (page?.let { "&pageToken=" + enc(it) } ?: "")
            val json = getJson(app, url)
            val items = json.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val sn = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                val id = sn.optJSONObject("resourceId")?.optString("channelId").orEmpty()
                val title = sn.optString("title")
                if (id.isNotBlank() && title.isNotBlank()) subs += id to title
            }
            page = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null && ++pages < MAX_SUB_PAGES)

        val musicIds = HashSet<String>()
        for (chunk in subs.chunked(50)) {
            val ids = chunk.joinToString(",") { it.first }
            val json = getJson(app, "https://www.googleapis.com/youtube/v3/channels?part=topicDetails&maxResults=50&id=" + enc(ids))
            val items = json.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val cats = o.optJSONObject("topicDetails")?.optJSONArray("topicCategories") ?: continue
                for (k in 0 until cats.length()) {
                    if (cats.optString(k).contains("music", ignoreCase = true)) { musicIds += o.optString("id"); break }
                }
            }
        }
        val topicLike = Regex("(\\s-\\sTopic|VEVO)$", RegexOption.IGNORE_CASE)
        return subs.filter { (id, title) -> id in musicIds || topicLike.containsMatchIn(title) }
            .map { (_, title) -> title.removeSuffix(" - Topic").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    // ------------------------------------------------------------------ writing (Convert)

    /** Creates a private playlist in the account and returns its id. */
    fun createPlaylist(c: Context, title: String): String {
        val body = JSONObject()
            .put("snippet", JSONObject().put("title", title.take(150)))
            .put("status", JSONObject().put("privacyStatus", "private"))
        return requestJson(c.applicationContext, "POST",
            "https://www.googleapis.com/youtube/v3/playlists?part=snippet,status", body, SCOPE_YOUTUBE).optString("id")
    }

    /** Appends one video to a playlist of the account. */
    fun addVideo(c: Context, playlistId: String, videoId: String) {
        val body = JSONObject().put("snippet", JSONObject()
            .put("playlistId", playlistId)
            .put("resourceId", JSONObject().put("kind", "youtube#video").put("videoId", videoId)))
        requestJson(c.applicationContext, "POST",
            "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet", body, SCOPE_YOUTUBE)
    }
}
