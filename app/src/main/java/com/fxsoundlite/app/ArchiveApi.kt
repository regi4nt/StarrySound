package com.fxsoundlite.app

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** One search hit on the Internet Archive: an album, a concert recording, a podcast... holding one or more audio files. */
data class ArchiveItem(
    val identifier: String,
    val title: String,
    val creator: String,
    val thumbUrl: String
)

/**
 * Online tab source: the Internet Archive (https://archive.org), free, no API key, legal public audio
 * (Live Music Archive, netlabels, public domain...). Blocking: call from a background thread.
 *
 * Flow: [search] lists items -> tapping one calls [tracks], which gives its songs as [Track]s with
 * [Track.SOURCE_ONLINE]; [Track.remoteId] is the direct https link of the audio file (seekable, not live).
 * The file format follows Settings > Audio quality: High prefers FLAC, Normal / Low prefer MP3.
 */
object ArchiveApi {

    private const val BASE = "https://archive.org"
    private const val USER_AGENT = "StarrySound/1.0 (Android; music player)"
    private const val ROWS = 30
    private const val ROWS_SAVER = 15
    private val BAD_QUERY_CHARS = Regex("[:\"\\\\()\\[\\]{}^~*?!/]")

    /** Search items. An empty [query] lists the most downloaded audio. */
    @Throws(IOException::class)
    fun search(context: Context, query: String, losslessOnly: Boolean = false): List<ArchiveItem> {
        val words = query.replace(BAD_QUERY_CHARS, " ").trim().replace(Regex("\\s+"), " ")
        var q = if (words.isEmpty()) "mediatype:audio"
        else "($words) AND mediatype:audio"
        if (losslessOnly) q += " AND format:(Flac)"
        val rows = if (MusicPrefs.saver(context)) ROWS_SAVER else ROWS
        val url = "$BASE/advancedsearch.php?q=" + URLEncoder.encode(q, "UTF-8") +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator" +
            "&sort%5B%5D=downloads+desc&rows=$rows&page=1&output=json"
        val docs = JSONObject(get(url)).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val out = ArrayList<ArchiveItem>()
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val id = d.optString("identifier").trim()
            if (id.isEmpty()) continue
            out += ArchiveItem(
                identifier = id,
                title = firstText(d, "title").ifBlank { id },
                creator = firstText(d, "creator"),
                thumbUrl = "$BASE/services/img/" + URLEncoder.encode(id, "UTF-8")
            )
        }
        return out
    }

    /** The songs of one item, in track order, in the file format the audio-quality setting asks for. */
    @Throws(IOException::class)
    fun tracks(context: Context, item: ArchiveItem): List<Track> =
        tracksFrom(context, JSONObject(get("$BASE/metadata/" + URLEncoder.encode(item.identifier, "UTF-8"))), item)

    /** Same, for a pasted archive.org link: only the identifier is known, so title and creator come from the item's own metadata. */
    @Throws(IOException::class)
    fun tracksOfId(context: Context, identifier: String): List<Track> {
        val meta = JSONObject(get("$BASE/metadata/" + URLEncoder.encode(identifier, "UTF-8")))
        val md = meta.optJSONObject("metadata")
        val item = ArchiveItem(
            identifier = identifier,
            title = md?.let { firstText(it, "title") }.orEmpty().ifBlank { identifier },
            creator = md?.let { firstText(it, "creator") }.orEmpty(),
            thumbUrl = "$BASE/services/img/" + URLEncoder.encode(identifier, "UTF-8")
        )
        return tracksFrom(context, meta, item)
    }

    private fun tracksFrom(context: Context, meta: JSONObject, item: ArchiveItem): List<Track> {
        val files = meta.optJSONArray("files") ?: return emptyList()
        val level = StreamQuality.effective(context)

        // Every playable file, grouped by its base name so each song appears once (FLAC and MP3 of the same song).
        class F(val name: String, val format: String, val title: String, val artist: String, val trackNo: Int, val lengthSec: Double, val kbps: Int)
        val byBase = LinkedHashMap<String, MutableList<F>>()
        for (i in 0 until files.length()) {
            val o = files.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val format = o.optString("format")
            if (name.isEmpty() || rank(format, level) < 0) continue
            val base = name.substringBeforeLast('.')
            byBase.getOrPut(base) { ArrayList() } += F(
                name, format, o.optString("title").trim(), o.optString("creator").trim(),
                o.optString("track").substringBefore('/').trim().toIntOrNull() ?: Int.MAX_VALUE,
                parseLength(o.optString("length")),
                o.optString("bitrate").trim().toIntOrNull() ?: 0
            )
        }

        val picked = byBase.values.mapNotNull { list -> list.maxByOrNull { rank(it.format, level) } }
            .sortedWith(compareBy<F> { it.trackNo }.thenBy { it.name })

        return picked.map { f ->
            val url = "$BASE/download/" + URLEncoder.encode(item.identifier, "UTF-8") + "/" +
                f.name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val t = Track(
                id = 0L,
                title = f.title.ifBlank { f.name.substringBeforeLast('.') },
                artist = f.artist.ifBlank { item.creator.ifBlank { item.title } },
                album = item.title,
                durationMs = (f.lengthSec * 1000).toLong(),
                source = Track.SOURCE_ONLINE, remoteId = url, artUrl = item.thumbUrl
            )
            AudioQuality.put(t, infoOf(f.format, f.kbps))
            t
        }
    }

    /** Higher = preferred for [level]; -1 = not an audio format we play. */
    private fun rank(format: String, level: StreamQuality.Level): Int {
        val f = format.lowercase(Locale.US)
        val flac = f == "flac" || f == "24bit flac"
        val mp3 = f.contains("mp3")
        val ogg = f == "ogg vorbis"
        return when {
            !flac && !mp3 && !ogg -> -1
            level == StreamQuality.Level.HIGH -> if (flac) 3 else if (f == "vbr mp3") 2 else if (mp3) 1 else 0
            level == StreamQuality.Level.LOW -> if (f.startsWith("64kbps")) 3 else if (mp3) 2 else if (ogg) 1 else 0
            else -> if (f == "vbr mp3") 3 else if (f.startsWith("128kbps")) 3 else if (mp3) 2 else if (ogg) 1 else 0 // NORMAL / AUTO
        }
    }

    private fun infoOf(format: String, kbps: Int): AudioInfo {
        val f = format.lowercase(Locale.US)
        return when {
            f.contains("flac") -> AudioInfo("FLAC", true, 0, if (f.startsWith("24")) 24 else 0, 0, kbps)
            f.contains("mp3") -> AudioInfo("MP3", false, 0, 0, 0, kbps)
            else -> AudioInfo("OGG", false, 0, 0, 0, kbps)
        }
    }

    /** "245.3" seconds or "4:05" / "1:02:03". */
    private fun parseLength(s: String): Double {
        if (s.isBlank()) return 0.0
        s.toDoubleOrNull()?.let { return it }
        return s.split(':').fold(0.0) { acc, p -> acc * 60 + (p.toDoubleOrNull() ?: 0.0) }
    }

    /** The Archive returns either a string or an array for fields like title / creator. */
    private fun firstText(o: JSONObject, key: String): String {
        val v = o.opt(key) ?: return ""
        return if (v is org.json.JSONArray) v.optString(0).trim() else v.toString().trim()
    }

    internal fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
