package com.fxsoundlite.app

import android.content.Context
import android.text.Html
import org.json.JSONObject
import java.net.URLEncoder

/** YouTube Data API v3 search, authenticated with the user's own API key (Settings). */
object YouTubeApi {

    private const val BASE = "https://www.googleapis.com/youtube/v3"
    /** 10 = "Music". Set to null to search every category. */
    private val CATEGORY: String? = "10"
    private const val MAX_RESULTS = 25

    private val ISO_DURATION = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")

    /** Options of the Search page's YouTube tab: no music-only filter, optionally this country and newest first, no Shorts. */
    class Opts(val region: String?, val newest: Boolean, val noShorts: Boolean)

    /** The Search page's YouTube tab: everything on YouTube (not only music), shaped by the Settings switches. */
    fun userSearch(context: Context, query: String): List<Track> {
        val region = if (MusicPrefs.ytLocal(context)) java.util.Locale.getDefault().country.takeIf { it.length == 2 } else null
        return search(
            context, query, category = null,
            opts = Opts(region, MusicPrefs.ytNewest(context), MusicPrefs.ytBlockShorts(context))
        )
    }

    private val SHORT_TITLE = Regex("(?i)#\\s?shorts?\\b|\\bshorts\\b|#reels?\\b|#tiktok\\b")

    /**
     * A Short (vertical, up to 3 minutes). Signals that need no network: flagged by YouTube, a portrait
     * thumbnail (Shorts are 9:16, ordinary videos 16:9), a #shorts-like title, or one minute or less.
     */
    private fun isShort(item: org.schabi.newpipe.extractor.stream.StreamInfoItem, seconds: Long): Boolean {
        if (seconds in 1..60) return true
        if (SHORT_TITLE.containsMatchIn(item.name.orEmpty())) return true
        if (item.url.orEmpty().contains("/shorts/")) return true
        if (try { item.thumbnails.any { it.width > 0 && it.height > it.width } } catch (_: Throwable) { false }) return true
        return try { item.javaClass.getMethod("isShortFormContent").invoke(item) as? Boolean == true } catch (_: Throwable) { false }
    }

    /**
     * Blocking. Costs 100 + 1 quota units per call (search.list + videos.list).
     * [category] null searches every category; [longOnly] asks for videos over 20 minutes (sleep sounds).
     * With [Opts.noShorts] the results are also checked against YouTube itself (see [dropShorts]).
     */
    fun search(
        context: Context,
        query: String,
        category: String? = CATEGORY,
        longOnly: Boolean = false,
        maxResults: Int = MAX_RESULTS,
        opts: Opts? = null
    ): List<Track> {
        // "Block Shorts" (Settings) applies to every search, not only the Search page's YouTube tab:
        // callers that pass no options (Home picks, shuffle, playlist generator ...) follow the switch too.
        val eff = opts ?: if (MusicPrefs.ytBlockShorts(context)) Opts(null, false, true) else null
        val raw = searchRaw(context, query, category, longOnly, maxResults, eff)
        // A pasted link is exactly what the user asked for: never filtered.
        return if (eff?.noShorts == true && LinkTracks.youtubeId(query) == null) dropShorts(raw) else raw
    }

    // ------------------------------------------------------------------ Shorts

    /** Shorts last up to 3 minutes; a little margin for rounding. Longer videos cannot be Shorts. */
    private const val SHORTS_MAX_MS = 185_000L
    private const val SHORTS_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val shortsCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Removes Shorts from [tracks]. Neither the API nor the extractor flags them reliably, and the old checks
     * (one minute or less, "#shorts" in the title) miss Shorts of 1 to 3 minutes. So every result of 3 minutes
     * or less is asked of YouTube itself: /shorts/ID answers directly for a Short and redirects to /watch for
     * an ordinary video. Answers are remembered; when the check fails (offline, consent page) the video is kept.
     */
    internal fun dropShorts(tracks: List<Track>): List<Track> {
        if (shortsCache.size > 3000) shortsCache.clear()
        val todo = tracks.filter { it.remoteId.isNotBlank() && it.durationMs <= SHORTS_MAX_MS && !shortsCache.containsKey(it.remoteId) }
            .distinctBy { it.remoteId }
        if (todo.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, todo.size))
            try {
                val jobs = todo.map { t -> pool.submit { probeShort(t.remoteId)?.let { shortsCache[t.remoteId] = it } } }
                jobs.forEach { try { it.get(8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { } }
            } finally {
                pool.shutdownNow()
            }
        }
        return tracks.filter { shortsCache[it.remoteId] != true }
    }

    /** true = Short, false = ordinary video, null = could not tell. Tries the desktop host, then the mobile one. */
    private fun probeShort(id: String): Boolean? =
        probeShortAt("https://www.youtube.com/shorts/", id) ?: probeShortAt("https://m.youtube.com/shorts/", id)

    private fun probeShortAt(base: String, id: String): Boolean? = try {
        val conn = java.net.URL(base + enc(id)).openConnection() as java.net.HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET" // HEAD is answered inconsistently; only the status line is read, the body is dropped
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            conn.setRequestProperty("User-Agent", SHORTS_UA)
            conn.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+") // skips the cookie-consent redirect where it applies
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location").orEmpty()
            when {
                code == 200 -> true
                code in 300..399 && loc.contains("consent") -> null
                code in 300..399 && (loc.contains("/watch") || loc.contains("v=$id")) -> false
                else -> null
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun searchRaw(
        context: Context,
        query: String,
        category: String?,
        longOnly: Boolean,
        maxResults: Int,
        opts: Opts?
    ): List<Track> {
        // A pasted YouTube link gives that one video instead of a keyword search.
        LinkTracks.youtubeId(query)?.let { return listOf(linkedVideo(context, it)) }
        val maxResults = if (MusicPrefs.saver(context)) minOf(maxResults, 10) else maxResults
        val key = MusicPrefs.youtubeKey(context)
        // No API key (or the key/quota failed): search through NewPipeExtractor instead.
        if (key.isBlank()) return searchExtractor(query, longOnly, maxResults, opts = opts)
        return try {
            searchApi(key, query, category, longOnly, maxResults, opts)
        } catch (e: ApiException) {
            if (e.code == RemoteEngine.ERR_NETWORK) throw e
            try { searchExtractor(query, longOnly, maxResults, opts = opts) } catch (_: Exception) { throw e }
        }
    }

    /** One video for a pasted link: through the user's API key when there is one, else through NewPipe. */
    private fun linkedVideo(context: Context, id: String): Track {
        val key = MusicPrefs.youtubeKey(context)
        if (key.isNotBlank()) {
            try {
                return videoApi(key, id)
            } catch (e: ApiException) {
                if (e.code == RemoteEngine.ERR_NETWORK) throw e
            }
        }
        return videoExtractor(id)
    }

    private fun videoApi(key: String, id: String): Track {
        val body = get("$BASE/videos?part=snippet,contentDetails&id=${enc(id)}&key=${enc(key)}")
        val item = body.optJSONArray("items")?.optJSONObject(0)
            ?: throw ApiException(RemoteEngine.ERR_GENERIC, "video not found")
        val sn = item.optJSONObject("snippet")
            ?: throw ApiException(RemoteEngine.ERR_GENERIC, "video not found")
        val thumbs = sn.optJSONObject("thumbnails")
        val art = thumbs?.optJSONObject("high")?.optString("url")
            ?: thumbs?.optJSONObject("medium")?.optString("url")
            ?: thumbs?.optJSONObject("default")?.optString("url").orEmpty()
        return Track(
            id = 0,
            title = decode(sn.optString("title")),
            artist = decode(sn.optString("channelTitle")),
            album = "",
            durationMs = parseIso(item.optJSONObject("contentDetails")?.optString("duration").orEmpty()),
            source = Track.SOURCE_YOUTUBE,
            remoteId = id,
            artUrl = art
        )
    }

    private fun videoExtractor(id: String): Track {
        val info = try {
            org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(
                org.schabi.newpipe.extractor.ServiceList.YouTube, "https://www.youtube.com/watch?v=$id"
            )
        } catch (e: java.io.IOException) {
            throw ApiException(RemoteEngine.ERR_NETWORK, e.message ?: "network error")
        } catch (e: Exception) {
            throw ApiException(RemoteEngine.ERR_GENERIC, e.message ?: "video not found")
        }
        return Track(
            id = 0,
            title = info.name.orEmpty(),
            artist = info.uploaderName.orEmpty(),
            album = "",
            durationMs = (info.duration.coerceAtLeast(0)) * 1000,
            source = Track.SOURCE_YOUTUBE,
            remoteId = id,
            artUrl = bestArt(info.thumbnails)
        )
    }

    /**
     * NewPipe lists thumbnails smallest first, so firstOrNull() gave a ~60-120 px image that looked
     * blurry on the full-screen Quick cover. Take the biggest one, and ask Google's image hosts
     * for a larger rendition where the size is part of the URL.
     */
    internal fun bestArt(thumbs: List<org.schabi.newpipe.extractor.Image>): String {
        val best = thumbs.maxByOrNull { it.width.coerceAtLeast(0) * it.height.coerceAtLeast(0) }
            ?: return ""
        return upgradeArt(best.url.orEmpty())
    }

    internal fun upgradeArt(url: String): String = when {
        // Signed thumbnail URLs (?sqp=...&rs=...) become invalid when the file name is changed -> keep as is.
        "i.ytimg.com" in url && '?' in url -> url
        // YouTube Music album art: ...=w120-h120-l90-rj  ->  ...=w544-h544-l90-rj
        "googleusercontent.com" in url || "ggpht.com" in url ->
            url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
                .replace(Regex("=s\\d+"), "=s544")
        // Plain video thumbnails: default / mqdefault -> hqdefault (480x360)
        "i.ytimg.com/vi" in url || "i.ytimg.com/vi_webp" in url ->
            url.replace(Regex("/(default|mqdefault)\\.(jpg|webp)"), "/hqdefault.$2")
        else -> url
    }

    /** Key-less search: same results as youtube.com, no quota. */
    internal fun searchExtractor(
        query: String, longOnly: Boolean, maxResults: Int,
        filter: String = org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.VIDEOS,
        opts: Opts? = null
    ): List<Track> {
        // Content country: results of this country (or the default one when the filter is off).
        try {
            val country = opts?.region
            org.schabi.newpipe.extractor.NewPipe.init(
                NewPipeDownloader,
                if (country != null) org.schabi.newpipe.extractor.localization.Localization(
                    java.util.Locale.getDefault().language, country
                ) else org.schabi.newpipe.extractor.localization.Localization.DEFAULT,
                if (country != null) org.schabi.newpipe.extractor.localization.ContentCountry(country)
                else org.schabi.newpipe.extractor.localization.ContentCountry.DEFAULT
            )
        } catch (_: Throwable) { }
        val items = ArrayList<org.schabi.newpipe.extractor.InfoItem>()
        val result = try {
            val service = org.schabi.newpipe.extractor.ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(
                query, listOf(filter), ""
            )
            val info = org.schabi.newpipe.extractor.search.SearchInfo.getInfo(service, handler)
            items += info.relatedItems
            // Newest first needs a bigger pool to sort: read one more page.
            if (opts?.newest == true && info.hasNextPage()) {
                try {
                    items += org.schabi.newpipe.extractor.search.SearchInfo.getMoreItems(service, handler, info.nextPage).items
                } catch (_: Exception) { }
            }
            info
        } catch (e: java.io.IOException) {
            throw ApiException(RemoteEngine.ERR_NETWORK, e.message ?: "network error")
        } catch (e: Exception) {
            throw ApiException(RemoteEngine.ERR_GENERIC, e.message ?: "search failed")
        }
        val out = ArrayList<Track>()
        var streams = items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
        if (opts?.newest == true) {
            // Newest upload first; videos without a date keep their order at the end.
            streams = streams.sortedByDescending {
                (try { it.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() } catch (_: Throwable) { null }) ?: Long.MIN_VALUE
            }
        }
        for (item in streams) {
            val seconds = item.duration
            if (seconds <= 0) continue // live streams
            if (longOnly && seconds < 20 * 60) continue
            if (opts?.noShorts == true && isShort(item, seconds)) continue
            val id = Regex("[?&]v=([A-Za-z0-9_-]{6,20})").find(item.url)?.groupValues?.get(1) ?: continue
            out.add(
                Track(
                    id = 0,
                    title = item.name.orEmpty(),
                    artist = item.uploaderName.orEmpty(),
                    album = "",
                    durationMs = seconds * 1000,
                    source = Track.SOURCE_YOUTUBE,
                    remoteId = id,
                    artUrl = bestArt(item.thumbnails)
                )
            )
            if (out.size >= maxResults) break
        }
        return out
    }

    private fun searchApi(
        key: String,
        query: String,
        category: String?,
        longOnly: Boolean,
        maxResults: Int,
        opts: Opts? = null
    ): List<Track> {

        val url = StringBuilder("$BASE/search?part=snippet&type=video")
            // Only videos the owner allows in third-party players, otherwise they fail to play.
            .append("&videoEmbeddable=true&videoSyndicated=true")
            .append("&maxResults=$maxResults")
        if (longOnly) url.append("&videoDuration=long")
        if (category != null) url.append("&videoCategoryId=$category")
        opts?.region?.let { url.append("&regionCode=").append(enc(it)) }
        if (opts?.newest == true) url.append("&order=date")
        url.append("&q=").append(enc(query)).append("&key=").append(enc(key))

        val body = get(url.toString())
        val items = body.optJSONArray("items") ?: return emptyList()

        val ids = ArrayList<String>()
        val base = LinkedHashMap<String, Track>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
            if (id.isEmpty()) continue
            val sn = item.optJSONObject("snippet") ?: continue
            val thumbs = sn.optJSONObject("thumbnails")
            val art = thumbs?.optJSONObject("high")?.optString("url")
                ?: thumbs?.optJSONObject("medium")?.optString("url")
                ?: thumbs?.optJSONObject("default")?.optString("url").orEmpty()
            ids.add(id)
            base[id] = Track(
                id = 0,
                title = decode(sn.optString("title")),
                artist = decode(sn.optString("channelTitle")),
                album = "",
                durationMs = 0,
                source = Track.SOURCE_YOUTUBE,
                remoteId = id,
                artUrl = art
            )
        }
        if (ids.isEmpty()) return emptyList()

        // search.list has no duration: one cheap videos.list call fills it in (and drops live streams).
        val details = get(
            "$BASE/videos?part=contentDetails&id=${enc(ids.joinToString(","))}&key=${enc(key)}"
        )
        val result = ArrayList<Track>()
        val arr = details.optJSONArray("items")
        if (arr != null) {
            val durations = HashMap<String, Long>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                durations[o.optString("id")] =
                    parseIso(o.optJSONObject("contentDetails")?.optString("duration").orEmpty())
            }
            for (id in ids) {
                val d = durations[id] ?: continue
                if (d <= 0) continue
                if (opts?.noShorts == true && (d <= 60_000L || SHORT_TITLE.containsMatchIn(base.getValue(id).title))) continue
                result.add(base.getValue(id).copy(durationMs = d))
            }
        }
        return result
    }

    private fun get(url: String): JSONObject {
        val r = try {
            Http.request("GET", url)
        } catch (e: Exception) {
            throw ApiException(RemoteEngine.ERR_NETWORK, e.message ?: "network error")
        }
        if (r.code !in 200..299) {
            val msg = try {
                JSONObject(r.body).getJSONObject("error").optString("message")
            } catch (e: Exception) {
                "HTTP ${r.code}"
            }
            throw ApiException(RemoteEngine.ERR_GENERIC, msg.ifBlank { "HTTP ${r.code}" })
        }
        return JSONObject(r.body)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun decode(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun parseIso(s: String): Long {
        val m = ISO_DURATION.matchEntire(s) ?: return 0
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLongOrNull() ?: 0
        val sec = m.groupValues[3].toLongOrNull() ?: 0
        return (h * 3600 + min * 60 + sec) * 1000
    }
}
