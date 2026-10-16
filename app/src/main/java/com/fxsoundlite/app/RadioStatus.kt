package com.fxsoundlite.app

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Is a radio station reachable right now? Each station of a shown list is probed in the
 * background (playlist links resolved, then one request that only reads the response headers, no
 * audio is downloaded) and the answer is remembered for [TTL_MS].
 */
object RadioStatus {
    const val UNKNOWN = 0
    const val ACTIVE = 1
    const val INACTIVE = 2

    private const val TTL_MS = 5 * 60 * 1000L
    private const val TIMEOUT_MS = 5_000
    private const val MAX_REDIRECTS = 5

    private class Result(val ok: Boolean, val at: Long)

    private val results = ConcurrentHashMap<String, Result>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Last known state of [url] (an old answer is kept until a new probe replaces it). */
    fun get(url: String): Int {
        val r = results[url] ?: return UNKNOWN
        return if (r.ok) ACTIVE else INACTIVE
    }

    /** Probes the stations that have no fresh answer; [onResult] runs on the main thread per station. */
    fun probe(urls: List<String>, onResult: (String) -> Unit) {
        val now = System.currentTimeMillis()
        for (u in urls.distinct()) {
            val r = results[u]
            if (r != null && now - r.at < TTL_MS) continue
            if (!pending.add(u)) continue
            try {
                pool.execute {
                    val ok = try { check(u) } catch (_: Throwable) { false }
                    results[u] = Result(ok, System.currentTimeMillis())
                    pending.remove(u)
                    main.post { onResult(u) }
                }
            } catch (_: Exception) {
                pending.remove(u)
            }
        }
    }

    private fun check(url: String): Boolean {
        for (u in RadioPlaylist.resolve(url).take(2)) if (ping(u)) return true
        return false
    }

    /** True when the server answers 2xx with something that is not a web page. */
    private fun ping(start: String): Boolean {
        var url = start
        for (hop in 0..MAX_REDIRECTS) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.instanceFollowRedirects = false // also follow http -> https ourselves
                conn.setRequestProperty("User-Agent", "StarrySound/1.0")
                conn.setRequestProperty("Icy-MetaData", "0")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return false
                    url = URL(URL(url), loc).toString()
                    continue
                }
                if (code !in 200..299) return false
                return !(conn.contentType ?: "").lowercase(Locale.US).startsWith("text/html")
            } catch (e: IOException) {
                return false
            } finally {
                conn.disconnect()
            }
        }
        return false
    }
}
