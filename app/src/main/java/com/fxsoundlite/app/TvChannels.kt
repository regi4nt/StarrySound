package com.fxsoundlite.app

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Live TV channels of the TV tab (video mode). Two sources, used together:
 *  - IPTV-org: the public free-to-air playlist of one country (https://iptv-org.github.io/iptv/countries/xx.m3u);
 *  - the user's own M3U / M3U8 playlist links.
 * A channel is a [Track] with [Track.SOURCE_TV]; [Track.remoteId] is the stream URL, [Track.artUrl] the logo.
 * Blocking network code: call from a background thread.
 */
object TvChannels {
    private const val PREFS = "tv_channels"
    private const val KEY_COUNTRY = "country"
    private const val KEY_CUSTOM = "custom_urls"
    private const val CACHE_MS = 12 * 60 * 60 * 1000L
    /** "" = no IPTV-org country, only the user's own links. */
    const val NO_COUNTRY = "-"

    /** ISO codes IPTV-org has playlists for (the most common ones). */
    val countries = listOf(
        "id", "my", "sg", "th", "ph", "vn", "in", "jp", "kr", "cn", "hk", "tw", "pk", "bd", "lk",
        "au", "nz", "us", "ca", "gb", "ie", "de", "fr", "es", "pt", "it", "nl", "be", "ch", "at",
        "se", "no", "dk", "fi", "pl", "ru", "ua", "gr", "tr", "sa", "ae", "qa", "eg", "il", "ir",
        "br", "mx", "ar", "cl", "co", "pe", "za", "ng", "ke", "ma", "dz"
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun country(c: Context): String {
        prefs(c).getString(KEY_COUNTRY, null)?.let { return it }
        val dev = Locale.getDefault().country.lowercase(Locale.ROOT)
        return if (dev in countries) dev else "id"
    }

    fun setCountry(c: Context, code: String) {
        prefs(c).edit().putString(KEY_COUNTRY, code).apply()
    }

    fun countryName(code: String): String =
        Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale.getDefault()).ifBlank { code.uppercase(Locale.ROOT) }

    fun customUrls(c: Context): List<String> =
        prefs(c).getString(KEY_CUSTOM, "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun saveCustom(c: Context, list: List<String>) {
        prefs(c).edit().putString(KEY_CUSTOM, list.joinToString("\n")).apply()
    }

    fun addCustom(c: Context, url: String): Boolean {
        val u = url.trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false
        saveCustom(c, (customUrls(c) + u).distinct())
        return true
    }

    fun removeCustom(c: Context, urls: Collection<String>) {
        saveCustom(c, customUrls(c).filter { it !in urls })
    }

    /** The channels: the user's own playlists first, then the IPTV-org country. Throws when nothing could be loaded. */
    fun load(c: Context, force: Boolean): List<Track> {
        val app = c.applicationContext
        val out = ArrayList<Track>()
        var failures = 0
        var sources = 0
        for (url in customUrls(app)) {
            sources++
            val text = try { fetch(app, url, force) } catch (e: Exception) { failures++; continue }
            out += parse(text)
        }
        val code = country(app)
        if (code != NO_COUNTRY) {
            sources++
            val url = "https://iptv-org.github.io/iptv/countries/$code.m3u"
            try { out += parse(fetch(app, url, force)) } catch (e: Exception) { failures++ }
        }
        if (sources > 0 && failures == sources) throw java.io.IOException("tv playlists unreachable")
        // Same stream twice (own link + IPTV-org): keep the first.
        return out.distinctBy { it.remoteId }
    }

    private fun fetch(app: Context, url: String, force: Boolean): String {
        val f = File(app.cacheDir, "tv_" + Integer.toHexString(url.hashCode()) + ".m3u")
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MS) {
            runCatching { return f.readText() }
        }
        return try {
            val r = Http.request("GET", url, readTimeoutMs = 30_000)
            if (r.code !in 200..299 || r.body.isBlank()) throw java.io.IOException("HTTP ${r.code}")
            runCatching { f.writeText(r.body) }
            r.body
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw e // offline: an older copy is better than nothing
        }
    }

    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** Reads an M3U: "#EXTINF:-1 tvg-logo=".." group-title="..",Name" followed by the stream URL. */
    fun parse(text: String): List<Track> {
        val out = ArrayList<Track>()
        var name = ""
        var logo = ""
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                logo = attrs["tvg-logo"].orEmpty()
                group = attrs["group-title"].orEmpty()
                name = line.substringAfterLast(',', "").trim().ifEmpty { attrs["tvg-name"].orEmpty() }
            } else if (!line.startsWith("#")) {
                if ((line.startsWith("http://") || line.startsWith("https://")) && name.isNotEmpty()) {
                    out += Track(
                        id = 0L, title = name, artist = group.substringBefore(';'), album = "", durationMs = 0L,
                        source = Track.SOURCE_TV, remoteId = line, artUrl = logo
                    )
                }
                name = ""; logo = ""; group = ""
            }
        }
        return out
    }
}
