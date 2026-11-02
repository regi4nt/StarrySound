package com.fxsoundlite.app

import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Links of TikTok, Instagram and Facebook, which NewPipeExtractor does not read. Best effort, no login:
 *  - TikTok: the public tikwm.com web API (video without watermark in HD / normal quality, plus the sound as a song).
 *  - Instagram / Facebook: the page is read the way a link-preview crawler reads it (og:video tag; Facebook also
 *    lists its own HD / SD file links). Only PUBLIC posts work; a private post, or one that asks for a login, gives
 *    nothing, and the sites change their pages now and then, so this may need an update later.
 * Used by [LinkDownloader.resolve]; the files themselves are fetched by [SongDownloader.fetch].
 */
object SocialResolver {

    private const val CRAWLER_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private enum class Site { TIKTOK, INSTAGRAM, FACEBOOK }

    private fun siteOf(link: String): Site? {
        val host = try { URL(link).host.lowercase(Locale.ROOT) } catch (e: Exception) { return null }
        fun hit(vararg names: String) = names.any { host == it || host.endsWith(".$it") }
        return when {
            hit("tiktok.com") -> Site.TIKTOK
            hit("instagram.com", "instagr.am") -> Site.INSTAGRAM
            hit("facebook.com", "fb.watch", "fb.com") -> Site.FACEBOOK
            else -> null
        }
    }

    /** Null when [link] is not one of these sites; otherwise what can be saved (throws [IOException] when nothing was found). */
    fun resolve(link: String): LinkDownloader.Info? {
        val site = siteOf(link) ?: return null
        return when (site) {
            Site.TIKTOK -> tiktok(link)
            Site.INSTAGRAM, Site.FACEBOOK -> page(link, site)
        }
    }

    // ------------------------------------------------------------------ TikTok

    private fun tiktok(link: String): LinkDownloader.Info {
        val api = "https://www.tikwm.com/api/?hd=1&url=" + URLEncoder.encode(link, "UTF-8")
        val r = Http.request("GET", api, mapOf("User-Agent" to BROWSER_UA, "Accept" to "application/json"), readTimeoutMs = 25_000)
        if (r.code !in 200..299) throw IOException("tikwm HTTP ${r.code}")
        val root = JSONObject(r.body)
        if (root.optInt("code", -1) != 0) throw IOException("tikwm: " + root.optString("msg"))
        val d = root.optJSONObject("data") ?: throw IOException("tikwm: no data")
        fun abs(u: String) = if (u.startsWith("/")) "https://www.tikwm.com$u" else u
        val options = ArrayList<LinkDownloader.Option>()
        val hd = d.optString("hdplay")
        val sd = d.optString("play")
        if (hd.isNotBlank()) options += LinkDownloader.Option("▶ HD · MP4", abs(hd), "mp4", "video/mp4", true)
        if (sd.isNotBlank() && sd != hd) options += LinkDownloader.Option("▶ MP4", abs(sd), "mp4", "video/mp4", true)
        val music = d.optString("music")
        if (music.isNotBlank()) options += LinkDownloader.Option("♪ MP3", abs(music), "mp3", "audio/mpeg", false)
        val author = d.optJSONObject("author")?.optString("nickname").orEmpty()
        val title = d.optString("title").ifBlank { "TikTok" }
        return LinkDownloader.Info(title, author, options)
    }

    // ------------------------------------------------------------------ Instagram / Facebook

    private fun page(link: String, site: Site): LinkDownloader.Info {
        val html = fetchPage(link)
        val options = ArrayList<LinkDownloader.Option>()
        val seen = HashSet<String>()
        fun add(label: String, raw: String) {
            val url = unescape(raw)
            if (url.startsWith("http") && seen.add(url.substringBefore('?'))) {
                options += LinkDownloader.Option(label, url, "mp4", "video/mp4", true)
            }
        }
        if (site == Site.FACEBOOK) {
            Regex("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\"").find(html)?.let { add("▶ HD · MP4", it.groupValues[1]) }
            Regex("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\"").find(html)?.let { add("▶ SD · MP4", it.groupValues[1]) }
        }
        for (prop in listOf("og:video:secure_url", "og:video:url", "og:video")) {
            meta(html, prop)?.let { add("▶ MP4", it) }
        }
        if (options.isEmpty()) throw IOException("no video found (private post, login wall, or not a video)")
        val title = meta(html, "og:title")?.let { unescape(it) }?.takeIf { it.isNotBlank() }
            ?: meta(html, "og:description")?.let { unescape(it) }?.takeIf { it.isNotBlank() }
            ?: if (site == Site.INSTAGRAM) "Instagram" else "Facebook"
        return LinkDownloader.Info(title.take(120), if (site == Site.INSTAGRAM) "Instagram" else "Facebook", options)
    }

    private fun fetchPage(link: String): String {
        // The crawler agent gets the preview page with the video tags; a plain browser agent is the second try.
        var last: Http.Response? = null
        for (ua in listOf(CRAWLER_UA, BROWSER_UA)) {
            val r = Http.request("GET", link, mapOf("User-Agent" to ua, "Accept-Language" to "en-US,en;q=0.9", "Accept" to "text/html"), readTimeoutMs = 25_000)
            last = r
            if (r.code in 200..299 && (r.body.contains("og:video") || r.body.contains("browser_native_"))) return r.body
        }
        if (last == null || last.code !in 200..299) throw IOException("HTTP ${last?.code}")
        return last.body
    }

    /** Content of <meta property="[prop]" content="..."> (either attribute order). */
    private fun meta(html: String, prop: String): String? {
        val p = Regex.escape(prop)
        return Regex("<meta[^>]+(?:property|name)=[\"']$p[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$p[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
    }

    /** JSON escapes (\/ & %) and HTML entities in a value copied out of a page. */
    private fun unescape(s: String): String = s
        .replace("\\/", "/").replace("\\u0026", "&").replace("\\u0025", "%").replace("\\u003D", "=")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
