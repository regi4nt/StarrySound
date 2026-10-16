package com.fxsoundlite.app

import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Online shelves of the Home page. Song charts come from Apple's public marketing-tools RSS
 * (no account, no key); a tapped song is looked up on YouTube when played. Blocking: call from a
 * background thread. Results are cached in memory for [TTL_MS].
 */
object HomeFeeds {

    data class Song(val title: String, val artist: String, val artUrl: String, val released: String)

    private const val TTL_MS = 30 * 60 * 1000L
    private class Cached(val at: Long, val songs: List<Song>)
    private val cache = HashMap<String, Cached>()

    private fun country(): String =
        Locale.getDefault().country.lowercase(Locale.US).takeIf { it.length == 2 } ?: "us"

    /** Most-played songs of a storefront (50 entries). */
    private fun chart(code: String): List<Song> {
        synchronized(cache) {
            cache[code]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.songs }
        }
        val r = Http.request(
            "GET", "https://rss.marketingtools.apple.com/api/v2/$code/music/most-played/50/songs.json",
            mapOf("User-Agent" to "StarrySound/1.0", "Accept" to "application/json")
        )
        if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
        val arr = JSONObject(r.body).getJSONObject("feed").getJSONArray("results")
        val out = ArrayList<Song>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("name").trim()
            if (title.isEmpty()) continue
            out += Song(
                title, o.optString("artistName").trim(),
                o.optString("artworkUrl100").replace("100x100bb", "400x400bb"), o.optString("releaseDate")
            )
        }
        synchronized(cache) { cache[code] = Cached(System.currentTimeMillis(), out) }
        return out
    }

    private fun chartOrFallback(code: String): List<Song> =
        try { chart(code) } catch (e: Exception) { if (code == "us") throw e else chart("us") }

    /** Top songs: 5 from the device's country chart + 5 international (a foreign chart, not already in the local picks). */
    fun topSongs(): List<Song> {
        val c = country()
        val local = chartOrFallback(c)
        val localPick = local.take(5)
        val seen = localPick.map { it.title + "|" + it.artist }.toHashSet()
        val abroad = runCatching { chart(if (c == "us") "gb" else "us") }.getOrDefault(emptyList())
        val intl = abroad.filter { (it.title + "|" + it.artist) !in local.map { l -> l.title + "|" + l.artist } }
            .filter { seen.add(it.title + "|" + it.artist) }.take(5)
        // If the international chart is unavailable, fill up with more local songs.
        val filler = local.drop(5).filter { seen.add(it.title + "|" + it.artist) }.take(10 - localPick.size - intl.size)
        return localPick + intl + filler
    }

    /** New releases: 5 recent songs of the device's country chart + 5 recent ones of a foreign chart (newest first). */
    fun newReleases(): List<Song> {
        val c = country()
        val local = chartOrFallback(c)
        val abroad = runCatching { chart(if (c == "us") "gb" else "us") }.getOrDefault(emptyList())
        val localKeys = local.map { it.title + "|" + it.artist }.toHashSet()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cutoff = System.currentTimeMillis() - 120L * 24 * 3600 * 1000
        // Newest first; the ones from the last 120 days take precedence, the rest only fills up.
        fun newest(list: List<Song>): List<Song> {
            val sorted = list.filter { it.released.isNotBlank() }.sortedByDescending { it.released }
            val recent = sorted.filter { (runCatching { fmt.parse(it.released)?.time }.getOrNull() ?: 0L) >= cutoff }
            return recent + sorted.filter { it !in recent }
        }
        val l = newest(local)
        val i = newest(abroad.filter { (it.title + "|" + it.artist) !in localKeys })
        val seen = HashSet<String>()
        val out = ArrayList<Song>()
        out += l.take(5).filter { seen.add(it.title + "|" + it.artist) }
        out += i.asSequence().filter { seen.add(it.title + "|" + it.artist) }.take(5).toList()
        (l + i).forEach { if (out.size < 10 && seen.add(it.title + "|" + it.artist)) out += it }
        return out
    }
}
