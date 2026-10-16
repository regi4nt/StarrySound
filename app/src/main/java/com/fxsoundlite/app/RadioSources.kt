package com.fxsoundlite.app

import android.content.Context
import android.os.SystemClock
import android.util.Xml
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.net.HttpURLConnection
import java.io.IOException
import java.io.StringReader
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Extra radio directories next to radio-browser (see [RadioApi]): SomaFM, Icecast, Radio Garden,
 * Shoutcast and Radio Paradise. Every function returns stations as [Track]s with
 * [Track.SOURCE_RADIO] ([Track.remoteId] = stream or playlist URL, resolved at play time).
 * Blocking: call from a background thread. Failures are thrown as [ApiException].
 */
object RadioSources {

    private const val LIMIT = 30
    private val HEADERS = mapOf("User-Agent" to "StarrySound/1.0", "Accept" to "application/json")

    private fun station(name: String, info: String, url: String, icon: String = "") = Track(
        id = 0L, title = name, artist = info, album = "", durationMs = 0L,
        source = Track.SOURCE_RADIO, remoteId = url, artUrl = icon.takeIf { it.startsWith("http") }.orEmpty()
    )

    private fun get(url: String): String {
        try {
            val r = Http.request("GET", url, HEADERS)
            if (r.code !in 200..299) throw ApiException(RemoteEngine.ERR_GENERIC, "HTTP ${r.code}")
            return r.body
        } catch (e: IOException) {
            throw ApiException(RemoteEngine.ERR_NETWORK, e.message ?: "network error")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ SomaFM

    /** https://somafm.com/channels.json: ~30 channels, each with .pls playlists per format/quality. */
    fun soma(context: Context, query: String): List<Track> {
        val saver = MusicPrefs.saver(context)
        val arr = try {
            JSONObject(get("https://somafm.com/channels.json")).getJSONArray("channels")
        } catch (e: JSONException) {
            throw ApiException(RemoteEngine.ERR_GENERIC, "bad response")
        }
        val q = query.trim().lowercase(Locale.getDefault())
        val rows = ArrayList<Pair<Int, Track>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val genre = o.optString("genre").trim()
            val desc = o.optString("description").trim()
            if (title.isEmpty()) continue
            if (q.isNotEmpty() && listOf(title, genre, desc).none { it.lowercase(Locale.getDefault()).contains(q) }) continue
            val url = pickSoma(o.optJSONArray("playlists"), saver) ?: continue
            val listeners = o.optString("listeners").toIntOrNull() ?: 0
            val info = listOf("SomaFM", genre.replace('|', '/'), if (listeners > 0) "$listeners ♫" else "")
                .filter { it.isNotBlank() }.joinToString(" · ")
            rows += listeners to station(title, info, url, o.optString("image"))
        }
        return rows.sortedByDescending { it.first }.map { it.second }.take(LIMIT)
    }

    /** MP3 first (MediaPlayer's favourite); "highest" quality, or a low one in saver mode. */
    private fun pickSoma(list: org.json.JSONArray?, saver: Boolean): String? {
        if (list == null) return null
        var best: String? = null
        var bestScore = -1
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val url = p.optString("url")
            if (!url.startsWith("http")) continue
            val format = p.optString("format")
            val quality = p.optString("quality")
            var score = when (format) { "mp3" -> 10; "aac" -> 6; "aacp" -> 5; else -> 0 }
            score += when {
                saver && (quality == "low" || quality == "lowest") -> 4
                !saver && quality == "highest" -> 4
                !saver && quality == "high" -> 3
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = url }
        }
        return best
    }

    // ------------------------------------------------------------------ Radio Garden

    /** Unofficial search API of radio.garden. Needs a search word (there is no "popular" list). */
    fun garden(query: String): List<Track> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = try {
            JSONObject(get("https://radio.garden/api/search?q=${enc(q)}")).getJSONObject("hits").getJSONArray("hits")
        } catch (e: JSONException) {
            throw ApiException(RemoteEngine.ERR_GENERIC, "bad response")
        }
        val out = ArrayList<Track>()
        for (i in 0 until hits.length()) {
            val src = hits.optJSONObject(i)?.optJSONObject("_source") ?: continue
            if (src.optString("type") != "channel") continue
            val page = src.optJSONObject("page") ?: continue
            val title = page.optString("title").trim()
            val id = page.optString("url").trimEnd('/').substringAfterLast('/')
            if (title.isEmpty() || id.isBlank()) continue
            val sub = page.optString("subtitle").trim()
            out += station(
                title, listOf("Radio Garden", sub).filter { it.isNotBlank() }.joinToString(" · "),
                "https://radio.garden/api/ara/content/listen/$id/channel.mp3"
            )
            if (out.size >= LIMIT) break
        }
        return out
    }

    // ------------------------------------------------------------------ Radio Paradise

    private val PARADISE = listOf(
        Triple("Main Mix", "aac-320", "aac-128"),
        Triple("Mellow Mix", "mellow-320", "mellow-128"),
        Triple("Rock Mix", "rock-320", "rock-128"),
        Triple("Global Mix", "global-320", "global-128")
    )

    /** Four fixed, ad-free channels (320k AAC, 128k in saver mode). */
    fun paradise(context: Context, query: String): List<Track> {
        val saver = MusicPrefs.saver(context)
        val q = query.trim().lowercase(Locale.getDefault())
        return PARADISE
            .filter { q.isEmpty() || "radio paradise ${it.first}".lowercase(Locale.getDefault()).contains(q) }
            .map { (name, hi, lo) ->
                station("Radio Paradise · $name", "Radio Paradise · AAC ${if (saver) 128 else 320}k",
                    "https://stream.radioparadise.com/${if (saver) lo else hi}")
            }
    }

    // ------------------------------------------------------------------ Icecast

    /**
     * The Xiph/Icecast directory (dir.xiph.org/yp.xml). The file is huge, so it is streamed and
     * reading stops as soon as [LIMIT] matches are found (or after 25 s, returning what it has).
     */
    fun icecast(query: String): List<Track> {
        val q = query.trim().lowercase(Locale.getDefault())
        val out = ArrayList<Track>()
        val deadline = SystemClock.elapsedRealtime() + 25_000L
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://dir.xiph.org/yp.xml").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "StarrySound/1.0")
            if (conn.responseCode !in 200..299) throw ApiException(RemoteEngine.ERR_GENERIC, "HTTP ${conn.responseCode}")
            val p = Xml.newPullParser()
            p.setInput(conn.inputStream, null)
            var inEntry = false
            var tag = ""
            var name = ""; var url = ""; var type = ""; var br = ""; var genre = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && out.size < LIMIT && SystemClock.elapsedRealtime() < deadline) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        tag = p.name
                        if (tag == "entry") {
                            inEntry = true
                            name = ""; url = ""; type = ""; br = ""; genre = ""
                        }
                    }
                    XmlPullParser.TEXT -> if (inEntry) {
                        val t = p.text
                        when (tag) {
                            "server_name" -> name += t
                            "listen_url" -> url += t
                            "server_type" -> type += t
                            "bitrate" -> br += t
                            "genre" -> genre += t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "entry" && inEntry) {
                            inEntry = false
                            val n = name.trim()
                            val u = url.trim()
                            val g = genre.trim()
                            val ok = n.isNotEmpty() && u.startsWith("http") && type.trim().lowercase(Locale.US).startsWith("audio/") &&
                                (q.isEmpty() || n.lowercase(Locale.getDefault()).contains(q) || g.lowercase(Locale.getDefault()).contains(q))
                            if (ok) {
                                val bitrate = br.trim().toIntOrNull()?.takeIf { it > 0 }?.let { "${it}k" }.orEmpty()
                                out += station(n, listOf("Icecast", g, bitrate).filter { it.isNotBlank() }.joinToString(" · "), u)
                            }
                        }
                        tag = ""
                    }
                }
                ev = p.next()
            }
        } catch (e: IOException) {
            if (out.isEmpty()) throw ApiException(RemoteEngine.ERR_NETWORK, e.message ?: "network error")
        } catch (e: XmlPullParserException) {
            if (out.isEmpty()) throw ApiException(RemoteEngine.ERR_GENERIC, "bad response")
        } finally {
            conn?.disconnect()
        }
        return out
    }

    // ------------------------------------------------------------------ Shoutcast

    /** Shoutcast's directory API needs a free developer key (MusicPrefs.shoutcastKey, set from the Radio tab). */
    fun shoutcast(context: Context, query: String): List<Track> {
        val key = MusicPrefs.shoutcastKey(context)
        if (key.isBlank()) throw ApiException(RemoteEngine.ERR_YT_NO_KEY, "no Shoutcast key")
        val q = query.trim()
        val url = if (q.isEmpty()) "https://api.shoutcast.com/legacy/Top500?k=${enc(key)}&limit=$LIMIT"
        else "https://api.shoutcast.com/legacy/stationsearch?k=${enc(key)}&search=${enc(q)}&limit=$LIMIT"
        val xml = get(url)
        val out = ArrayList<Track>()
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "station") {
                    val name = p.getAttributeValue(null, "name").orEmpty().trim()
                    val id = p.getAttributeValue(null, "id").orEmpty().trim()
                    val mt = p.getAttributeValue(null, "mt").orEmpty().lowercase(Locale.US)
                    if (name.isNotEmpty() && id.isNotEmpty() && (mt.isEmpty() || mt.startsWith("audio/"))) {
                        val br = p.getAttributeValue(null, "br").orEmpty().trim().let { if (it.isNotEmpty()) "${it}k" else "" }
                        val lc = p.getAttributeValue(null, "lc").orEmpty().trim().let { if (it.isNotEmpty() && it != "0") "$it ♫" else "" }
                        val genre = p.getAttributeValue(null, "genre").orEmpty().trim()
                        out += station(
                            name, listOf("Shoutcast", genre, br, lc).filter { it.isNotBlank() }.joinToString(" · "),
                            "http://yp.shoutcast.com/sbin/tunein-station.pls?id=$id" // a .pls: resolved by RadioPlaylist
                        )
                    }
                }
                ev = p.next()
            }
        } catch (e: XmlPullParserException) {
            // A wrong key comes back as an error page / text, not a station list.
            if (out.isEmpty()) throw ApiException(RemoteEngine.ERR_GENERIC, "bad response (check the Shoutcast key)")
        }
        return out
    }
}
