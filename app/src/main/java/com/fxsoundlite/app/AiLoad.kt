package com.fxsoundlite.app

import android.content.Context
import android.os.SystemClock

/**
 * Spreads AI requests over every AI service that has a key (Settings > AI), so no single one is worn out:
 *  - each request goes to the service that has done the least work in the last minute (the service the user
 *    picked counts double, so it still gets the biggest share),
 *  - a service that says "limit reached / no credit / busy / bad key" rests for a while and the request is
 *    retried on the next service straight away.
 * State lives in memory only (it resets when the app process restarts). Thread-safe.
 */
object AiLoad {
    private const val WINDOW_MS = 60_000L
    private const val PREFERRED_WEIGHT = 2.0

    private val recent = HashMap<AiProvider, ArrayList<Long>>()
    private val restUntil = HashMap<AiProvider, Long>()

    private fun now() = SystemClock.elapsedRealtime()

    /** Providers that have a key. */
    fun configured(c: Context): List<AiProvider> =
        AiProvider.values().filter { MusicPrefs.aiKey(c, it).isNotBlank() }

    /** Providers to try, best first. Resting ones come last (only used when nothing else is left). */
    @Synchronized
    fun order(c: Context, preferred: AiProvider): List<AiProvider> {
        val t = now()
        val (ready, resting) = configured(c).partition { (restUntil[it] ?: 0L) <= t }
        fun score(p: AiProvider): Double {
            val list = recent[p]
            list?.removeAll { t - it > WINDOW_MS }
            val w = if (p == preferred) PREFERRED_WEIGHT else 1.0
            return ((list?.size ?: 0) + 1) / w
        }
        return ready.sortedWith(compareBy<AiProvider> { score(it) }.thenBy { if (it == preferred) 0 else 1 }) +
            resting.sortedBy { restUntil[it] ?: 0L }
    }

    @Synchronized
    fun started(p: AiProvider) {
        recent.getOrPut(p) { ArrayList() }.add(now())
    }

    @Synchronized
    fun succeeded(p: AiProvider) {
        restUntil.remove(p)
    }

    /** [resId] is the AiException's message: decides how long the service rests. */
    @Synchronized
    fun failed(p: AiProvider, resId: Int) {
        val ms = when (resId) {
            R.string.ai_err_rate -> 60_000L
            R.string.ai_err_credit -> 30 * 60_000L
            R.string.ai_err_key -> 30 * 60_000L
            R.string.ai_err_model -> 10 * 60_000L
            R.string.ai_err_busy -> 30_000L
            else -> 15_000L
        }
        restUntil[p] = now() + ms
    }

    /** A key or model was edited: forget the rests so the new settings get a fair try. */
    @Synchronized
    fun reset() {
        restUntil.clear()
    }

    /** Failures worth trying another service for. Network loss and empty prompts are not (all would fail). */
    fun worthRetry(resId: Int): Boolean =
        resId != R.string.ai_err_network && resId != R.string.ai_err_empty_prompt && resId != R.string.ai_err_no_key
}
