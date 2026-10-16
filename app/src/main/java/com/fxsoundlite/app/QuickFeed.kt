package com.fxsoundlite.app

import android.content.Context
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Song source of the Quick tab (reels-style chorus previews). Entirely on the device, no account.
 *
 * "Recommendations according to YouTube" means two things here, mixed together on every refresh:
 *  - Taste: YouTube's own "related videos" for a few random songs the user liked / played, plus
 *    searches for the user's top artists (same taste profile idea as [PlaylistGenerator]);
 *  - Discovery: random popular-music searches, so there is always something new, also for a
 *    brand-new install that has no taste yet.
 * Songs already shown recently (see [markSeen], persisted) are skipped, so each refresh gives
 * different music.
 *
 * Searching uses the key-less NewPipe extractor first (no YouTube API quota is spent); the API
 * key / search from [YouTubeApi] is only a fallback if the extractor fails.
 */
object QuickFeed {

    /** Length of the chorus preview. */
    const val CLIP_MS = 25_000L

    private const val MIN_MS = 90_000L          // skips jingles / intros
    private const val SHORTS_MAX_MS = 3 * 60_000L   // YouTube Shorts can last up to 3 minutes
    private const val MAX_MS = 7 * 60_000L      // skips mixes, albums, sleep videos
    private const val PER_SOURCE = 4            // max songs taken from one search / related list
    private const val PREFS = "quick_prefs"
    private const val KEY_SEEN = "seen_ids"
    private const val SEEN_MAX = 300

    private val BAD_TITLE = Regex(
        "(?i)\\b(full album|compilation|non-?stop|1 hour|one hour|playlist|karaoke|reaction|tutorial|podcast|interview|trailer|" +
            "episode|eps\\.?|vlog|review|unboxing|asmr|mukbang|gameplay|news|highlights?|behind the scenes|making of|teaser|" +
            "live ?stream|shorts|talk ?show|documentary|reacts?|storytime|prank|challenge|q&a|lecture|audiobook|sermon|ceramah)\\b|#shorts"
    )

    /**
     * Where the chorus most likely starts. YouTube exposes no "chorus" marker, so this is an
     * estimate: pop songs usually reach their first chorus about a quarter of the way in.
     */
    fun chorusStartMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 45_000L
        val latest = (durationMs - CLIP_MS - 5_000L).coerceAtLeast(0L)
        val guess = (durationMs * 0.27).toLong().coerceAtLeast(30_000L)
        return guess.coerceAtMost(latest) / 1000L * 1000L
    }

    // ------------------------------------------------------------------ seen ids

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun seenList(c: Context): List<String> =
        prefs(c).getString(KEY_SEEN, "").orEmpty().split(',').filter { it.isNotBlank() }

    fun seen(c: Context): Set<String> = seenList(c).toHashSet()

    /** Remembers [ids] (video ids) as shown; the oldest are forgotten after [SEEN_MAX]. */
    @Synchronized
    fun markSeen(c: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val merged = (seenList(c).filter { it !in ids } + ids).takeLast(SEEN_MAX)
        prefs(c).edit().putString(KEY_SEEN, merged.joinToString(",")).apply()
    }

    // ------------------------------------------------------------------ generation

    /**
     * Blocking: call from a background thread. Calls [onChunk] (from that thread) every time a
     * source produced new songs, so the first reel can start playing after the very first
     * search instead of waiting for all of them. Stops after about [target] songs, or as soon as
     * [cancelled] returns true. A source that fails (offline, YouTube change) is simply skipped.
     */
    fun generate(
        context: Context,
        exclude: Set<String>,
        target: Int,
        cancelled: () -> Boolean,
        onChunk: (List<Track>) -> Unit
    ) {
        val rnd = Random(System.nanoTime())
        val used = HashSet<String>(exclude)
        var delivered = 0
        val leftovers = ArrayList<Track>() // good songs that were only skipped for being seen before

        // Two searches run at the same time: the first reel starts after whichever answers first, and a
        // slow or failing source no longer holds everything behind it. Sources keep their order of priority.
        val sources = buildSources(context, rnd)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val cs = ExecutorCompletionService<List<Track>>(pool)
            var submitted = 0
            var finished = 0
            fun submitNext() {
                if (submitted < sources.size) {
                    val source = sources[submitted++]
                    cs.submit { try { source() } catch (e: Exception) { emptyList<Track>() } }
                }
            }
            submitNext(); submitNext()
            while (finished < submitted) {
                if (cancelled() || delivered >= target) break
                val found = try { cs.take().get() } catch (e: Exception) { emptyList<Track>() }
                finished++
                submitNext()
                val good = found.filter { ok(it) }.distinctBy { it.remoteId }
                val fresh = good.filter { it.remoteId !in used }.shuffled(rnd).take(PER_SOURCE)
                leftovers.addAll(good.filter { it.remoteId in exclude })
                if (fresh.isEmpty()) continue
                fresh.forEach { used.add(it.remoteId) }
                delivered += fresh.size
                if (!cancelled()) onChunk(fresh)
            }
        } finally {
            pool.shutdownNow()
        }
        // Everything found had been shown before (tiny result sets): better a repeat than a blank page.
        if (delivered == 0 && !cancelled() && leftovers.isNotEmpty()) {
            onChunk(leftovers.distinctBy { it.remoteId }.shuffled(rnd).take(target))
        }
    }

    private fun ok(t: Track): Boolean =
        t.source == Track.SOURCE_YOUTUBE && t.remoteId.isNotBlank() &&
            t.durationMs in MIN_MS..MAX_MS && !BAD_TITLE.containsMatchIn(t.title)

    /** Sources are tried in this (interleaved) order: taste, taste, discovery, taste, ... */
    private fun buildSources(context: Context, rnd: Random): List<() -> List<Track>> {
        val liked = PlaylistStore.liked(context)
        val history = MusicPrefs.youtubeHistory(context)

        // Artist weights: like +3, YouTube history +1, each play +1 (same idea as PlaylistGenerator).
        val weight = HashMap<String, Int>()
        val display = HashMap<String, String>()
        fun bump(artist: String, by: Int) {
            val shown = cleanArtist(artist)
            if (shown.isEmpty() || shown.startsWith("<")) return // "<unknown>" from MediaStore
            val key = shown.lowercase(Locale.ROOT)
            weight[key] = (weight[key] ?: 0) + by
            display.putIfAbsent(key, shown)
        }
        liked.forEach { if (it.source != Track.SOURCE_RADIO) bump(it.artist, 3) }
        history.forEach { bump(it.artist, 1) }
        StatsStore.artistCounts(context).forEach { (a, n) -> bump(a, n) }
        TasteSignals.addTo(context) { a, n -> bump(a, n) } // other apps (YouTube Music) + YouTube account likes / subscriptions

        // A random 3 of the top 8 artists, so refreshes do not keep circling the same ones.
        val artists = weight.entries.sortedByDescending { it.value }.take(8)
            .map { display.getValue(it.key) }.shuffled(rnd).take(3)
        val seeds = (liked + history).filter { it.source == Track.SOURCE_YOUTUBE && it.remoteId.isNotBlank() }
            .distinctBy { it.remoteId }.shuffled(rnd).take(2)
        val discovery = discoveryQueries(rnd).take(if (artists.isEmpty() && seeds.isEmpty()) 5 else 3)

        val taste = ArrayList<() -> List<Track>>()
        for (i in 0 until maxOf(artists.size, seeds.size)) {
            seeds.getOrNull(i)?.let { s -> taste.add { related(context, s) } }
            artists.getOrNull(i)?.let { a -> taste.add { search(context, "$a official audio") } }
        }
        val disc = discovery.map { q -> { search(context, q) } }

        val out = ArrayList<() -> List<Track>>()
        val ti = taste.iterator()
        val di = disc.iterator()
        while (ti.hasNext() || di.hasNext()) {
            if (ti.hasNext()) out.add(ti.next())
            if (ti.hasNext()) out.add(ti.next())
            if (di.hasNext()) out.add(di.next())
        }
        return out
    }

    private fun discoveryQueries(rnd: Random): List<String> {
        val y = Calendar.getInstance().get(Calendar.YEAR)
        val global = listOf(
            "top hits $y", "new pop songs $y", "viral songs $y", "best r&b songs", "rock hits",
            "hip hop hits $y", "edm hits $y", "acoustic songs", "indie pop songs", "k-pop hits $y",
            "latin pop hits", "chill songs", "country hits", "90s hits", "2000s hits", "80s hits"
        )
        val indo = listOf(
            "lagu indonesia terbaru $y", "lagu viral tiktok $y", "lagu pop indonesia terpopuler",
            "lagu galau indonesia", "lagu indie indonesia", "lagu santai"
        )
        val lang = Locale.getDefault().language
        val pool = if (lang == "in" || lang == "id") indo + indo + global else global
        return pool.shuffled(rnd).distinct()
    }

    // ------------------------------------------------------------------ sources

    /**
     * Songs only: YouTube Music's "Songs" search (so no podcasts, vlogs or creator videos). Key-less
     * (no quota). If that search fails, a normal video search in the Music category is the fallback;
     * [ok] still filters its results by length and title.
     */
    fun search(context: Context, query: String): List<Track> {
        val max = if (MusicPrefs.saver(context)) 10 else 20
        try {
            val songs = YouTubeApi.searchExtractor(
                query, false, max,
                org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
            )
            if (songs.isNotEmpty()) return songs
        } catch (e: Exception) { /* fall through to the video search */ }
        // Plain video search: this is where Shorts can slip in, so "Block Shorts" (Settings) applies here too.
        // Shorts last up to 3 minutes and are not always flagged, so with the switch on, videos of 3 minutes
        // or less are dropped from this fallback (the Songs search above is not affected).
        val noShorts = MusicPrefs.ytBlockShorts(context)
        val opts = if (noShorts) YouTubeApi.Opts(null, false, true) else null
        val videos = try {
            YouTubeApi.searchExtractor(query, false, max, opts = opts)
        } catch (e: Exception) {
            YouTubeApi.search(context, query, maxResults = max, opts = opts)
        }
        return if (noShorts) videos.filter { it.durationMs > SHORTS_MAX_MS } else videos
    }

    /**
     * "More like this" for [seed]: YouTube's related list mixes in every kind of video, so instead
     * the Songs search is asked for music like the seed (same artist / same title).
     */
    private fun related(context: Context, seed: Track): List<Track> =
        search(context, cleanArtist(seed.artist) + " " + seed.title).filter { it.remoteId != seed.remoteId }

    /** "Artist - Topic" / "ArtistVEVO" channel names -> plain artist. */
    fun cleanArtist(s: String): String =
        s.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "")
            .trim()
}
