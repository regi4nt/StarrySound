package com.fxsoundlite.app

import android.content.Context
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Songs from the phone's own storage have a MediaStore id that means nothing on another device.
 * Playlists synced through Drive therefore identify them by title + artist ([syncKey]), and this
 * object finds the matching file in THIS device's library. A song that is not on this device is
 * returned flagged [Track.missing] (kept in the playlist, but skipped when playing).
 *
 * The library index is cached (a MediaStore scan is too slow to repeat on every list read).
 */
object LocalMatcher {

    private class Index(
        val byId: Map<Long, Track>,
        val byKey: Map<String, List<Track>>,
        val byTitle: Map<String, List<Track>>,
        val at: Long
    )

    private const val TTL_MS = 60_000L
    private const val TITLE_ONLY_TOLERANCE_MS = 3_000L

    @Volatile private var index: Index? = null
    private val refreshing = AtomicBoolean(false)

    /** Lower-case letters and digits only, so "Don't Stop (Remastered)" style punctuation noise cannot split a match. */
    fun norm(s: String): String = buildString {
        for (ch in s.lowercase(Locale.ROOT)) if (ch.isLetterOrDigit()) append(ch)
    }

    /** Device-independent identity of a local song. */
    fun localKey(title: String, artist: String) = "M:${norm(title)}|${norm(artist)}"

    /** Rebuilds the index now. Blocking: call from a background thread. */
    fun refresh(c: Context) { build(c, force = true) }

    @Synchronized
    private fun build(c: Context, force: Boolean): Index? {
        val cur = index
        if (!force && cur != null && SystemClock.elapsedRealtime() - cur.at <= TTL_MS) return cur
        if (!MusicLibrary.canRead(c)) return null
        val list = MusicLibrary.load(c)
        if (list.isEmpty()) return null // library not scanned / unreadable: do not conclude that songs are missing
        val idx = Index(
            byId = list.associateBy { it.id },
            byKey = list.groupBy { localKey(it.title, it.artist) },
            byTitle = list.groupBy { norm(it.title) },
            at = SystemClock.elapsedRealtime()
        )
        index = idx
        return idx
    }

    private fun index(c: Context): Index? {
        val cur = index
        if (cur == null) return build(c, force = false) // first use: wait once
        if (SystemClock.elapsedRealtime() - cur.at > TTL_MS && refreshing.compareAndSet(false, true)) {
            val app = c.applicationContext
            Thread { try { build(app, force = true) } finally { refreshing.set(false) } }.start()
        }
        return cur // stale-while-revalidate
    }

    /**
     * The same song on this device: a valid local [Track] (with this device's id), or [t] flagged
     * [Track.missing] when it is not here. Non-local tracks, and every track while the library
     * cannot be read, come back unchanged.
     */
    fun resolve(c: Context, t: Track): Track {
        if (t.source != Track.SOURCE_LOCAL) return t
        val idx = index(c) ?: return t
        val title = norm(t.title)
        // A song of a picked OTG / SD folder keeps the same id and document uri while its title changes from the file
        // name to the real tag, so the uri counts as proof of identity too.
        idx.byId[t.id]?.let {
            if (norm(it.title) == title || (t.remoteId.isNotEmpty() && t.remoteId == it.remoteId)) return it.copy(addedAt = t.addedAt)
        }

        fun closest(list: List<Track>?): Track? = list?.minByOrNull { abs(it.durationMs - t.durationMs) }
        closest(idx.byKey[localKey(t.title, t.artist)])?.let { return it.copy(addedAt = t.addedAt) }

        // Same title where one side has no artist tag: accept only when the length agrees too.
        idx.byTitle[title]
            ?.filter { (it.artist.isBlank() || t.artist.isBlank()) && abs(it.durationMs - t.durationMs) <= TITLE_ONLY_TOLERANCE_MS }
            ?.let { closest(it) }
            ?.let { return it.copy(addedAt = t.addedAt) }
        return t.copy(missing = true)
    }
}
