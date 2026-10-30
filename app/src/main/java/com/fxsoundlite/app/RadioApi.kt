package com.fxsoundlite.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.util.Locale

/**
 * Internet radio directory: https://www.radio-browser.info (free, no API key). Stations come back
 * as [Track]s with [Track.SOURCE_RADIO]; [Track.remoteId] holds the stream URL and [Track.durationMs]
 * is 0 (live). Blocking: call from a background thread.
 */
object RadioApi {

    // The API runs on several mirrors. They are asked in a staggered race (see fetch), so a dead or
    // slow one no longer costs its whole connect timeout. The list is refreshed from the official
    // server list in the background (refreshHosts); these are the fallback.
    private val STATIC_HOSTS = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info"
    )
    @Volatile private var discoveredHosts: List<String>? = null
    @Volatile private var discoveredAt = 0L
    @Volatile private var refreshing = false
    const val SRC_BROWSER = 0   // radio-browser.info (default)
    const val SRC_SOMA = 1
    const val SRC_ICECAST = 2
    const val SRC_GARDEN = 3
    const val SRC_SHOUTCAST = 4
    const val SRC_PARADISE = 5
    const val SRC_COLLECTION = 6 // hand-picked stations from the StarryNight MPlayer web app

    /** Directories that cannot list anything without a search word. */
    fun needsQuery(source: Int) = source == SRC_GARDEN

    private const val LIMIT = 30
    /** searchAll stops waiting this long after the start once some directory has answered... */
    private const val SOFT_WAIT_MS = 3_000L
    /** ...but never before radio-browser (the main directory) has answered, and gives up on the slow ones after this. */
    private const val HARD_WAIT_MS = 12_000L
    /** A next mirror is started when the previous ones have not answered within this time. */
    private const val STAGGER_MS = 1_200L
    /** Whole radio-browser request (all mirrors together). */
    private const val FETCH_DEADLINE_MS = 11_000L
    private const val HOSTS_TTL_MS = 6 * 60 * 60 * 1000L
    /** The mirror that answered last: tried first next time. */
    @Volatile private var preferredHost = ""
    private const val LIMIT_SAVER = 15

    /** Most-listened stations of the device's country (all countries if it can't be told). */
    fun popular(context: Context, source: Int = SRC_BROWSER): List<Track> = load(context, "", source)

    fun search(context: Context, query: String, source: Int = SRC_BROWSER): List<Track> {
        // A pasted stream link plays as a station of its own.
        LinkTracks.radioUrl(query)?.let { return RadioOrigin.tag(context, listOf(LinkTracks.radioTrack(it)), RadioOrigin.LINK) }
        return load(context, query.trim(), source)
    }

    /** Most-listened stations of one country (2-letter code; "" = worldwide): the Radio tab's first list. */
    fun popularCountry(context: Context, country: String): List<Track> =
        RadioOrigin.tag(context, fetch(context, "", "clickcount", country), SRC_BROWSER)

    /** Popular stations from every directory at once (same merge as [searchAll] with no query). */
    fun popularAll(context: Context): List<Track> = searchAll(context, "")

    /** Searches every directory at once (no source choice) and merges the answers, interleaved so
     *  each directory is represented, duplicates (same stream URL) dropped. Shoutcast joins only
     *  when a key is saved. Fails only when every directory fails. */
    fun searchAll(context: Context, query: String, onPartial: ((List<Track>) -> Unit)? = null): List<Track> {
        val q = query.trim()
        LinkTracks.radioUrl(q)?.let { return RadioOrigin.tag(context, listOf(LinkTracks.radioTrack(it)), RadioOrigin.LINK) }
        val sources = mutableListOf(SRC_BROWSER, SRC_GARDEN, SRC_COLLECTION, SRC_SOMA, SRC_ICECAST, SRC_PARADISE)
        if (MusicPrefs.shoutcastKey(context).isNotBlank()) sources.add(SRC_SHOUTCAST)
        val results = arrayOfNulls<Result<List<Track>>>(sources.size)
        // Every directory is asked at once, but one slow directory (Icecast reads a huge file) must not
        // hold the others back: after SOFT_WAIT_MS the answers that are in are shown.
        val pool = Executors.newFixedThreadPool(sources.size)
        try {
            val cs = ExecutorCompletionService<Pair<Int, Result<List<Track>>>>(pool)
            sources.forEachIndexed { i, src -> cs.submit { i to runCatching { load(context, q, src) } } }
            val start = SystemClock.elapsedRealtime()
            val browserIdx = sources.indexOf(SRC_BROWSER)
            // Stations bundled in the app / built in answer instantly and must not start the short wait.
            val localIdx = sources.indices.filter { sources[it] == SRC_COLLECTION || sources[it] == SRC_PARADISE }.toSet()
            var done = 0
            while (done < sources.size) {
                val elapsed = SystemClock.elapsedRealtime() - start
                val haveNetwork = results.indices.any { it !in localIdx && results[it]?.getOrNull()?.isNotEmpty() == true }
                val browserDone = browserIdx < 0 || results[browserIdx] != null
                // Short wait only once a network directory answered AND radio-browser has answered or failed.
                val limit = if (haveNetwork && browserDone) SOFT_WAIT_MS else HARD_WAIT_MS
                val left = limit - elapsed
                if (left <= 0) break
                val (i, r) = (cs.poll(left, TimeUnit.MILLISECONDS) ?: break).get()
                results[i] = r
                done++
                // Show what is in already (bundled / fast directories first) instead of waiting for the slowest one.
                if (onPartial != null && r.getOrNull()?.isNotEmpty() == true) {
                    val part = mergeInterleaved(results.mapNotNull { it?.getOrNull() })
                    if (part.isNotEmpty()) onPartial(part)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        val lists = results.mapNotNull { it?.getOrNull() }
        if (lists.isEmpty()) throw results.firstNotNullOfOrNull { it?.exceptionOrNull() } ?: IOException("no result")
        return mergeInterleaved(lists)
    }

    /** One station from each directory in turn, duplicates (same stream URL) dropped. */
    private fun mergeInterleaved(lists: List<List<Track>>): List<Track> {
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        var i = 0
        while (lists.any { i < it.size }) {
            for (l in lists) if (i < l.size && seen.add(l[i].remoteId)) out.add(l[i])
            i++
        }
        return out
    }

    /** One directory's stations, each remembered as coming from it (shown in the player). */
    private fun load(context: Context, query: String, source: Int): List<Track> =
        RadioOrigin.tag(context, loadRaw(context, query, source), source)

    private fun loadRaw(context: Context, query: String, source: Int): List<Track> = when (source) {
        SRC_SOMA -> RadioSources.soma(context, query)
        SRC_ICECAST -> RadioSources.icecast(query)
        SRC_GARDEN -> RadioSources.garden(query)
        SRC_SHOUTCAST -> RadioSources.shoutcast(context, query)
        SRC_PARADISE -> RadioSources.paradise(context, query)
        SRC_COLLECTION -> RadioCollection.stations(context, query)
        else -> fetch(context, query)
    }

    /** Home "recommended stations": hand-picked channels (Radio Paradise, SomaFM) first, then the
     *  best-voted stations of the device's country. Parts that fail are simply left out. */
    fun recommended(context: Context): List<Track> {
        val curated = listOf(
            RadioOrigin.tag(context, runCatching { RadioSources.paradise(context, "") }.getOrDefault(emptyList()), SRC_PARADISE),
            RadioOrigin.tag(context, runCatching { RadioSources.soma(context, "").take(8) }.getOrDefault(emptyList()), SRC_SOMA)
        )
        val voted = RadioOrigin.tag(context, runCatching { fetch(context, "", "votes") }.getOrDefault(emptyList()), SRC_BROWSER)
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        var i = 0
        while (curated.any { i < it.size }) {
            for (l in curated) if (i < l.size && seen.add(l[i].remoteId)) out.add(l[i])
            i++
        }
        voted.forEach { if (seen.add(it.remoteId)) out.add(it) }
        if (out.isEmpty()) throw ApiException(RemoteEngine.ERR_NETWORK, "network error")
        return out.take(10)
    }

    /**
     * Home "Popular radio": 5 most-listened stations of the device's country + 5 from the rest of the
     * world (a global ranking without that country's stations). Fills up from either side if one is short.
     */
    fun popularMixed(context: Context): List<Track> {
        val cc = DeviceCountry.codeOrNull(context)?.uppercase(Locale.US)
        val local = if (cc != null) RadioOrigin.tag(context, runCatching { fetch(context, "", "clickcount", cc) }.getOrDefault(emptyList()), SRC_BROWSER) else emptyList()
        val global = RadioOrigin.tag(context, runCatching { fetch(context, "", "clickcount", "") }.getOrDefault(emptyList()), SRC_BROWSER)
        if (local.isEmpty() && global.isEmpty()) throw ApiException(RemoteEngine.ERR_NETWORK, "network error")
        val localName = cc?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH) }.orEmpty() // radio-browser uses English names
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        out += local.take(5).filter { seen.add(it.remoteId) }
        out += global.asSequence()
            .filter { localName.isEmpty() || !it.artist.startsWith(localName, ignoreCase = true) }
            .filter { seen.add(it.remoteId) }.take(5).toList()
        (local + global).forEach { if (out.size < 10 && seen.add(it.remoteId)) out += it }
        return out
    }

    /** [country]: null = the device's country (default), "" = worldwide, otherwise that 2-letter code. */
    private fun fetch(context: Context, query: String, order: String = "clickcount", country: String? = null): List<Track> {
        val limit = if (MusicPrefs.saver(context)) LIMIT_SAVER else LIMIT
        val path = StringBuilder("/json/stations/search?hidebroken=true&order=$order&reverse=true&limit=$limit")
        // Audio quality (Settings): Low / Saver = stations of at most 96 kbps, Normal = 160 kbps (unknown bitrates, 0, still match).
        StreamQuality.radioMaxKbps(context).takeIf { it > 0 }?.let { path.append("&bitrateMax=").append(it) }
        if (query.isNotEmpty()) {
            path.append("&name=").append(URLEncoder.encode(query, "UTF-8"))
        } else {
            (country ?: DeviceCountry.codeOrNull(context)).takeIf { it?.length == 2 }
                ?.let { path.append("&countrycode=").append(it) }
        }
        refreshHosts()
        val hosts = orderedHosts()
        // Staggered race: the preferred mirror starts at once, the next one after STAGGER_MS without an
        // answer, and so on. The first good answer wins, so one dead mirror no longer costs its timeout.
        val pool = Executors.newFixedThreadPool(hosts.size)
        try {
            val cs = ExecutorCompletionService<Pair<String, List<Track>>>(pool)
            val deadline = SystemClock.elapsedRealtime() + FETCH_DEADLINE_MS
            var next = 0
            var running = 0
            var failure: ApiException? = null
            while (true) {
                if (next < hosts.size) {
                    val h = hosts[next++]
                    cs.submit { h to requestHost(h, path.toString()) }
                    running++
                } else if (running == 0) {
                    break
                }
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                val wait = if (next < hosts.size) minOf(STAGGER_MS, left) else left
                val f = cs.poll(wait, TimeUnit.MILLISECONDS) ?: continue
                running--
                try {
                    val (host, list) = f.get()
                    preferredHost = host
                    return list
                } catch (e: ExecutionException) {
                    failure = (e.cause as? ApiException)
                        ?: ApiException(RemoteEngine.ERR_NETWORK, e.cause?.message ?: "network error")
                }
            }
            throw failure ?: ApiException(RemoteEngine.ERR_NETWORK, "timeout")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun requestHost(host: String, path: String): List<Track> {
        try {
            val r = Http.request(
                "GET", host + path,
                mapOf("User-Agent" to "StarrySound/1.0", "Accept" to "application/json"),
                readTimeoutMs = 8_000, connectTimeoutMs = 5_000
            )
            if (r.code !in 200..299) throw ApiException(RemoteEngine.ERR_GENERIC, "HTTP ${r.code}")
            return parse(r.body)
        } catch (e: IOException) {
            throw ApiException(RemoteEngine.ERR_NETWORK, e.message ?: "network error")
        } catch (e: JSONException) {
            throw ApiException(RemoteEngine.ERR_GENERIC, "bad response")
        }
    }

    /** Known mirrors, the one that answered last first. */
    private fun orderedHosts(): List<String> {
        val all = discoveredHosts?.takeIf { it.isNotEmpty() } ?: STATIC_HOSTS
        val pref = preferredHost
        return if (pref.isNotEmpty() && pref in all) listOf(pref) + all.filter { it != pref } else all
    }

    /** Asks the official server list (in the background, at most every few hours) so dead mirrors drop out. */
    private fun refreshHosts() {
        if (refreshing || SystemClock.elapsedRealtime() - discoveredAt < HOSTS_TTL_MS && discoveredHosts != null) return
        refreshing = true
        Thread {
            try {
                val r = Http.request(
                    "GET", "https://all.api.radio-browser.info/json/servers",
                    mapOf("User-Agent" to "StarrySound/1.0", "Accept" to "application/json"),
                    readTimeoutMs = 6_000, connectTimeoutMs = 4_000
                )
                if (r.code in 200..299) {
                    val arr = JSONArray(r.body)
                    val names = LinkedHashSet<String>()
                    for (i in 0 until arr.length()) {
                        val n = arr.optJSONObject(i)?.optString("name").orEmpty().trim()
                        if (n.endsWith("radio-browser.info")) names += "https://$n"
                    }
                    if (names.isNotEmpty()) {
                        discoveredHosts = names.toList().shuffled()
                        discoveredAt = SystemClock.elapsedRealtime()
                    }
                }
            } catch (_: Exception) {
            } finally {
                refreshing = false
            }
        }.start()
    }

    private fun parse(body: String): List<Track> {
        val arr = JSONArray(body)
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            val name = o.optString("name").trim()
            if (url.isBlank() || name.isBlank() || !url.startsWith("http")) continue
            if (!seen.add(url)) continue
            val codec = o.optString("codec").trim().uppercase(Locale.US)
            val bitrate = o.optInt("bitrate", 0)
            val quality = listOf(codec, if (bitrate > 0) "${bitrate}k" else "").filter { it.isNotBlank() }.joinToString(" ")
            val info = listOf(o.optString("country").trim(), quality).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
            val icon = o.optString("favicon").trim().takeIf { it.startsWith("http") }.orEmpty()
            out += Track(
                id = 0L, title = name, artist = info, album = "", durationMs = 0L,
                source = Track.SOURCE_RADIO, remoteId = url, artUrl = icon
            )
        }
        return out
    }
}
