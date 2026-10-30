package com.fxsoundlite.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** The sources inside the Online tab, in the order of the source chips. */
enum class OnlineSource(val labelRes: Int) {
    ARCHIVE(R.string.online_src_archive),
    AUDIUS(R.string.online_src_audius),
    JAMENDO(R.string.online_src_jamendo),
    CCMIXTER(R.string.online_src_ccmixter)
}

/** Filters sent with a search. [genre] is one of [OnlineApi.GENRES] (null = any). */
data class OnlineFilter(val genre: String? = null, val losslessOnly: Boolean = false, val commercialOnly: Boolean = false)

/** A row in the Online tab: an Internet Archive item (opens to its songs) or a ready-to-play song. */
sealed class OnlineResult {
    data class Album(val item: ArchiveItem) : OnlineResult()
    data class Song(val track: Track) : OnlineResult()
}

/**
 * Searches one [OnlineSource]. All songs come back as [Track.SOURCE_ONLINE]: [Track.remoteId] is a plain https
 * audio link that MediaPlayer plays (and can seek in). Blocking: call from a background thread.
 *
 *  - Internet Archive: free public audio, FLAC / MP3 by the audio-quality setting (see [ArchiveApi]).
 *  - Audius: open API, full tracks by independent artists, no key (just an app name).
 *  - Jamendo: full Creative Commons / independent tracks. Needs a free client id ([OnlineKeys]); see [needsKey].
 *  - ccMixter: Creative Commons remixes and samples, no key.
 */
object OnlineApi {

    private const val APP_NAME = "StarrySound"
    /** Genre chips: search keyword per genre (also sent as Jamendo tag). */
    val GENRES = listOf("pop", "rock", "electronic", "hip hop", "jazz", "classical", "ambient", "folk")
    private const val LIMIT = 30
    private const val LIMIT_SAVER = 15

    /** True when [source] cannot search until the user adds a (free) key in Settings. */
    fun needsKey(context: Context, source: OnlineSource): Boolean =
        source == OnlineSource.JAMENDO && OnlineKeys.jamendo(context).isBlank()

    @Throws(IOException::class)
    fun search(context: Context, source: OnlineSource, query: String, filter: OnlineFilter = OnlineFilter()): List<OnlineResult> {
        linkResults(context, query)?.let { return it } // a pasted link wins over the chosen source
        return searchSource(context, source, query, filter)
    }

    private fun searchSource(context: Context, source: OnlineSource, query: String, filter: OnlineFilter): List<OnlineResult> {
        // The genre is just another search word, except on Jamendo which has a real tag filter.
        val q = (if (source == OnlineSource.JAMENDO) query else listOf(query, filter.genre.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")).trim()
        val n = if (MusicPrefs.saver(context)) LIMIT_SAVER else LIMIT
        return when (source) {
            OnlineSource.ARCHIVE -> archiveSongs(context, q, filter.losslessOnly, n).map { OnlineResult.Song(it) }
            OnlineSource.AUDIUS -> audius(q, n).map { OnlineResult.Song(it) }
            OnlineSource.JAMENDO -> jamendo(context, q, n, filter.genre).map { OnlineResult.Song(it) }
            OnlineSource.CCMIXTER -> ccmixter(q, n).map { OnlineResult.Song(it) }
        }
    }

    /**
     * Searches every source at once (no source chosen) and merges the answers: one song from each source in
     * turn, so no source buries the others, without repeats of the same title + artist. Jamendo is skipped
     * while it has no key; a source that fails is skipped too. Only when every source fails does it throw.
     */
    @Throws(IOException::class)
    fun searchAll(
        context: Context, query: String, filter: OnlineFilter = OnlineFilter(),
        onPartial: ((List<OnlineResult>) -> Unit)? = null
    ): List<OnlineResult> {
        linkResults(context, query)?.let { return it }
        val sources = OnlineSource.values().filter { !needsKey(context, it) }
        if (sources.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(sources.size)
        val answers = arrayOfNulls<Result<List<OnlineResult>>>(sources.size)
        try {
            val cs = java.util.concurrent.ExecutorCompletionService<Pair<Int, Result<List<OnlineResult>>>>(pool)
            sources.forEachIndexed { i, src -> cs.submit { i to runCatching { searchSource(context, src, query, filter) } } }
            val deadline = android.os.SystemClock.elapsedRealtime() + 25_000L
            var done = 0
            while (done < sources.size) {
                val left = deadline - android.os.SystemClock.elapsedRealtime()
                if (left <= 0) break
                val (i, r) = (cs.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break).get() // too slow = left out
                answers[i] = r
                done++
                // Show what is in already instead of waiting for the slowest source.
                if (onPartial != null && r.getOrNull()?.isNotEmpty() == true) {
                    val part = mergeInterleaved(answers.mapNotNull { it?.getOrNull() })
                    if (part.isNotEmpty()) onPartial(part)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        val lists = answers.mapNotNull { it?.getOrNull() }
        if (lists.isEmpty()) throw (answers.firstNotNullOfOrNull { it?.exceptionOrNull() } as? IOException ?: IOException("No source answered"))
        return mergeInterleaved(lists)
    }

    private fun mergeInterleaved(lists: List<List<OnlineResult>>): List<OnlineResult> {
        val seen = HashSet<String>()
        val out = ArrayList<OnlineResult>()
        var i = 0
        while (lists.any { i < it.size }) {
            for (l in lists) {
                val r = l.getOrNull(i) ?: continue
                val key = when (r) {
                    is OnlineResult.Song -> r.track.title.trim().lowercase() + "|" + r.track.artist.trim().lowercase()
                    is OnlineResult.Album -> "ia:" + r.item.identifier
                }
                if (seen.add(key)) out += r
            }
            i++
        }
        return out
    }

    /**
     * Internet Archive finds albums; the list should show songs like the YouTube tab does, so the songs of the
     * first few matches are fetched (in parallel, a few per album so one big album cannot fill the list).
     */
    private fun archiveSongs(context: Context, q: String, losslessOnly: Boolean, n: Int): List<Track> {
        val items = ArchiveApi.search(context, q, losslessOnly).take(if (n <= LIMIT_SAVER) 4 else 8)
        if (items.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs = items.map { item ->
                java.util.concurrent.Callable { runCatching { ArchiveApi.tracks(context, item).take(5) }.getOrDefault(emptyList()) }
            }
            return pool.invokeAll(jobs, 20, java.util.concurrent.TimeUnit.SECONDS)
                .flatMap { f -> runCatching { f.get() }.getOrDefault(emptyList()) }
                .take(n)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ Jamendo

    private fun jamendo(context: Context, q: String, n: Int, genre: String?): List<Track> {
        val key = OnlineKeys.jamendo(context)
        if (key.isBlank()) throw IOException("Jamendo key missing")
        val high = StreamQuality.effective(context) == StreamQuality.Level.HIGH
        val url = "https://api.jamendo.com/v3.0/tracks/?client_id=${enc(key)}&format=json&limit=$n" +
            "&audioformat=" + (if (high) "mp32" else "mp31") + "&order=popularity_total&include=musicinfo" +
            (if (q.isEmpty()) "" else "&search=${enc(q)}") + (if (genre.isNullOrEmpty()) "" else "&tags=${enc(genre.replace(" ", ""))}")
        val data = JSONObject(ArchiveApi.get(url)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Track>()
        for (i in 0 until data.length()) jamendoTrack(data.optJSONObject(i), high)?.let { out += it }
        return out
    }

    /** One Jamendo track object; [parent] (album / playlist) fills in artist, album and cover when the track lacks them. */
    private fun jamendoTrack(o: JSONObject?, high: Boolean, parent: JSONObject? = null): Track? {
        if (o == null) return null
        val audio = o.optString("audio").trim()
        val title = o.optString("name").trim()
        if (!audio.startsWith("http") || title.isEmpty()) return null
        fun pick(key: String, parentKey: String = key) = o.optString(key).trim().ifBlank { parent?.optString(parentKey).orEmpty().trim() }
        return make(
            title = title, artist = pick("artist_name").ifBlank { "Jamendo" },
            album = pick("album_name", "name"), durationMs = o.optLong("duration", 0L) * 1000,
            url = audio, art = pick("image"),
            info = AudioInfo("MP3", false, 0, 0, 0, if (high) 192 else 96)
        )
    }

    // ------------------------------------------------------------------ Audius

    private fun audius(q: String, n: Int): List<Track> {
        val url = if (q.isEmpty()) "https://api.audius.co/v1/tracks/trending?limit=$n&app_name=$APP_NAME"
        else "https://api.audius.co/v1/tracks/search?query=${enc(q)}&limit=$n&app_name=$APP_NAME"
        val data = JSONObject(ArchiveApi.get(url)).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Track>()
        for (i in 0 until data.length()) audiusTrack(data.optJSONObject(i))?.let { out += it }
        return out
    }

    private fun audiusTrack(o: JSONObject?): Track? {
        if (o == null) return null
        val id = o.optString("id").trim()
        val title = o.optString("title").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        if (o.optBoolean("is_streamable", true).not() || o.optBoolean("is_unlisted", false)) return null
        val art = o.optJSONObject("artwork")
        return make(
            title = title,
            artist = o.optJSONObject("user")?.optString("name").orEmpty().ifBlank { "Audius" },
            album = "",
            durationMs = o.optLong("duration", 0L) * 1000,
            // The stream endpoint redirects to the audio file; MediaPlayer follows it.
            url = "https://api.audius.co/v1/tracks/${enc(id)}/stream?app_name=$APP_NAME",
            art = art?.optString("480x480").orEmpty().ifBlank { art?.optString("150x150").orEmpty() },
            info = AudioInfo("MP3", false, 0, 0, 0, 320)
        )
    }

    // ------------------------------------------------------------------ ccMixter

    private fun ccmixter(q: String, n: Int): List<Track> {
        val url = "https://ccmixter.org/api/query?f=json&limit=$n&sort=rank&dir=desc" +
            if (q.isEmpty()) "" else "&search=${enc(q)}&search_type=all"
        val arr = try { JSONArray(ArchiveApi.get(url)) } catch (_: org.json.JSONException) { return emptyList() }
        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) ccmixterTrack(arr.optJSONObject(i))?.let { out += it }
        return out
    }

    private fun ccmixterTrack(o: JSONObject?): Track? {
        if (o == null) return null
        val files = o.optJSONArray("files") ?: return null
        var dl = ""
        var kbps = 0
        var secs = 0L
        for (j in 0 until files.length()) {
            val f = files.optJSONObject(j) ?: continue
            val u = f.optString("download_url").trim()
            val fi = f.optJSONObject("file_format_info")
            val isMp3 = u.endsWith(".mp3", true) || fi?.optString("format-name").orEmpty().contains("mp3", true)
            if (!isMp3 || !u.startsWith("http")) continue
            dl = u
            kbps = Regex("\\d+").find(fi?.optString("default-bitrate").orEmpty())?.value?.toIntOrNull() ?: 0
            secs = fi?.optString("ps").orEmpty().split(':').fold(0L) { a, p -> a * 60 + (p.trim().toLongOrNull() ?: 0L) }
            break
        }
        val title = o.optString("upload_name").trim()
        if (dl.isEmpty() || title.isEmpty()) return null
        return make(
            title = title,
            artist = o.optString("user_real_name").ifBlank { o.optString("user_name") }.trim().ifBlank { "ccMixter" },
            album = "ccMixter", durationMs = secs * 1000, url = dl, art = "",
            info = AudioInfo("MP3", false, 0, 0, 0, kbps)
        )
    }

    // ------------------------------------------------------------------ pasted links

    private val HTTP_LINK = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val AUDIO_EXT = mapOf(
        "mp3" to ("MP3" to false), "m4a" to ("AAC" to false), "aac" to ("AAC" to false), "ogg" to ("OGG" to false),
        "oga" to ("OGG" to false), "opus" to ("OPUS" to false), "flac" to ("FLAC" to true), "wav" to ("WAV" to true)
    )

    /**
     * A search box text that is a link instead of words: songs of an Audius, Internet Archive, Jamendo or
     * ccMixter page, or a direct audio file. null = not a link (search the words as usual). A link this
     * does not know gives an empty list, never a keyword search for the URL text. YouTube links belong to the YouTube tab.
     */
    @Throws(IOException::class)
    private fun linkResults(context: Context, text: String): List<OnlineResult>? {
        val t = text.trim()
        if (!HTTP_LINK.matches(t)) return null
        val uri = try { Uri.parse(t) } catch (_: Exception) { return emptyList() }
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        val segs = uri.pathSegments.orEmpty()
        val songs: List<Track> = when {
            host == "audius.co" || host.endsWith(".audius.co") -> audiusLink(t)
            host == "archive.org" || host.endsWith(".archive.org") -> archiveLink(context, segs)
            host.endsWith("jamendo.com") -> jamendoLink(context, segs)
            host.endsWith("ccmixter.org") -> ccmixterLink(segs)
            else -> directAudio(uri, host, t)?.let { listOf(it) } ?: emptyList()
        }
        return songs.map { OnlineResult.Song(it) }
    }

    /** A link straight to an audio file (.mp3, .flac, ...): plays as one song named after the file. */
    private fun directAudio(uri: Uri, host: String, url: String): Track? {
        val file = uri.lastPathSegment.orEmpty()
        val ext = file.substringAfterLast('.', "").lowercase()
        val (codec, lossless) = AUDIO_EXT[ext] ?: return null
        val title = file.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { host }
        return make(title, host, "", 0L, url, "", AudioInfo(codec, lossless, 0, 0, 0, 0))
    }

    /** audius.co/<artist>/<track> (one song), /<artist>/playlist/<name> or /<artist> (their tracks), resolved by Audius itself. */
    private fun audiusLink(url: String): List<Track> {
        val n = LIMIT
        val raw = JSONObject(ArchiveApi.get("https://api.audius.co/v1/resolve?url=${enc(url)}&app_name=$APP_NAME")).opt("data")
        val d = (if (raw is JSONArray) raw.optJSONObject(0) else raw as? JSONObject) ?: return emptyList()
        val id = d.optString("id").trim()
        fun fetch(path: String): List<Track> {
            val arr = JSONObject(ArchiveApi.get("https://api.audius.co/v1/$path?limit=$n&app_name=$APP_NAME")).optJSONArray("data")
                ?: return emptyList()
            return (0 until arr.length()).mapNotNull { audiusTrack(arr.optJSONObject(it)) }
        }
        return when {
            d.has("playlist_name") && id.isNotEmpty() -> fetch("playlists/${enc(id)}/tracks")
            d.has("title") -> listOfNotNull(audiusTrack(d))
            d.has("handle") && id.isNotEmpty() -> fetch("users/${enc(id)}/tracks")
            else -> emptyList()
        }
    }

    /** archive.org/details/<id> (also /download/<id>, /embed/<id>): the item's songs. */
    private fun archiveLink(context: Context, segs: List<String>): List<Track> {
        if (segs.size < 2 || segs[0] !in setOf("details", "download", "embed", "stream")) return emptyList()
        return ArchiveApi.tracksOfId(context, segs[1])
    }

    /** jamendo.com/track/<id>/..., /album/<id>/..., /artist/<id>/..., /list/<id>/... (an optional language segment in front is skipped). */
    private fun jamendoLink(context: Context, segs: List<String>): List<Track> {
        val at = segs.indexOfFirst { it in setOf("track", "album", "artist", "list") }
        val id = segs.getOrNull(at + 1)?.takeIf { at >= 0 && it.all(Char::isDigit) && it.isNotEmpty() } ?: return emptyList()
        val key = OnlineKeys.jamendo(context)
        if (key.isBlank()) throw IOException("Jamendo key missing")
        val high = StreamQuality.effective(context) == StreamQuality.Level.HIGH
        val common = "client_id=${enc(key)}&format=json&limit=$LIMIT&audioformat=" + (if (high) "mp32" else "mp31")
        val base = "https://api.jamendo.com/v3.0/"
        val kind = segs[at]
        val url = when (kind) {
            "track" -> base + "tracks/?$common&id=$id&include=musicinfo"
            "artist" -> base + "tracks/?$common&artist_id=$id&order=popularity_total&include=musicinfo"
            "album" -> base + "albums/tracks/?$common&id=$id"
            else -> base + "playlists/tracks/?$common&id=$id"
        }
        val results = JSONObject(ArchiveApi.get(url)).optJSONArray("results") ?: return emptyList()
        if (kind == "track" || kind == "artist") {
            return (0 until results.length()).mapNotNull { jamendoTrack(results.optJSONObject(it), high) }
        }
        // Album / playlist: the answer is one object holding a "tracks" array.
        val parent = results.optJSONObject(0) ?: return emptyList()
        val arr = parent.optJSONArray("tracks") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { jamendoTrack(arr.optJSONObject(it), high, parent) }
    }

    /** ccmixter.org/files/<user>/<id>: that upload. */
    private fun ccmixterLink(segs: List<String>): List<Track> {
        val id = segs.lastOrNull()?.takeIf { segs.firstOrNull() == "files" && it.isNotEmpty() && it.all(Char::isDigit) } ?: return emptyList()
        val arr = try { JSONArray(ArchiveApi.get("https://ccmixter.org/api/query?f=json&ids=$id")) } catch (_: org.json.JSONException) { return emptyList() }
        return (0 until arr.length()).mapNotNull { ccmixterTrack(arr.optJSONObject(it)) }
    }

    private fun make(title: String, artist: String, album: String, durationMs: Long, url: String, art: String, info: AudioInfo): Track {
        val t = Track(
            id = 0L, title = title, artist = artist, album = album, durationMs = durationMs,
            source = Track.SOURCE_ONLINE, remoteId = url, artUrl = art.takeIf { it.startsWith("http") }.orEmpty()
        )
        AudioQuality.put(t, info)
        return t
    }
}
