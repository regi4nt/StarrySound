package com.fxsoundlite.app

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Connected to Spotify: copies the account's playlists into StarrySound by itself (they land in the automatic
 * "Spotify" folder of the Playlist tab, see [SpotifyLists]). Each Spotify playlist is imported once: its id is
 * remembered, so a playlist the user deleted never comes back and nothing is duplicated. A playlist whose name
 * matches one that is already imported (e.g. done by hand from a link) is only marked as done.
 *
 * Matching every song to YouTube takes a while, so a run handles at most [BATCH] playlists; the next visit to the
 * Playlist tab continues with the rest. Skipped on data saver.
 */
object SpotifyAutoImport {
    private const val PREFS = "spotify_auto_import"
    private const val KEY_DONE = "done"
    private const val BATCH = 8
    private const val MIN_GAP_MS = 30 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastCheck = 0L
    @Volatile private var busy = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun done(c: Context): Set<String> = HashSet(prefs(c).getStringSet(KEY_DONE, emptySet()) ?: emptySet())

    private fun markDone(c: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        prefs(c).edit().putStringSet(KEY_DONE, done(c) + ids).apply()
    }

    /**
     * Call when the Playlist tab is shown. [onStart] (playlists about to be imported) and [onDone] (result, how many
     * are still waiting) run on the main thread.
     */
    fun maybeRun(c: Context, onStart: (Int) -> Unit, onDone: (SpotifyImport.Result, Int) -> Unit) {
        val app = c.applicationContext
        if (busy || SpotifyImport.running || !SpotifyAuth.isConnected(app) || MusicPrefs.saver(app)) return
        val now = System.currentTimeMillis()
        if (now - lastCheck < MIN_GAP_MS) return
        lastCheck = now
        busy = true
        Thread {
            try {
                val all = try { SpotifyApi.playlists(app) } catch (e: Exception) { lastCheck = 0L; return@Thread } // offline / token: retry next visit
                val known = done(app)
                val have = SpotifyLists.ids(app).mapNotNull { PlaylistStore.get(app, it)?.name }.toSet()
                val (dupes, todo) = all.filter { it.id !in known }.partition { it.name in have }
                markDone(app, dupes.map { it.id })
                val batch = todo.take(BATCH)
                if (batch.isEmpty()) return@Thread
                main.post { onStart(batch.size) }
                val result = try {
                    SpotifyImport.run(app, batch, false, "") { _, _, _ -> }
                } catch (e: Exception) { lastCheck = 0L; return@Thread } // not marked: tried again next time
                markDone(app, batch.map { it.id })
                val left = todo.size - batch.size
                if (left > 0) lastCheck = 0L // continue on the next visit
                main.post { onDone(result, left) }
            } finally {
                busy = false
            }
        }.start()
    }
}
