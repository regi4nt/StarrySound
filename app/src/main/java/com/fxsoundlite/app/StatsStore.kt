package com.fxsoundlite.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Listening statistics shown on the Home page. Everything stays on the device (SharedPreferences).
 * [MusicService] feeds it: [addListening] while something plays, [recordPlay] once a song has been
 * heard for [PLAY_THRESHOLD_MS] (so skipping through a list does not count as playing).
 *
 * Besides the lifetime counters it keeps one small bucket per calendar month (minutes, plays, top
 * artists / songs, active days, hour of day, source) -- that is what the "Wrapped" recap
 * (WrappedActivity) is made of. Months are only recorded from the version that added them on.
 */
object StatsStore {
    const val PLAY_THRESHOLD_MS = 30_000L

    private const val PREFS = "stats_prefs"
    private const val KEY_TOTAL = "total_ms"
    private const val KEY_PLAYS = "plays"
    private const val KEY_TODAY_KEY = "today_key"
    private const val KEY_TODAY_MS = "today_ms"
    private const val KEY_ARTISTS = "artists"
    private const val KEY_TRACKS = "tracks"
    private const val MAX_ENTRIES = 150
    private const val MONTH_PREFIX = "m_"
    private const val MAX_MONTHS = 36
    private const val MONTH_ENTRIES = 100
    /** Most songs a playlist made from a Wrapped gets. */
    const val PLAYLIST_SIZE = 100

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    @Synchronized
    fun addListening(c: Context, ms: Long) {
        if (ms <= 0) return
        val prefs = p(c)
        val today = dayKey()
        val todayMs = if (prefs.getString(KEY_TODAY_KEY, "") == today) prefs.getLong(KEY_TODAY_MS, 0L) else 0L
        val edit = prefs.edit()
            .putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0L) + ms)
            .putString(KEY_TODAY_KEY, today)
            .putLong(KEY_TODAY_MS, todayMs + ms)
        addToMonth(prefs, edit, today, ms)
        edit.apply()
    }

    @Synchronized
    fun recordPlay(c: Context, t: Track) {
        val prefs = p(c)
        val edit = prefs.edit().putInt(KEY_PLAYS, prefs.getInt(KEY_PLAYS, 0) + 1)

        // Most played song / station (key -> {t: title, n: count}).
        val tracks = readJson(prefs.getString(KEY_TRACKS, null))
        val cur = tracks.optJSONObject(t.key)
        tracks.put(t.key, JSONObject().put("t", t.title).put("n", (cur?.optInt("n") ?: 0) + 1))
        edit.putString(KEY_TRACKS, prune(tracks).toString())

        // Top artist: radio stations carry "country · codec" there, so they are left out.
        if (t.source != Track.SOURCE_RADIO && t.artist.isNotBlank()) {
            val artists = readJson(prefs.getString(KEY_ARTISTS, null))
            artists.put(t.artist, artists.optInt(t.artist) + 1)
            edit.putString(KEY_ARTISTS, pruneInts(artists).toString())
        }
        recordMonthPlay(prefs, edit, t)
        edit.apply()
        DriveSync.markDirty(c)
    }

    // ------------------------------------------------------------------ monthly buckets (Wrapped)

    private fun monthKey(): String = SimpleDateFormat("yyyyMM", Locale.US).format(Date())

    private fun readMonth(prefs: SharedPreferences, key: String): JSONObject =
        readJson(prefs.getString(MONTH_PREFIX + key, null))

    /** Drops the oldest months once a new one would go over [MAX_MONTHS]. */
    private fun makeRoom(prefs: SharedPreferences, edit: SharedPreferences.Editor, key: String) {
        if (prefs.contains(MONTH_PREFIX + key)) return
        val keys = prefs.all.keys.filter { it.startsWith(MONTH_PREFIX) }.sorted()
        var extra = keys.size + 1 - MAX_MONTHS
        for (k in keys) { if (extra-- <= 0) break; edit.remove(k) }
    }

    private fun addToMonth(prefs: SharedPreferences, edit: SharedPreferences.Editor, today: String, ms: Long) {
        val mk = monthKey()
        makeRoom(prefs, edit, mk)
        val m = readMonth(prefs, mk)
        m.put("ms", m.optLong("ms") + ms)
        val days = m.optJSONObject("days") ?: JSONObject().also { m.put("days", it) }
        days.put(today, days.optLong(today) + ms)
        val hours = m.optJSONArray("hours") ?: JSONArray().also { a -> repeat(24) { a.put(0L) }; m.put("hours", a) }
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hours.put(h, hours.optLong(h) + ms)
        edit.putString(MONTH_PREFIX + mk, m.toString())
    }

    private fun recordMonthPlay(prefs: SharedPreferences, edit: SharedPreferences.Editor, t: Track) {
        val mk = monthKey()
        makeRoom(prefs, edit, mk)
        val m = readMonth(prefs, mk)
        m.put("plays", m.optInt("plays") + 1)
        val artist = if (t.source != Track.SOURCE_RADIO) t.artist else ""
        if (t.source != Track.SOURCE_RADIO) { // stations are counted in plays / sources, but they are not "songs"
            val tracks = m.optJSONObject("tracks") ?: JSONObject()
            val cur = tracks.optJSONObject(t.key)
            // s / r / i / u / d: enough to rebuild the song later (Wrapped -> "Create playlist").
            tracks.put(
                t.key,
                JSONObject().put("t", t.title).put("a", artist).put("n", (cur?.optInt("n") ?: 0) + 1)
                    .put("s", t.source).put("r", t.remoteId).put("i", t.id).put("u", t.artUrl).put("d", t.durationMs)
            )
            m.put("tracks", prune(tracks, MONTH_ENTRIES))
        }
        if (artist.isNotBlank()) {
            val artists = m.optJSONObject("artists") ?: JSONObject()
            artists.put(artist, artists.optInt(artist) + 1)
            m.put("artists", pruneInts(artists, MONTH_ENTRIES))
        }
        val src = m.optJSONObject("src") ?: JSONObject()
        val sk = t.source.toString()
        src.put(sk, src.optInt(sk) + 1)
        m.put("src", src)
        edit.putString(MONTH_PREFIX + mk, m.toString())
    }

    /** Merge of two copies of the same month (two phones): the larger count wins per item. */
    private fun mergeMonth(a: JSONObject, b: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("ms", maxOf(a.optLong("ms"), b.optLong("ms")))
        out.put("plays", maxOf(a.optInt("plays"), b.optInt("plays")))
        fun maxLongs(k: String): JSONObject {
            val o = JSONObject(); val x = a.optJSONObject(k) ?: JSONObject(); val y = b.optJSONObject(k) ?: JSONObject()
            (x.keys().asSequence() + y.keys().asSequence()).toSet().forEach { o.put(it, maxOf(x.optLong(it), y.optLong(it))) }
            return o
        }
        fun maxInts(k: String): JSONObject {
            val o = JSONObject(); val x = a.optJSONObject(k) ?: JSONObject(); val y = b.optJSONObject(k) ?: JSONObject()
            (x.keys().asSequence() + y.keys().asSequence()).toSet().forEach { o.put(it, maxOf(x.optInt(it), y.optInt(it))) }
            return o
        }
        out.put("days", maxLongs("days"))
        out.put("artists", pruneInts(maxInts("artists"), MONTH_ENTRIES))
        out.put("src", maxInts("src"))
        val ha = a.optJSONArray("hours"); val hb = b.optJSONArray("hours")
        val hours = JSONArray()
        for (i in 0 until 24) hours.put(maxOf(ha?.optLong(i) ?: 0L, hb?.optLong(i) ?: 0L))
        out.put("hours", hours)
        val ta = a.optJSONObject("tracks") ?: JSONObject(); val tb = b.optJSONObject("tracks") ?: JSONObject()
        val tracks = JSONObject()
        (ta.keys().asSequence() + tb.keys().asSequence()).toSet().forEach { k ->
            val x = ta.optJSONObject(k); val y = tb.optJSONObject(k)
            tracks.put(k, if (x == null) y else if (y == null) x else if (y.optInt("n") > x.optInt("n")) y else x)
        }
        out.put("tracks", prune(tracks, MONTH_ENTRIES))
        return out
    }

    /** [track] is the playable song (null when the stored entry is too incomplete to rebuild it). */
    class TrackStat(val title: String, val artist: String, val plays: Int, val track: Track? = null)

    /** What the Wrapped screen shows for one month or one year. */
    class Recap(
        val totalMs: Long,
        val plays: Int,
        val activeDays: Int,
        /** "yyyyMMdd" of the day with the most listening, or null. */
        val bestDay: String?,
        val bestDayMs: Long,
        /** 0..23, or -1 when nothing was recorded. */
        val peakHour: Int,
        /** Listening time per hour of the day (24 values, ms). */
        val hours: List<Long>,
        val artists: List<Pair<String, Int>>,
        val artistCount: Int,
        val tracks: List<TrackStat>,
        val trackCount: Int,
        /** The most played songs as playable tracks (up to [PLAYLIST_SIZE]), for "Create playlist". */
        val playlistTracks: List<Track>,
        /** Track.SOURCE_* -> plays. */
        val sources: Map<Int, Int>,
        /** "yyyyMM" with the most listening (only worth showing for a year), or null. */
        val topMonth: String?,
        val topMonthMs: Long
    ) {
        val isEmpty: Boolean get() = totalMs < 60_000L && plays == 0
    }

    private fun buildRecap(months: List<Pair<String, JSONObject>>): Recap {
        var total = 0L
        var plays = 0
        val days = HashMap<String, Long>()
        val hours = LongArray(24)
        val artists = HashMap<String, Int>()
        val tracks = HashMap<String, TrackStat>()
        val sources = HashMap<Int, Int>()
        var topMonth: String? = null
        var topMonthMs = 0L
        for ((key, m) in months) {
            val ms = m.optLong("ms")
            total += ms
            plays += m.optInt("plays")
            if (ms > topMonthMs) { topMonthMs = ms; topMonth = key }
            m.optJSONObject("days")?.let { d -> d.keys().forEach { k -> days[k] = (days[k] ?: 0L) + d.optLong(k) } }
            m.optJSONArray("hours")?.let { h -> for (i in 0 until minOf(24, h.length())) hours[i] += h.optLong(i) }
            m.optJSONObject("artists")?.let { a -> a.keys().forEach { k -> artists[k] = (artists[k] ?: 0) + a.optInt(k) } }
            m.optJSONObject("tracks")?.let { tr ->
                tr.keys().forEach { k ->
                    val o = tr.optJSONObject(k) ?: return@forEach
                    val local = k.startsWith("L")
                    val src = o.optInt("s", if (local) Track.SOURCE_LOCAL else k.substringBefore(':').toIntOrNull() ?: Track.SOURCE_LOCAL)
                    if (src == Track.SOURCE_RADIO) return@forEach
                    val rid = o.optString("r").ifBlank { if (local) "" else k.substringAfter(':') }
                    val lid = o.optLong("i", if (local) k.drop(1).toLongOrNull() ?: 0L else 0L)
                    val valid = if (src == Track.SOURCE_LOCAL) lid > 0L else rid.isNotBlank()
                    val song = if (!valid) null else Track(
                        id = lid, title = o.optString("t"), artist = o.optString("a"), album = "",
                        durationMs = o.optLong("d"), source = src, remoteId = rid, artUrl = o.optString("u")
                    )
                    val old = tracks[k]
                    tracks[k] = TrackStat(o.optString("t"), o.optString("a"), (old?.plays ?: 0) + o.optInt("n"), song ?: old?.track)
                }
            }
            m.optJSONObject("src")?.let { sr -> sr.keys().forEach { k -> k.toIntOrNull()?.let { s -> sources[s] = (sources[s] ?: 0) + sr.optInt(k) } } }
        }
        val rankedTracks = tracks.values.sortedWith(compareByDescending<TrackStat> { it.plays }.thenBy { it.title })
        val best = days.entries.maxByOrNull { it.value }
        val peak = hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0L } ?: -1
        return Recap(
            totalMs = total,
            plays = plays,
            activeDays = days.values.count { it >= 60_000L },
            bestDay = best?.takeIf { it.value > 0L }?.key,
            bestDayMs = best?.value ?: 0L,
            peakHour = peak,
            hours = hours.toList(),
            artists = artists.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(5).map { it.key to it.value },
            artistCount = artists.size,
            tracks = rankedTracks.take(5),
            trackCount = tracks.size,
            playlistTracks = rankedTracks.mapNotNull { it.track }.take(PLAYLIST_SIZE),
            sources = sources,
            topMonth = topMonth,
            topMonthMs = topMonthMs
        )
    }

    /** "yyyyMM" of every month that has data, newest first. */
    fun monthKeys(c: Context): List<String> =
        p(c).all.keys.filter { it.startsWith(MONTH_PREFIX) }.map { it.removePrefix(MONTH_PREFIX) }.sortedDescending()

    fun currentMonthKey(): String = monthKey()

    fun recapForMonth(c: Context, key: String): Recap =
        buildRecap(listOf(key to readMonth(p(c), key)))

    fun recapForYear(c: Context, year: Int): Recap {
        val prefs = p(c)
        val prefix = String.format(Locale.US, "%04d", year)
        return buildRecap(monthKeys(c).filter { it.startsWith(prefix) }.map { it to readMonth(prefs, it) })
    }

    // ------------------------------------------------------------------ Google Drive sync

    /** Lifetime counters for the Drive backup (today's minutes stay on the device). */
    @Synchronized
    fun exportJson(c: Context): JSONObject {
        val prefs = p(c)
        val months = JSONObject()
        monthKeys(c).forEach { months.put(it, readMonth(prefs, it)) }
        return JSONObject()
            .put("months", months)
            .put("total", prefs.getLong(KEY_TOTAL, 0L))
            .put("plays", prefs.getInt(KEY_PLAYS, 0))
            .put("artists", readJson(prefs.getString(KEY_ARTISTS, null)))
            .put("tracks", readJson(prefs.getString(KEY_TRACKS, null)))
    }

    /** Applies a Drive snapshot; merge keeps the larger count per item. Never marks data as changed. */
    @Synchronized
    fun importJson(c: Context, o: JSONObject, merge: Boolean) {
        val prefs = p(c)
        val rArtists = o.optJSONObject("artists") ?: JSONObject()
        val rTracks = o.optJSONObject("tracks") ?: JSONObject()
        var total = o.optLong("total")
        var plays = o.optInt("plays")
        var artists = rArtists
        var tracks = rTracks
        if (merge) {
            total = maxOf(total, prefs.getLong(KEY_TOTAL, 0L))
            plays = maxOf(plays, prefs.getInt(KEY_PLAYS, 0))
            artists = readJson(prefs.getString(KEY_ARTISTS, null))
            rArtists.keys().forEach { k -> artists.put(k, maxOf(artists.optInt(k), rArtists.optInt(k))) }
            tracks = readJson(prefs.getString(KEY_TRACKS, null))
            rTracks.keys().forEach { k ->
                val r = rTracks.optJSONObject(k) ?: return@forEach
                val l = tracks.optJSONObject(k)
                if (l == null || r.optInt("n") > l.optInt("n")) tracks.put(k, r)
            }
        }
        prefs.edit()
            .putLong(KEY_TOTAL, total).putInt(KEY_PLAYS, plays)
            .putString(KEY_ARTISTS, pruneInts(artists).toString())
            .putString(KEY_TRACKS, prune(tracks).toString())
            .apply()

        // Monthly buckets (older snapshots have none: then the local ones stay as they are).
        o.optJSONObject("months")?.let { remote ->
            val edit = prefs.edit()
            if (!merge) prefs.all.keys.filter { it.startsWith(MONTH_PREFIX) }.forEach { edit.remove(it) }
            remote.keys().forEach { k ->
                val r = remote.optJSONObject(k) ?: return@forEach
                val out = if (merge) mergeMonth(readMonth(prefs, k), r) else r
                edit.putString(MONTH_PREFIX + k, out.toString())
            }
            edit.apply()
        }
    }

    private fun readJson(raw: String?): JSONObject = try { JSONObject(raw ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Keeps the storage small: drops the least played entries once there are too many. */
    private fun prune(o: JSONObject, max: Int = MAX_ENTRIES): JSONObject {
        if (o.length() <= max) return o
        val entries = o.keys().asSequence().map { it to (o.optJSONObject(it)?.optInt("n") ?: 0) }.sortedByDescending { it.second }
        val out = JSONObject()
        entries.take(max).forEach { (k, _) -> out.put(k, o.getJSONObject(k)) }
        return out
    }

    private fun pruneInts(o: JSONObject, max: Int = MAX_ENTRIES): JSONObject {
        if (o.length() <= max) return o
        val entries = o.keys().asSequence().map { it to o.optInt(it) }.sortedByDescending { it.second }
        val out = JSONObject()
        entries.take(max).forEach { (k, n) -> out.put(k, n) }
        return out
    }

    fun totalMs(c: Context): Long = p(c).getLong(KEY_TOTAL, 0L)

    fun todayMs(c: Context): Long {
        val prefs = p(c)
        return if (prefs.getString(KEY_TODAY_KEY, "") == dayKey()) prefs.getLong(KEY_TODAY_MS, 0L) else 0L
    }

    fun plays(c: Context): Int = p(c).getInt(KEY_PLAYS, 0)

    /** Plays per artist (as stored by [recordPlay]); empty while there is no data. */
    fun artistCounts(c: Context): Map<String, Int> {
        val o = readJson(p(c).getString(KEY_ARTISTS, null))
        return o.keys().asSequence().associateWith { o.optInt(it) }
    }

    /** The artist played most often, or null while there is no data. */
    fun topArtist(c: Context): String? {
        val o = readJson(p(c).getString(KEY_ARTISTS, null))
        return o.keys().asSequence().maxByOrNull { o.optInt(it) }
    }

    /** Title of the most played song / station, or null while there is no data. */
    fun topTrack(c: Context): String? {
        val o = readJson(p(c).getString(KEY_TRACKS, null))
        return o.keys().asSequence()
            .maxByOrNull { o.optJSONObject(it)?.optInt("n") ?: 0 }
            ?.let { o.optJSONObject(it)?.optString("t") }
            ?.takeIf { it.isNotBlank() }
    }
}
