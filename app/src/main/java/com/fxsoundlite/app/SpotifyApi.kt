package com.fxsoundlite.app

import android.content.Context
import org.json.JSONObject

/**
 * Read-only Spotify Web API calls (playlists and liked songs), written for the February 2026 API:
 * playlist songs come from /playlists/{id}/items (field "item", older "track" is accepted too) and Spotify only
 * returns the songs of the user's OWN playlists (followed ones answer 403 and are skipped by the importer).
 */
object SpotifyApi {
    private const val BASE = "https://api.spotify.com/v1"
    private const val MAX_PAGES_ITEMS = 1000

    class ApiException(val code: Int, message: String) : Exception(message)
    class Pl(val id: String, val name: String, val total: Int)
    class Hit(val uri: String, val title: String, val artist: String, val durationMs: Long, val artUrl: String)
    class Song(val title: String, val artist: String, val allArtists: String, val durationMs: Long, val artUrl: String)

    /** Blocking. Retries once after a 401 (refreshes the token) and a few times after 429 (rate limit). */
    private fun call(c: Context, method: String, url: String, body: String? = null): Http.Response {
        var refreshed = false
        for (attempt in 0..3) {
            val token = SpotifyAuth.accessToken(c)
            val r = Http.request(method, url, mapOf("Authorization" to "Bearer $token"), body, if (body != null) "application/json" else null)
            when {
                r.code == 429 -> Thread.sleep(2000L * (attempt + 1))
                r.code == 401 && !refreshed -> { refreshed = true; SpotifyAuth.invalidate(c) }
                else -> return r
            }
        }
        throw ApiException(429, "rate limited")
    }

    fun put(c: Context, path: String, json: String): Http.Response = call(c, "PUT", BASE + path, json)

    private fun get(c: Context, url: String): JSONObject {
        val r = call(c, "GET", url)
        if (r.code !in 200..299) {
            val msg = try { JSONObject(r.body).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
            throw ApiException(r.code, (msg.ifBlank { "HTTP ${r.code}" }) + " [" + url.removePrefix(BASE).substringBefore("?") + "]")
        }
        return JSONObject(r.body)
    }

    private fun pages(c: Context, first: String, maxItems: Int, each: (JSONObject) -> Unit) {
        var url: String? = first
        var n = 0
        while (url != null && n < maxItems) {
            val o = get(c, url)
            val arr = o.optJSONArray("items") ?: break
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(each)
            n += arr.length()
            url = if (o.isNull("next")) null else o.optString("next").takeIf { it.isNotBlank() }
        }
    }

    fun playlists(c: Context): List<Pl> {
        val out = ArrayList<Pl>()
        pages(c, "$BASE/me/playlists?limit=50", 500) {
            val id = it.optString("id")
            if (id.isBlank()) return@pages
            val total = it.optJSONObject("items")?.optInt("total", -1)
                ?: it.optJSONObject("tracks")?.optInt("total", -1) ?: -1
            out += Pl(id, it.optString("name").ifBlank { "Spotify" }, total)
        }
        return out
    }

    fun playlistSongs(c: Context, id: String): List<Song> {
        val out = ArrayList<Song>()
        pages(c, "$BASE/playlists/$id/items?limit=50", MAX_PAGES_ITEMS) {
            parse(it.optJSONObject("item") ?: it.optJSONObject("track"))?.let(out::add)
        }
        return out
    }

    fun likedSongs(c: Context): List<Song> {
        val out = ArrayList<Song>()
        pages(c, "$BASE/me/tracks?limit=50", MAX_PAGES_ITEMS) { parse(it.optJSONObject("track"))?.let(out::add) }
        return out
    }

    // ---- player (Premium; Spotify must have an active device) ----

    fun post(c: Context, path: String): Http.Response = call(c, "POST", BASE + path, "{}")

    private fun failure(r: Http.Response, path: String): ApiException {
        val msg = try { JSONObject(r.body).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
        return ApiException(r.code, (msg.ifBlank { "HTTP ${r.code}" }) + " [" + path + "]")
    }

    /** What Spotify is playing right now; null when no device is active (Spotify answers 204). */
    fun player(c: Context): JSONObject? {
        val r = call(c, "GET", "$BASE/me/player")
        if (r.code == 204 || r.body.isBlank()) return null
        if (r.code !in 200..299) throw failure(r, "/me/player")
        return JSONObject(r.body)
    }

    /** Song search. Development-mode apps get at most 10 results per request since February 2026. */
    fun search(c: Context, query: String): List<Hit> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val o = get(c, "$BASE/search?q=$q&type=track&limit=10")
        val arr = o.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { hit(arr.optJSONObject(it)) }
    }

    /** The songs waiting in the Spotify queue. */
    fun queue(c: Context): List<Hit> {
        val arr = get(c, "$BASE/me/player/queue").optJSONArray("queue") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { hit(arr.optJSONObject(it)) }
    }

    fun hit(t: JSONObject?): Hit? {
        if (t == null || t.optString("type") != "track") return null
        val uri = t.optString("uri")
        val title = t.optString("name")
        if (uri.isBlank() || title.isBlank()) return null
        val artists = t.optJSONArray("artists")
        val names = (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        val art = t.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
        return Hit(uri, title, names.joinToString(", "), t.optLong("duration_ms", 0L), art)
    }

    private fun parse(t: JSONObject?): Song? {
        if (t == null || t.optString("type") != "track") return null // episodes, removed songs
        val title = t.optString("name")
        if (title.isBlank()) return null
        val artists = t.optJSONArray("artists")
        val names = (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        val art = t.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
        return Song(title, names.firstOrNull().orEmpty(), names.joinToString(", "), t.optLong("duration_ms", 0L), art)
    }
}
