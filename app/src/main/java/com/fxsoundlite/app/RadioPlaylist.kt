package com.fxsoundlite.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Many radio stations publish a playlist link (`.pls` / `.m3u`) instead of the stream itself, and the
 * framework MediaPlayer cannot play those. [resolve] turns such a link into the real stream URL(s).
 * `.m3u8` is NOT handled here: that is HLS, which MediaPlayer plays natively.
 * Blocking: call from a background thread.
 */
object RadioPlaylist {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_REDIRECTS = 4
    private const val MAX_DEPTH = 2
    private const val USER_AGENT = "StarrySound/1.0"

    private val PLS_FILE = Regex("^file\\d+\\s*=\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val PLS_META = Regex("^(title|length|numberofentries|version)\\d*\\s*=", RegexOption.IGNORE_CASE)

    /** True when the URL path ends in .pls or .m3u (query string ignored). */
    fun isPlaylistUrl(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        return path.endsWith(".pls") || path.endsWith(".m3u")
    }

    /**
     * The playable stream URLs behind [url], best first. A plain stream URL comes back as is.
     * Throws [IOException] when a playlist could not be downloaded or contains no usable entry.
     */
    fun resolve(url: String): List<String> {
        cached(url)?.let { return it }
        val out = resolve(url, 0)
        cache[url] = Cached(out, System.currentTimeMillis())
        return out
    }

    /**
     * Like [resolve], but every address also gets its redirects followed (see [followRedirects]).
     * Only for reconnect attempts: the first attempt plays the station's own link untouched, and the
     * status probe of the list must not open this extra connection to every station.
     */
    fun resolveFollowed(url: String): List<String> {
        cachedFollowed(url)?.let { return it }
        val out = resolve(url).map { followRedirects(it) }
        followedCache[url] = Cached(out, System.currentTimeMillis())
        return out
    }

    // A station that was resolved once (by the list's status probe or an earlier play) starts at once next time.
    private class Cached(val urls: List<String>, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private val followedCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    /** Forget what was resolved for [url] (the stream failed: look it up again next time). */
    fun invalidate(url: String) { cache.remove(url); followedCache.remove(url) }

    /** [resolveFollowed] result when known and still fresh, else null. Never blocks. */
    fun cachedFollowed(url: String): List<String>? {
        val c = followedCache[url] ?: return null
        return if (System.currentTimeMillis() - c.at < CACHE_TTL_MS) c.urls else { followedCache.remove(url); null }
    }

    /** The resolved stream URLs of [url] when they are known and still fresh, else null. Never blocks. */
    fun cached(url: String): List<String>? {
        val c = cache[url] ?: return null
        return if (System.currentTimeMillis() - c.at < CACHE_TTL_MS) c.urls else { cache.remove(url); null }
    }

    /**
     * MediaPlayer refuses redirects that switch between http and https (Radio Garden and many
     * stations do exactly that), so the link is followed here and the final address is played.
     * Only the response headers are read, then the connection is closed. Any problem (timeout,
     * an "ICY 200 OK" status line, ...) just keeps the original link.
     */
    private fun followRedirects(start: String): String {
        var url = start
        try {
            for (hop in 0..MAX_REDIRECTS) {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 4_000
                    conn.readTimeout = 4_000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    val code = conn.responseCode
                    if (code !in 300..399) return url
                    val loc = conn.getHeaderField("Location") ?: return url
                    url = URL(URL(url), loc).toString()
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) { android.util.Log.d("RadioPlaylist", "ignored: " + e) }
        return url
    }

    private fun resolve(url: String, depth: Int): List<String> {
        if (!isPlaylistUrl(url) || depth > MAX_DEPTH) return listOf(url)
        val text = download(url) ?: return listOf(url) // answered with audio: it is a stream after all
        val out = LinkedHashSet<String>()
        for (entry in parse(text, url)) {
            if (isPlaylistUrl(entry)) {
                try { out.addAll(resolve(entry, depth + 1)) } catch (e: IOException) { android.util.Log.d("RadioPlaylist", "ignored: " + e) }
            } else {
                out.add(entry)
            }
        }
        if (out.isEmpty()) throw IOException("empty playlist")
        return out.toList()
    }

    /** Entries of a PLS or M3U body; relative entries are resolved against [base]. */
    internal fun parse(text: String, base: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue
            val value = PLS_FILE.find(line)?.groupValues?.get(1)?.trim()
                ?: if (PLS_META.containsMatchIn(line)) continue else line
            val abs = try { URL(URL(base), value).toString() } catch (_: Exception) { continue }
            if (abs.startsWith("http://", true) || abs.startsWith("https://", true)) out += abs
        }
        return out
    }

    /**
     * Downloads the playlist text (at most [MAX_BYTES], with timeouts, following redirects even
     * from http to https). Returns null if the server is actually sending an audio stream.
     */
    private fun download(start: String): String? {
        var url = start
        for (hop in 0..MAX_REDIRECTS) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    url = URL(URL(url), loc).toString()
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val type = (conn.contentType ?: "").lowercase(Locale.US)
                if (type.startsWith("audio/") && !type.contains("mpegurl") && !type.contains("scpls")) return null
                val buf = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val chunk = ByteArray(4096)
                    while (buf.size() < MAX_BYTES) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                }
                return buf.toString("UTF-8")
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects")
    }
}
