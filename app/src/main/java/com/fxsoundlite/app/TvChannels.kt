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

    /** True when [q] (what was typed in the search box) is a link, not a channel name. */
    fun isLink(q: String): Boolean = (q.startsWith("http://") || q.startsWith("https://")) && !q.any { it.isWhitespace() } && q.length > 12

    /**
     * A link pasted in the search box: a playlist gives its channels (the second value is true), anything else
     * (a single stream such as a .m3u8) is one channel of its own. Throws when the link cannot be read.
     */
    fun loadLink(c: Context, url: String): Pair<List<Track>, Boolean> {
        val head = readLinkHead(url) ?: throw java.io.IOException("link unreachable")
        // Only a real M3U playlist is read completely; a direct stream (.m3u8 media playlist, .ts, Icecast ...) is never
        // downloaded: a live stream has no end, reading it to the end would hang and fill the memory.
        val channels = if (head.contains("#EXTM3U") || head.contains("#EXTINF")) parse(readLinkFully(url) ?: head) else emptyList()
        if (channels.isNotEmpty()) return channels.distinctBy { it.remoteId } to true
        // No named channels: a media playlist / direct stream. Play the link itself.
        val name = url.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.').ifBlank { "TV" }
        return listOf(
            Track(id = 0L, title = name, artist = "", album = "", durationMs = 0L, source = Track.SOURCE_TV, remoteId = url, artUrl = "")
        ) to false
    }

    /** Opens [url] (follows redirects, also http <-> https) and hands the stream to [block]; null when it cannot be reached. */
    private fun <T> openLink(url: String, block: (java.io.InputStream) -> T): T? {
        var target = url
        for (hop in 0..5) {
            val conn = java.net.URL(target).openConnection() as java.net.HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    target = java.net.URL(java.net.URL(target), loc).toString()
                    continue
                }
                if (code !in 200..299) return null
                return conn.inputStream.use(block)
            } catch (e: java.io.IOException) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /** The first bytes of [url] as text: enough to tell a playlist from a stream. */
    private fun readLinkHead(url: String): String? = openLink(url) { s ->
        val buf = ByteArray(4096)
        var n = 0
        while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        String(buf, 0, n, Charsets.UTF_8)
    }

    /** A playlist read completely (at most 16 MB). */
    private fun readLinkFully(url: String): String? = openLink(url) { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < 16 * 1024 * 1024) { val r = s.read(buf); if (r < 0) break; out.write(buf, 0, r) }
        out.toString("UTF-8")
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
                // The name follows the first comma after the last attribute (a name or a group may hold commas itself).
                val from = attr.findAll(line).lastOrNull()?.range?.last?.plus(1) ?: 0
                val comma = line.indexOf(',', from)
                name = (if (comma >= 0) line.substring(comma + 1) else "").trim().ifEmpty { attrs["tvg-name"].orEmpty() }
            } else if (!line.startsWith("#")) {
                if ((line.startsWith("http://") || line.startsWith("https://")) && name.isNotEmpty()) {
                    out += Track(
                        id = 0L, title = name, artist = group.substringBefore(';'), album = "", durationMs = 0L,
                        // Kodi-style "url|User-Agent=..." headers are not part of the address
                        source = Track.SOURCE_TV, remoteId = line.substringBefore('|').trim(), artUrl = logo
                    )
                }
                name = ""; logo = ""; group = ""
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Home shelves (recommended / popular TV)

    /** Well-known free-to-air channels per country, best first (matched by name inside the IPTV-org playlist). */
    private val localFavourites = mapOf(
        "id" to listOf("RCTI", "SCTV", "Indosiar", "Trans TV", "Trans7", "Kompas TV", "Metro TV", "tvOne", "MNCTV", "TVRI", "NET", "CNN Indonesia", "GTV", "RTV", "iNews", "Moji"),
        "my" to listOf("TV3", "Astro Awani", "NTV7", "8TV", "TV9", "RTM", "Bernama"),
        "sg" to listOf("CNA", "Channel 5", "Channel 8", "Channel U", "Suria", "Vasantham"),
        "th" to listOf("Channel 3", "Channel 7", "Thai PBS", "PPTV", "Workpoint", "One31", "Amarin"),
        "ph" to listOf("GMA", "ABS-CBN", "TV5", "PTV", "A2Z", "Kapamilya"),
        "gb" to listOf("BBC One", "BBC News", "ITV", "Channel 4", "Sky News", "BBC Two", "GB News"),
        "us" to listOf("NBC", "ABC", "CBS", "PBS", "Fox", "CNN", "NASA", "Bloomberg"),
        "au" to listOf("ABC", "SBS", "Nine", "Seven", "Ten", "Sky News"),
        "in" to listOf("Aaj Tak", "NDTV", "India Today", "ABP", "DD National", "Zee", "Republic"),
        "jp" to listOf("NHK", "TBS", "Fuji", "TV Asahi", "Nippon", "TV Tokyo"),
        "kr" to listOf("KBS", "MBC", "SBS", "Arirang", "YTN", "JTBC"),
        "de" to listOf("DW", "ARD", "ZDF", "RTL", "Sat.1", "ProSieben", "Tagesschau"),
        "fr" to listOf("France 24", "TF1", "France 2", "France 3", "M6", "BFM", "LCI"),
        "es" to listOf("La 1", "Antena 3", "Telecinco", "La Sexta", "RTVE", "24h"),
        "br" to listOf("Globo", "Record", "SBT", "Band", "TV Brasil", "CNN Brasil"),
        "tr" to listOf("TRT", "ATV", "Show TV", "Kanal D", "Star TV", "NTV", "Haber"),
        "sa" to listOf("Al Arabiya", "Saudi", "SBC", "Al Ekhbariya", "MBC")
    )

    /** International channels, best first (name inside the IPTV-org playlists listed in [intlSources]). */
    private val intlFavourites = listOf(
        "Al Jazeera English", "DW English", "France 24 English", "NASA", "Bloomberg", "Sky News", "Euronews English",
        "CNA", "CGTN", "BBC News", "Arirang", "NHK World", "Red Bull TV", "WION", "TRT World", "ABC News Live"
    )
    private val intlSources = listOf("qa", "de", "fr", "us", "gb", "sg", "cn", "kr", "jp", "in", "tr", "au")

    private fun homeCountry(c: Context): String {
        val dev = DeviceCountry.codeOrNull(c)
        return if (dev != null && dev in countries) dev else country(c).takeIf { it != NO_COUNTRY } ?: "id"
    }

    private fun loadCode(app: Context, code: String): List<Track> =
        try { parse(fetch(app, "https://iptv-org.github.io/iptv/countries/$code.m3u", false)) } catch (e: Exception) { emptyList() }

    private fun byName(pool: List<Track>, names: List<String>): List<Track> {
        val out = ArrayList<Track>()
        for (n in names) {
            // whole-word-ish match so "NET" does not hit "Planet"
            val rx = Regex("(^|[^A-Za-z0-9])" + Regex.escape(n) + "([^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE)
            pool.firstOrNull { rx.containsMatchIn(it.title) && out.none { o -> o.remoteId == it.remoteId } }?.let { out += it }
        }
        return out
    }

    /** Local channels ranked: the well-known ones first, then News / General / Entertainment, then the rest. */
    private fun rankedLocal(app: Context, code: String): List<Track> {
        val pool = loadCode(app, code).distinctBy { it.remoteId }
        val fav = byName(pool, localFavourites[code].orEmpty())
        val rest = pool.filter { t -> fav.none { it.remoteId == t.remoteId } }
        val good = rest.filter { it.artist.contains("News", true) || it.artist.contains("General", true) || it.artist.contains("Entertainment", true) }
        return (fav + good + rest).distinctBy { it.remoteId }
    }

    private fun rankedIntl(app: Context, homeCode: String): List<Track> {
        val pool = ArrayList<Track>()
        for (code in intlSources) pool += loadCode(app, code)
        // Channels of the device's own country are "local", not international.
        val own = loadCode(app, homeCode).map { it.remoteId }.toSet()
        return byName(pool.filter { it.remoteId !in own }, intlFavourites)
    }

    /** Home "Popular TV": 5 well-known channels of the device's country + 5 international ones. */
    fun popularMixed(c: Context): List<Track> {
        val app = c.applicationContext
        val local = rankedLocal(app, homeCountry(app))
        val intl = rankedIntl(app, homeCountry(app))
        if (local.isEmpty() && intl.isEmpty()) throw java.io.IOException("tv playlists unreachable")
        val out = ArrayList<Track>()
        out += local.take(5)
        out += intl.take(5)
        (local.drop(5) + intl.drop(5)).forEach { if (out.size < 10 && out.none { o -> o.remoteId == it.remoteId }) out += it }
        return out
    }

    /** Home "Recommended TV": the next 5 local and next 5 international channels after the popular ones, interleaved. */
    fun recommended(c: Context): List<Track> {
        val app = c.applicationContext
        val code = homeCountry(app)
        val local = rankedLocal(app, code).drop(5)
        val intl = rankedIntl(app, code).drop(5)
        if (local.isEmpty() && intl.isEmpty()) throw java.io.IOException("tv playlists unreachable")
        val out = ArrayList<Track>()
        var i = 0
        while (out.size < 10 && (i < local.size || i < intl.size)) {
            if (i < intl.size) out += intl[i]
            if (i < local.size && out.size < 10) out += local[i]
            i++
        }
        return out.take(10)
    }
}
