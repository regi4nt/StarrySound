package com.fxsoundlite.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** The sources inside the Online tab, in the order of the source chips. */
enum class OnlineSource(val labelRes: Int) {
    ARCHIVE(R.string.online_src_archive),
    AUDIUS(R.string.online_src_audius),
    JAMENDO(R.string.online_src_jamendo),
    CCMIXTER(R.string.online_src_ccmixter),
    OPENVERSE(R.string.online_src_openverse)
}

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
 *  - Openverse: openly licensed music from many sites, no key.
 */
object OnlineApi {

    private const val APP_NAME = "StarrySound"
    private const val LIMIT = 30
    private const val LIMIT_SAVER = 15

    /** True when [source] cannot search until the user adds a (free) key in Settings. */
    fun needsKey(context: Context, source: OnlineSource): Boolean =
        source == OnlineSource.JAMENDO && OnlineKeys.jamendo(context).isBlank()

    @Throws(IOException::class)
    fun search(context: Context, source: OnlineSource, query: String): List<OnlineResult> {
        val q = query.trim()
        val n = if (MusicPrefs.saver(context)) LIMIT_SAVER else LIMIT
        return when (source) {
            OnlineSource.ARCHIVE -> ArchiveApi.search(context, q).map { OnlineResult.Album(it) }
            OnlineSource.AUDIUS -> audius(q, n).map { OnlineResult.Song(it) }
            OnlineSource.JAMENDO -> jamendo(context, q, n).map { OnlineResult.Song(it) }
            OnlineSource.CCMIXTER -> ccmixter(q, n).map { OnlineResult.Song(it) }
            OnlineSource.OPENVERSE -> openverse(q, minOf(n, 20)).map { OnlineResult.Song(it) }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ Jamendo

    private fun jamendo(context: Context, q: String, n: Int): List<Track> {
        val key = OnlineKeys.jamendo(context)
        if (key.isBlank()) throw IOException("Jamendo key missing")
        val high = StreamQuality.effective(context) == StreamQuality.Level.HIGH
        val url = "https://api.jamendo.com/v3.0/tracks/?client_id=${enc(key)}&format=json&limit=$n" +
            "&audioformat=" + (if (high) "mp32" else "mp31") + "&order=popularity_total&include=musicinfo" +
            if (q.isEmpty()) "" else "&search=${enc(q)}"
        val data = JSONObject(ArchiveApi.get(url)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Track>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val audio = o.optString("audio").trim()
            val title = o.optString("name").trim()
            if (!audio.startsWith("http") || title.isEmpty()) continue
            out += make(
                title = title, artist = o.optString("artist_name").trim().ifBlank { "Jamendo" },
                album = o.optString("album_name").trim(), durationMs = o.optLong("duration", 0L) * 1000,
                url = audio, art = o.optString("image"),
                info = AudioInfo("MP3", false, 0, 0, 0, if (high) 192 else 96)
            )
        }
        return out
    }

    // ------------------------------------------------------------------ Openverse

    private fun openverse(q: String, n: Int): List<Track> {
        val url = "https://api.openverse.org/v1/audio/?category=music&page_size=$n&q=" + enc(q.ifEmpty { "music" })
        val data = JSONObject(ArchiveApi.get(url)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Track>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val audio = o.optString("url").trim()
            val type = o.optString("filetype").lowercase()
            val title = o.optString("title").trim()
            if (!audio.startsWith("http") || title.isEmpty()) continue
            if (type.isNotEmpty() && type !in setOf("mp3", "ogg", "oga", "flac", "wav", "m4a")) continue // MediaPlayer can't play the rest
            val codec = if (type.isEmpty()) "MP3" else type.uppercase().replace("OGA", "OGG")
            out += make(
                title = title, artist = o.optString("creator").trim().ifBlank { o.optString("source").ifBlank { "Openverse" } },
                album = o.optString("source"), durationMs = o.optLong("duration", 0L), // Openverse gives milliseconds
                url = audio, art = o.optString("thumbnail"),
                info = AudioInfo(codec, type == "flac" || type == "wav", o.optInt("sample_rate", 0), 0, 0, o.optInt("bit_rate", 0) / 1000)
            )
        }
        return out
    }

    // ------------------------------------------------------------------ Audius

    private fun audius(q: String, n: Int): List<Track> {
        val url = if (q.isEmpty()) "https://api.audius.co/v1/tracks/trending?limit=$n&app_name=$APP_NAME"
        else "https://api.audius.co/v1/tracks/search?query=${enc(q)}&limit=$n&app_name=$APP_NAME"
        val data = JSONObject(ArchiveApi.get(url)).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Track>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val title = o.optString("title").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            if (o.optBoolean("is_streamable", true).not() || o.optBoolean("is_unlisted", false)) continue
            val art = o.optJSONObject("artwork")
            out += make(
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
        return out
    }

    // ------------------------------------------------------------------ ccMixter

    private fun ccmixter(q: String, n: Int): List<Track> {
        val url = "https://ccmixter.org/api/query?f=json&limit=$n&sort=rank&dir=desc" +
            if (q.isEmpty()) "" else "&search=${enc(q)}&search_type=all"
        val arr = try { JSONArray(ArchiveApi.get(url)) } catch (_: org.json.JSONException) { return emptyList() }
        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val files = o.optJSONArray("files") ?: continue
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
            if (dl.isEmpty() || title.isEmpty()) continue
            out += make(
                title = title,
                artist = o.optString("user_real_name").ifBlank { o.optString("user_name") }.trim().ifBlank { "ccMixter" },
                album = "ccMixter", durationMs = secs * 1000, url = dl, art = "",
                info = AudioInfo("MP3", false, 0, 0, 0, kbps)
            )
        }
        return out
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
