package com.fxsoundlite.app

import android.content.Context
import java.util.Locale
import kotlin.random.Random

/**
 * Builds a 10-song playlist from what the user likes and plays, entirely on the device (no
 * account, no server-side profile). The taste profile is a weight per artist:
 *   - each liked song            +3 for its artist
 *   - each play (StatsStore)     +1 per play of that artist
 *   - each YouTube history entry +1
 * Songs are then picked from three pools, mixed together:
 *   - YouTube: a search for the top artists (new songs, not already liked),
 *   - Device: songs on the phone by taste artists,
 *   - Fallback: liked songs, YouTube history, then random device songs, so there are
 *     always [SIZE] songs whenever enough exist at all.
 * Blocking: call from a background thread. A failed YouTube search (offline, quota) just
 * means more songs come from the other pools.
 */
object PlaylistGenerator {
    const val SIZE = 10

    private const val TOP_ARTISTS = 4
    private const val YT_PER_ARTIST = 2
    private const val LOCAL_TARGET = 4
    private const val MIN_MS = 60_000L        // skips jingles / intros
    private const val MAX_MS = 9 * 60_000L    // skips mixes, full albums, sleep videos

    class Result(val tracks: List<Track>, val usedTaste: Boolean)

    fun generate(context: Context): Result {
        LastFm.refreshTaste(context) // blocking, only hits the network when the cached taste is stale
        val liked = PlaylistStore.liked(context)
        val history = MusicPrefs.youtubeHistory(context)
        val likedKeys = liked.map { it.key }.toHashSet()

        // ---- taste profile: normalized artist -> (weight, display name)
        val weight = HashMap<String, Int>()
        val display = HashMap<String, String>()
        fun bump(artist: String, by: Int) {
            val n = normArtist(artist)
            if (n.isEmpty()) return
            weight[n] = (weight[n] ?: 0) + by
            display.putIfAbsent(n, cleanArtist(artist))
        }
        liked.forEach { if (it.source != Track.SOURCE_RADIO) bump(it.artist, 3) }
        history.forEach { bump(it.artist, 1) }
        StatsStore.artistCounts(context).forEach { (a, n) -> bump(a, n) }
        TasteSignals.addTo(context) { a, n -> bump(a, n) } // other apps (YouTube Music) + YouTube account likes / subscriptions
        LastFm.cachedTaste(context).artists.forEachIndexed { i, a -> bump(a, maxOf(1, 10 - i / 2)) } // Last.fm top artists, by rank

        val topArtists = weight.entries.sortedByDescending { it.value }.take(TOP_ARTISTS).map { it.key }
        val usedTaste = topArtists.isNotEmpty()

        val picked = LinkedHashMap<String, Track>() // dedupe by title+artist
        fun add(t: Track): Boolean {
            if (picked.size >= SIZE) return false
            val id = normTitle(t.title) + "|" + normArtist(t.artist)
            if (picked.containsKey(id)) return false
            picked[id] = t
            return true
        }

        // ---- 1) YouTube discoveries from the top artists (round-robin so one artist can't dominate)
        val ytPools = topArtists.map { a ->
            try {
                YouTubeApi.search(context, "${display[a]} official audio", maxResults = 15)
                    .filter { it.durationMs in MIN_MS..MAX_MS && it.key !in likedKeys }
                    .filter { normArtist(it.artist).let { c -> c.contains(a) || a.contains(c) || normTitle(it.title).contains(a) } }
                    .toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
        }
        val ytTarget = SIZE - LOCAL_TARGET
        var round = 0
        while (round < YT_PER_ARTIST && picked.size < ytTarget) {
            for (pool in ytPools) {
                if (picked.size >= ytTarget) break
                while (pool.isNotEmpty() && !add(pool.removeAt(0))) { /* skip duplicates */ }
            }
            round++
        }

        // ---- 2) songs on the device by taste artists
        val local: List<Track> =
            if (MusicLibrary.canRead(context)) MusicLibrary.load(context) else emptyList()
        val tasteLocal = local.filter { normArtist(it.artist) in weight }
            .sortedByDescending { weight[normArtist(it.artist)] ?: 0 }
            .groupBy { normArtist(it.artist) }
            .values.flatMap { it.shuffled().take(2) }
            .shuffled()
        tasteLocal.forEach { if (picked.size < SIZE) add(it) }

        // ---- 3) fill up: leftover YouTube hits, liked songs, history, random device songs
        ytPools.flatten().forEach { add(it) }
        liked.filter { it.source != Track.SOURCE_RADIO }.shuffled().forEach { add(it) }
        history.shuffled().forEach { add(it) }
        local.shuffled().forEach { add(it) }

        return Result(picked.values.toList().shuffled(Random(System.nanoTime())), usedTaste)
    }

    /** "Artist - Topic" / "ArtistVEVO" channel names -> plain artist. */
    private fun cleanArtist(s: String): String =
        s.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun normArtist(s: String): String = cleanArtist(s).lowercase(Locale.ROOT)

    private fun normTitle(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[\\(\\[].*?[\\)\\]]"), "").replace(Regex("[^a-z0-9 ]"), "").trim()
}
