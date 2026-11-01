package com.fxsoundlite.app

import android.content.Context
import kotlin.math.abs

/**
 * Copies Spotify playlists into StarrySound playlists. Spotify audio cannot be played outside the Spotify app,
 * so every song is matched to a playable YouTube version (title + artist, closest duration), exactly like the
 * "Convert" feature does for other sources. Songs that cannot be matched are counted and skipped.
 */
object SpotifyImport {
    @Volatile var running = false
        private set

    class Result(val playlists: Int, val songs: Int, val missed: Int)

    /** Blocking (minutes for big libraries): call from a background thread. [progress] gets (done, total, name). */
    fun run(c: Context, picks: List<SpotifyApi.Pl>, includeLiked: Boolean, likedName: String, progress: (Int, Int, String) -> Unit): Result {
        if (running) return Result(0, 0, 0)
        running = true
        try {
            val app = c.applicationContext
            val jobs = ArrayList<Pair<String, List<SpotifyApi.Song>>>()
            val total = picks.size + if (includeLiked) 1 else 0
            if (includeLiked) {
                progress(0, total, likedName)
                try { jobs += likedName to SpotifyApi.likedSongs(app) }
                catch (e: SpotifyApi.ApiException) { if (e.code != 403 && e.code != 404) throw e } // Spotify refused liked songs: still import the playlists
            }
            for ((i, pl) in picks.withIndex()) {
                progress(i + (if (includeLiked) 1 else 0), total, pl.name)
                try { jobs += pl.name to SpotifyApi.playlistSongs(app, pl.id) }
                catch (e: SpotifyApi.ApiException) { if (e.code != 403 && e.code != 404) throw e } // not the user's own playlist
            }
            return convert(app, jobs, progress)
        } finally {
            running = false
        }
    }

    /** Blocking. Copies songs that are already known (read from a public link, no account needed) into one playlist. */
    fun runSongs(c: Context, name: String, songs: List<SpotifyApi.Song>, progress: (Int, Int, String) -> Unit): Result {
        if (running) return Result(0, 0, 0)
        running = true
        try {
            return convert(c.applicationContext, listOf(name to songs), progress)
        } finally {
            running = false
        }
    }

    private fun convert(app: Context, jobs: List<Pair<String, List<SpotifyApi.Song>>>, progress: (Int, Int, String) -> Unit): Result {
            val allSongs = jobs.sumOf { it.second.size }
            var done = 0; var made = 0; var added = 0; var missed = 0
            val cache = HashMap<String, Track?>()
            for ((name, songs) in jobs) {
                val tracks = ArrayList<Track>()
                for (s in songs) {
                    done++
                    progress(done, allSongs, name)
                    val key = (s.artist + "|" + s.title).lowercase()
                    val t = cache.getOrPut(key) { match(s) }
                    if (t != null) tracks += t else missed++
                }
                if (tracks.isNotEmpty()) { PlaylistStore.createWith(app, name, tracks); made++; added += tracks.size }
            }
            return Result(made, added, missed)
    }

    private fun match(s: SpotifyApi.Song): Track? {
        val cands = try { YouTubeApi.lookupSong("${s.artist} ${s.title}".trim()) } catch (_: Exception) { return null }
        Thread.sleep(120) // be gentle with YouTube
        if (cands.isEmpty()) return null
        if (s.durationMs > 0) cands.firstOrNull { it.durationMs > 0 && abs(it.durationMs - s.durationMs) <= 8_000 }?.let { return it }
        return cands.first()
    }
}
