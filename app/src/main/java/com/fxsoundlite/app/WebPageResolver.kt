package com.fxsoundlite.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Last resort of [LinkDownloader.resolve] for any other website: reads the page and lists the audio / video FILES it
 * points to (og:video / og:audio tags, <video>, <audio> and <source> tags, "contentUrl" of the page data, or a plain
 * link to a media file inside the page). Also takes a link whose server says it is a media file although the address
 * has no file extension. Streams that are not one file (HLS .m3u8, DASH .mpd), pages that build their player with
 * scripts after loading, and protected media (DRM, login, hot-link checks) cannot be saved this way.
 */
object WebPageResolver {

    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_OPTIONS = 8
    private const val MAX_PAGE_CHARS = 3_000_000
    private val VIDEO_EXT = listOf("mp4", "webm", "m4v", "mov", "mkv", "ogv", "3gp")
    private val AUDIO_EXT = listOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav")

    fun resolve(link: String): LinkDownloader.Info {
        val type = contentType(link)
        if (type.startsWith("video/") || type.startsWith("audio/")) {
            val video = type.startsWith("video/")
            val ext = extFromMime(type) ?: if (video) "mp4" else "mp3"
            val name = URL(link).path.substringAfterLast('/').substringBeforeLast('.').ifBlank { URL(link).host }
            return LinkDownloader.Info(name, URL(link).host, listOf(option(link, ext, video, "")))
        }
        val r = Http.request("GET", link, mapOf("User-Agent" to UA, "Accept" to "text/html,*/*;q=0.8", "Accept-Language" to "en-US,en;q=0.9"), readTimeoutMs = 25_000)
        if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
        val html = r.body.take(MAX_PAGE_CHARS)
        val base = URL(link)
        val found = LinkedHashMap<String, Pair<String, Boolean>>() // url -> (ext, video)

        fun add(raw: String, hintVideo: Boolean? = null) {
            val u = absolute(base, unescape(raw)) ?: return
            val path = u.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            val ext = path.substringAfterLast('.', "")
            val video = when {
                ext in VIDEO_EXT -> true
                ext in AUDIO_EXT -> false
                else -> hintVideo ?: return // no known file type: only taken when a tag says what it is
            }
            if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return
            val key = u.substringBefore('?')
            if (found.size < MAX_OPTIONS && key !in found.keys.map { it.substringBefore('?') }) {
                found[u] = (if (ext in VIDEO_EXT || ext in AUDIO_EXT) ext else if (video) "mp4" else "mp3") to video
            }
        }

        for (p in listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream")) meta(html, p)?.let { add(it, true) }
        for (p in listOf("og:audio:secure_url", "og:audio:url", "og:audio")) meta(html, p)?.let { add(it, false) }
        Regex("<video[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach { add(it.groupValues[1], true) }
        Regex("<audio[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html).forEach { add(it.groupValues[1], false) }
        Regex("<source[^>]+src=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach {
            val tag = it.value.lowercase(Locale.ROOT)
            add(it.groupValues[1], if (tag.contains("audio/")) false else if (tag.contains("video/")) true else null)
        }
        Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"").findAll(html).forEach { add(it.groupValues[1], null) }
        val ext = (VIDEO_EXT + AUDIO_EXT).joinToString("|")
        Regex("https?:(?:\\\\?/){2}[^\"'\\s<>\\\\]+?\\.(?:$ext)(?:\\?[^\"'\\s<>\\\\]*)?", RegexOption.IGNORE_CASE).findAll(html).forEach { add(it.value, null) }

        if (found.isEmpty()) throw IOException("no media file in the page")
        val title = (meta(html, "og:title") ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1))
            ?.let { unescape(it).trim() }?.takeIf { it.isNotBlank() } ?: base.host
        // Videos first, then songs; each keeps the order of the page.
        val options = found.entries.sortedByDescending { it.value.second }.map { (u, v) -> option(u, v.first, v.second, link) }
        return LinkDownloader.Info(title.take(120), base.host, options)
    }

    private fun option(url: String, ext: String, video: Boolean, referer: String): LinkDownloader.Option {
        val file = try { java.net.URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8") } catch (e: Exception) { "" }
        val shortName = if (file.length > 28) file.take(25) + "…" else file
        val label = (if (video) "▶ " else "♪ ") + ext.uppercase(Locale.ROOT) + (if (shortName.isBlank()) "" else " · $shortName")
        val mime = (if (video) "video/" else "audio/") + when (ext) { "mp3" -> "mpeg"; "m4a" -> "mp4"; "mov" -> "quicktime"; "mkv" -> "x-matroska"; else -> ext }
        return LinkDownloader.Option(label, url, ext, mime, video, referer)
    }

    /** Content-Type the server answers for [link] (HEAD), "" when unknown. */
    private fun contentType(link: String): String = try {
        val c = URL(link).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "HEAD"
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", UA)
            (if (c.responseCode in 200..299) c.contentType else null).orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        } finally { c.disconnect() }
    } catch (e: Exception) { "" }

    private fun extFromMime(m: String): String? = when (m) {
        "video/mp4" -> "mp4"; "video/webm" -> "webm"; "video/quicktime" -> "mov"; "video/x-matroska" -> "mkv"; "video/ogg" -> "ogv"; "video/3gpp" -> "3gp"
        "audio/mpeg", "audio/mp3" -> "mp3"; "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a"; "audio/ogg" -> "ogg"; "audio/flac" -> "flac"
        "audio/wav", "audio/x-wav" -> "wav"; "audio/opus" -> "opus"
        else -> null
    }

    private fun absolute(base: URL, raw: String): String? = try {
        val s = raw.trim()
        if (s.isEmpty() || s.startsWith("data:") || s.startsWith("blob:")) null else URL(base, s).toString()
    } catch (e: Exception) { null }

    private fun meta(html: String, prop: String): String? {
        val p = Regex.escape(prop)
        return Regex("<meta[^>]+(?:property|name)=[\"']$p[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$p[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
    }

    private fun unescape(s: String): String = s
        .replace("\\/", "/").replace("\\u0026", "&").replace("\\u0025", "%")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
