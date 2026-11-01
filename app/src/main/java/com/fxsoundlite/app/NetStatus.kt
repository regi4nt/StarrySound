package com.fxsoundlite.app

import android.os.Handler
import android.os.Looper
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Online / offline as the app itself experiences it (like Spotify / YouTube Music): every request the
 * app makes reports here. Any answer from a server = online; two network failures in a row (no DNS,
 * no route, timeout) with no answer in between = offline. No extra traffic is generated.
 */
object NetStatus {
    @Volatile private var failures = 0
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** True after two network failures in a row. */
    val failing: Boolean get() = failures >= 2

    // Moving average of how long servers take to start answering (connect + TLS + first byte).
    // High = a slow / weak connection: callers then skip optional traffic and use lighter requests.
    @Volatile private var avgMs = 0f
    @Volatile private var avgAt = 0L
    private const val WEAK_LATENCY_MS = 1_500f
    private const val STALE_MS = 3 * 60_000L

    /** Called by the HTTP helpers with the time (ms) a server needed to start answering. */
    @Synchronized
    fun recordLatency(ms: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        avgMs = if (avgMs == 0f || now - avgAt > STALE_MS) ms.toFloat() else avgMs * 0.7f + ms * 0.3f
        avgAt = now
    }

    /** True while the connection looks weak: a recent network failure, or slow first answers lately. */
    val weak: Boolean
        get() = failures >= 1 ||
            (avgMs > WEAK_LATENCY_MS && android.os.SystemClock.elapsedRealtime() - avgAt < STALE_MS)

    fun ok() {
        val was = failing
        failures = 0
        if (was) notifyListeners()
    }

    fun fail(e: Throwable) {
        if (e !is UnknownHostException && e !is ConnectException && e !is SocketTimeoutException &&
            e !is NoRouteToHostException) return
        val before = failing
        failures++
        if (!before && failing) notifyListeners()
    }

    /** Forget past failures (a new network came up, or a probe got through). */
    fun reset() {
        val was = failing
        failures = 0
        if (was) notifyListeners()
    }

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyListeners() { main.post { listeners.forEach { it() } } }
}
