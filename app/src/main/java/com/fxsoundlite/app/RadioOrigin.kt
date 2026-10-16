package com.fxsoundlite.app

import android.content.Context

/**
 * Remembers which directory a radio station came from (Radio Browser, SomaFM, ...), by stream URL,
 * so the player can say where the station is played from. Saved on the device, newest [MAX] kept.
 */
object RadioOrigin {
    /** A pasted stream link (not from any directory). */
    const val LINK = -1

    private const val PREFS = "radio_origin"
    private const val KEY = "map"
    private const val MAX = 800

    private val map = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Int>?) = size > MAX
    }
    private var loaded = false

    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        for (line in raw.split('\n')) {
            val i = line.lastIndexOf('\t')
            if (i <= 0) continue
            val src = line.substring(i + 1).toIntOrNull() ?: continue
            map[line.substring(0, i)] = src
        }
    }

    /** Marks every station of [tracks] as coming from [src] (one of RadioApi.SRC_*, or [LINK]). Returns [tracks]. */
    fun tag(c: Context, tracks: List<Track>, src: Int): List<Track> {
        var changed = false
        synchronized(map) {
            load(c)
            for (t in tracks) {
                if (t.source != Track.SOURCE_RADIO || t.remoteId.isBlank()) continue
                if (map.put(t.remoteId, src) != src) changed = true
            }
            if (changed) {
                val sb = StringBuilder()
                for ((k, v) in map) sb.append(k).append('\t').append(v).append('\n')
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, sb.toString()).apply()
            }
        }
        return tracks
    }

    /** Directory the station was found in, or null if unknown (e.g. added before this was recorded). */
    fun of(c: Context, track: Track): Int? {
        if (track.source != Track.SOURCE_RADIO) return null
        synchronized(map) {
            load(c)
            return map[track.remoteId]
        }
    }

    /** Display name of the directory the station of [track] came from, or null if unknown. */
    fun label(c: Context, track: Track): String? = of(c, track)?.let {
        c.getString(
            when (it) {
                RadioApi.SRC_BROWSER -> R.string.radio_origin_browser
                RadioApi.SRC_SOMA -> R.string.radio_src_soma
                RadioApi.SRC_ICECAST -> R.string.radio_src_icecast
                RadioApi.SRC_GARDEN -> R.string.radio_src_garden
                RadioApi.SRC_SHOUTCAST -> R.string.radio_src_shoutcast
                RadioApi.SRC_PARADISE -> R.string.radio_src_paradise
                RadioApi.SRC_COLLECTION -> R.string.radio_origin_collection
                else -> R.string.radio_origin_link
            }
        )
    }
}
