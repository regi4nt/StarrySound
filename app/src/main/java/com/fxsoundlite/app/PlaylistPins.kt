package com.fxsoundlite.app

import android.content.Context

/**
 * Pinned playlists on the Playlist page (like Spotify): at most [MAX] rows stay on top of the list.
 * Works for own, YouTube and Drive playlists. "Liked songs" is always first and cannot be pinned.
 * Pinned rows sit right below it and above the last played playlist.
 * Stored on this device only, as an ordered list of row ids (the first pinned stays first).
 */
object PlaylistPins {
    const val MAX = 4

    private const val PREFS = "playlist_pins"
    private const val KEY = "ids"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pinned row ids in the order they were pinned (never more than [MAX]). */
    @Synchronized
    fun ids(c: Context): List<String> {
        val raw = p(c).getString(KEY, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() && it != PlaylistStore.LIKED_ID }.distinct().take(MAX)
    }

    fun isPinned(c: Context, id: String): Boolean = id in ids(c)

    fun count(c: Context): Int = ids(c).size

    private fun save(c: Context, list: List<String>) {
        p(c).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    /** Pins [id] at the end of the pinned rows. Returns false when [MAX] rows are already pinned. */
    @Synchronized
    fun pin(c: Context, id: String): Boolean {
        if (id == PlaylistStore.LIKED_ID) return true // "Liked songs" is always on top, no pin needed
        val cur = ids(c)
        if (id in cur) return true
        if (cur.size >= MAX) return false
        save(c, cur + id)
        return true
    }

    @Synchronized
    fun unpin(c: Context, id: String) {
        val cur = ids(c)
        if (id in cur) save(c, cur.filter { it != id })
    }

    /** Drops pins whose playlist is gone, so a deleted playlist never keeps a pin slot. */
    @Synchronized
    fun retain(c: Context, alive: (String) -> Boolean) {
        val cur = ids(c)
        val keep = cur.filter(alive)
        if (keep.size != cur.size) save(c, keep)
    }
}
