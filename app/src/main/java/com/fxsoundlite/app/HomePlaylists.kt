package com.fxsoundlite.app

import android.content.Context
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import java.io.IOException
import java.util.Calendar
import java.util.Locale

/**
 * "Recommended playlists" and "Popular playlists" shelves of the Home page. Playlists are found with
 * the key-less NewPipe extractor (YouTube playlist search, no account, no API quota); a playlist's
 * songs are only fetched when one is tapped (first page only, see [tracks]).
 *
 *  - Recommended: built from the user's taste (top artists of likes / plays / history, like [QuickFeed]);
 *    with no taste yet, a few moods that rotate by day.
 *  - Popular: what YouTube ranks first for current hits: 5 for the device's country (Indonesian when the device language is), 5 worldwide.
 *
 * Blocking: call from a background thread. Data saver mode runs one search per shelf instead of several.
 */
object HomePlaylists {

    data class Item(val listId: String, val name: String, val owner: String, val count: Long, val artUrl: String)

    private val LIST_RE = Regex("[?&]list=([A-Za-z0-9_-]{10,60})")
    private val BROWSE_PREFIX = Regex("^VL(?=(PL|OL|RD|UU|LL|FL))")
    private val BAD = Regex(
        "(?i)\\b(karaoke|1 hour|one hour|10 hours|sleep|asmr|reaction|podcast|lecture|tutorial|full album|non-?stop|audiobook|sermon|ceramah)\\b"
    )
    private const val MIN_SONGS = 8L
    private const val MAX_SONGS = 400L
    private const val PER_QUERY = 5
    private const val SHELF_SIZE = 10
    private const val TRACKS_PLAYED = 50
    private const val TRACKS_PLAYED_SAVER = 30

    private val MOODS = listOf(
        "chill music playlist", "workout music playlist", "focus study music playlist",
        "road trip songs playlist", "relaxing acoustic playlist", "feel good hits playlist"
    )

    // ------------------------------------------------------------------ shelves

    fun recommended(context: Context): List<Item> {
        val saver = MusicPrefs.saver(context)
        val queries = ArrayList<String>()
        taste(context).take(if (saver) 1 else 2).forEach { queries += "$it best songs playlist" }
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val moodCount = if (saver) 1 else 3 - queries.size.coerceAtMost(2) // taste first, moods fill the rest
        repeat(moodCount.coerceAtLeast(1)) { i -> queries += MOODS[(day + i) % MOODS.size] }
        if (isIndonesian()) queries += "playlist santai indonesia"
        return merge(queries.take(if (saver) 1 else 4))
    }

    /** Popular playlists: 5 for the device's country (Indonesian searches when the language is) + 5 worldwide. */
    fun popular(context: Context): List<Item> {
        val saver = MusicPrefs.saver(context)
        val y = Calendar.getInstance().get(Calendar.YEAR)
        val localQ = ArrayList<String>()
        if (isIndonesian()) {
            localQ += "playlist lagu indonesia terpopuler $y"
            if (!saver) localQ += "lagu indonesia hits $y playlist"
        } else {
            Locale.getDefault().getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() }
                ?.let { localQ += "top hits $it $y playlist" }
        }
        val intlQ = ArrayList<String>()
        intlQ += "top hits $y playlist"
        if (!saver) intlQ += "most streamed songs playlist"
        var failure: IOException? = null
        fun safe(q: List<String>): List<Item> =
            if (q.isEmpty()) emptyList() else try { merge(q) } catch (e: IOException) { failure = e; emptyList() }
        val local = safe(localQ)
        val intl = safe(intlQ)
        if (local.isEmpty() && intl.isEmpty()) throw failure ?: IOException("no playlists")
        val seen = HashSet<String>()
        val out = ArrayList<Item>()
        out += local.take(5).filter { seen.add(it.listId) }
        out += intl.asSequence().filter { seen.add(it.listId) }.take(5).toList()
        (local + intl).forEach { if (out.size < SHELF_SIZE && seen.add(it.listId)) out += it }
        return out
    }

    /**
     * The first songs of a playlist (one page of the playlist, never the whole list), to be played.
     * Throws [IOException] when offline, [IllegalStateException] when nothing playable came back.
     */
    fun tracks(context: Context, listId: String): List<Track> {
        val service = ServiceList.YouTube
        val url = "https://www.youtube.com/playlist?list=$listId"
        val info = try {
            PlaylistInfo.getInfo(service, url)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "playlist failed")
        }
        val max = if (MusicPrefs.saver(context)) TRACKS_PLAYED_SAVER else TRACKS_PLAYED
        val noShorts = MusicPrefs.ytBlockShorts(context)
        val out = ArrayList<Track>()
        for (item in info.relatedItems) {
            val id = PlaylistIo.youtubeVideoId(item.url.orEmpty()) ?: continue
            if (item.duration <= 0) continue // deleted / private / live
            if (noShorts && (item.duration <= 60 || item.name.orEmpty().contains("#short", ignoreCase = true))) continue
            out += Track(
                id = 0, title = item.name.orEmpty(), artist = item.uploaderName.orEmpty(), album = "",
                durationMs = item.duration * 1000, source = Track.SOURCE_YOUTUBE, remoteId = id,
                artUrl = item.thumbnails.firstOrNull()?.url.orEmpty().ifBlank { "https://i.ytimg.com/vi/$id/mqdefault.jpg" }
            )
            if (out.size >= max) break
        }
        val kept = if (noShorts) YouTubeApi.dropShorts(out.distinctBy { it.key }) else out.distinctBy { it.key }
        if (kept.isEmpty()) throw IllegalStateException("empty playlist")
        return kept
    }

    // ------------------------------------------------------------------ internals

    private fun isIndonesian(): Boolean = Locale.getDefault().language.let { it == "in" || it == "id" }

    /** Top artists of the user's taste (like +3, YouTube history +1, each play +1), best first. */
    private fun taste(context: Context): List<String> {
        val weight = HashMap<String, Int>()
        val display = HashMap<String, String>()
        fun bump(artist: String, by: Int) {
            val shown = QuickFeed.cleanArtist(artist)
            if (shown.isEmpty() || shown.startsWith("<")) return
            val key = shown.lowercase(Locale.ROOT)
            weight[key] = (weight[key] ?: 0) + by
            display.putIfAbsent(key, shown)
        }
        PlaylistStore.liked(context).forEach { if (it.source != Track.SOURCE_RADIO) bump(it.artist, 3) }
        MusicPrefs.youtubeHistory(context).forEach { bump(it.artist, 1) }
        StatsStore.artistCounts(context).forEach { (a, n) -> bump(a, n) }
        TasteSignals.addTo(context) { a, n -> bump(a, n) } // other apps (YouTube Music) + YouTube account likes / subscriptions
        return weight.entries.sortedByDescending { it.value }.take(4).map { display.getValue(it.key) }
    }

    /** Runs the searches in order, interleaves their results and drops duplicates. A failing query is skipped. */
    private fun merge(queries: List<String>): List<Item> {
        val lists = ArrayList<List<Item>>()
        var failure: IOException? = null
        for (q in queries) {
            try {
                lists += search(q).take(PER_QUERY)
            } catch (e: IOException) {
                failure = e
            }
        }
        if (lists.all { it.isEmpty() } && failure != null) throw failure
        val seen = HashSet<String>()
        val out = ArrayList<Item>()
        var i = 0
        while (lists.any { i < it.size } && out.size < SHELF_SIZE) {
            for (l in lists) if (i < l.size && out.size < SHELF_SIZE && seen.add(l[i].listId)) out += l[i]
            i++
        }
        return out
    }

    private fun search(query: String): List<Item> {
        val service = ServiceList.YouTube
        for (filter in listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS, YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS)) {
            val out = ArrayList<Item>()
            try {
                val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
                val info = SearchInfo.getInfo(service, handler)
                for (item in info.relatedItems) {
                    if (item !is PlaylistInfoItem) continue
                    val raw = LIST_RE.find(item.url.orEmpty())?.groupValues?.get(1) ?: continue
                    val id = raw.replace(BROWSE_PREFIX, "")
                    val name = item.name.orEmpty().trim()
                    val n = item.streamCount // -1 when YouTube does not say
                    if (name.isEmpty() || BAD.containsMatchIn(name)) continue
                    if (n in 0 until MIN_SONGS || n > MAX_SONGS) continue
                    out += Item(id, name, item.uploaderName.orEmpty().trim(), n, item.thumbnails.firstOrNull()?.url.orEmpty())
                }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                // this filter did not work (YouTube change): try the next one
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }
}
