package com.fxsoundlite.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Playlist import / export, all without a server:
 *   - M3U file (export one playlist, import any M3U / M3U8),
 *   - YouTube / YouTube Music playlist link (import, read through NewPipeExtractor, no API key),
 *   - StarrySound share link `starrysound://playlist?d=...` (the songs travel inside the link),
 *   - JSON backup of every playlist + liked songs, a second backup next to Google Drive.
 *
 * Everything here is blocking: call it from a background thread.
 */
object PlaylistIo {
    const val MAX_IMPORT_TRACKS = 300
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024
    private const val MAX_LINK_TRACKS = 100
    /** Chat apps (Telegram: 4096) cut longer messages, so a share link stays below this. */
    private const val MAX_LINK_CHARS = 3500
    private const val LINK_PREFIX = "starrysound://playlist?d="

    private val LINK_RE = Regex("starrysound://playlist\\?d=([A-Za-z0-9_-]+)")
    private val YT_VIDEO_RE = Regex(
        "(?:youtube\\.com/(?:watch\\?(?:[^\\s#]*&)?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})"
    )
    private val YT_LIST_RE = Regex("[?&]list=([A-Za-z0-9_-]{10,})")
    private val YT_ID_RE = Regex("[A-Za-z0-9_-]{11}")

    /** A playlist read from a file / link, not saved yet. [skipped] = entries that could not be used. */
    class Imported(val name: String, val tracks: List<Track>, val skipped: Int)

    class ImportException(@StringRes val resId: Int) : Exception()

    class ShareLink(val link: String, val shared: Int, val omitted: Int)

    // ------------------------------------------------------------------ small helpers

    private fun oneLine(s: String) = s.replace('\r', ' ').replace('\n', ' ').trim()

    private fun youtubeUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun thumb(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    fun youtubeVideoId(text: String): String? = YT_VIDEO_RE.find(text)?.groupValues?.get(1)

    fun youtubePlaylistId(text: String): String? = YT_LIST_RE.find(text)?.groupValues?.get(1)

    fun isShareLink(text: String): Boolean = LINK_RE.containsMatchIn(text)

    /** True when [text] holds something the "import from link" box can use. */
    fun recognizes(text: String): Boolean = isShareLink(text) || youtubePlaylistId(text) != null

    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "playlist" }.take(80)

    fun backupFileName(): String =
        "starrysound-playlists-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json"

    // ------------------------------------------------------------------ M3U export

    /** Extended M3U text. YouTube songs get their watch URL, radio stations their stream URL,
     *  local songs their file path (when the system still gives one). */
    fun toM3u(c: Context, name: String, tracks: List<Track>): String {
        val sb = StringBuilder("#EXTM3U\n")
        if (name.isNotBlank()) sb.append("#PLAYLIST:").append(oneLine(name)).append('\n')
        for (t in tracks) {
            val target = when (t.source) {
                Track.SOURCE_YOUTUBE -> if (t.remoteId.isBlank()) null else youtubeUrl(t.remoteId)
                Track.SOURCE_RADIO -> t.remoteId.takeIf { it.isNotBlank() }
                else -> localPath(c, t)
            } ?: continue
            val label = if (t.artist.isBlank() || t.source == Track.SOURCE_RADIO) oneLine(t.title)
            else oneLine(t.artist) + " - " + oneLine(t.title)
            val secs = if (t.durationMs > 0) t.durationMs / 1000 else -1L
            sb.append("#EXTINF:").append(secs).append(',').append(label).append('\n')
            if (t.artUrl.isNotBlank()) sb.append("#EXTIMG:").append(oneLine(t.artUrl)).append('\n')
            sb.append(oneLine(target)).append('\n')
        }
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun localPath(c: Context, t: Track): String? = try {
        c.contentResolver.query(t.uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ M3U import

    fun parseM3u(text: String, defaultName: String = ""): Imported {
        var name = defaultName
        var title = ""
        var artist = ""
        var durSec = -1L
        var art = ""
        val out = ArrayList<Track>()
        var skipped = 0
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#PLAYLIST:", ignoreCase = true) ->
                        line.substring(10).trim().takeIf { it.isNotEmpty() }?.let { name = it }
                    line.startsWith("#EXTINF:", ignoreCase = true) -> {
                        val body = line.substring(8)
                        val comma = body.indexOf(',')
                        if (comma >= 0) {
                            durSec = body.substring(0, comma).trim().split(' ').firstOrNull()?.toLongOrNull() ?: -1L
                            val label = body.substring(comma + 1).trim()
                            val dash = label.indexOf(" - ")
                            if (dash > 0) {
                                artist = label.substring(0, dash).trim()
                                title = label.substring(dash + 3).trim()
                            } else {
                                artist = ""
                                title = label
                            }
                        }
                    }
                    line.startsWith("#EXTIMG:", ignoreCase = true) -> art = line.substring(8).trim()
                }
                continue
            }
            val t = trackFromEntry(line, title, artist, durSec, art)
            if (t != null) out.add(t) else skipped++
            title = ""; artist = ""; durSec = -1L; art = ""
        }
        return Imported(name, out.distinctBy { it.key }.take(MAX_IMPORT_TRACKS), skipped)
    }

    private fun trackFromEntry(line: String, title: String, artist: String, durSec: Long, art: String): Track? {
        val dur = if (durSec > 0) durSec * 1000 else 0L
        youtubeVideoId(line)?.let { id ->
            return Track(
                id = 0, title = title.ifBlank { id }, artist = artist, album = "", durationMs = dur,
                source = Track.SOURCE_YOUTUBE, remoteId = id, artUrl = art.ifBlank { thumb(id) }
            )
        }
        if (line.startsWith("http://", true) || line.startsWith("https://", true)) {
            val host = try { Uri.parse(line).host.orEmpty() } catch (e: Exception) { "" }
            return Track(
                id = 0, title = title.ifBlank { host.ifBlank { line } }, artist = artist, album = "",
                durationMs = 0, source = Track.SOURCE_RADIO, remoteId = line, artUrl = art
            )
        }
        return null // a path on someone else's phone: nothing to play here
    }

    // ------------------------------------------------------------------ share link

    /** Packs the YouTube songs of a playlist into one `starrysound://` link (gzip + base64url).
     *  Local files cannot travel; the list is trimmed until the link fits a chat message. */
    fun buildShareLink(name: String, tracks: List<Track>): ShareLink {
        val yt = tracks.filter { it.source == Track.SOURCE_YOUTUBE && it.remoteId.length == 11 }
        var n = minOf(yt.size, MAX_LINK_TRACKS)
        while (n > 0) {
            val link = encodeLink(name, yt.take(n))
            if (link.length <= MAX_LINK_CHARS || n == 1) return ShareLink(link, n, tracks.size - n)
            n = (n * 0.85).toInt()
        }
        return ShareLink("", 0, tracks.size)
    }

    private fun encodeLink(name: String, list: List<Track>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONArray().put(it.remoteId).put(it.title.take(90)).put(it.artist.take(50)).put(it.durationMs / 1000))
        }
        val json = JSONObject().put("v", 1).put("n", name.take(60)).put("t", arr).toString()
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return LINK_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
    }

    /** Reads a share link out of [text] (a whole pasted chat message is fine); null if there is none or it is damaged. */
    fun decodeLink(text: String): Imported? {
        val m = LINK_RE.find(text) ?: return null
        return try {
            val packed = Base64.getUrlDecoder().decode(m.groupValues[1])
            val json = GZIPInputStream(ByteArrayInputStream(packed)).use { String(readLimited(it, 1_000_000), Charsets.UTF_8) }
            val root = JSONObject(json)
            val arr = root.optJSONArray("t") ?: return null
            val tracks = ArrayList<Track>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val a = arr.optJSONArray(i)
                val id = a?.optString(0).orEmpty()
                if (a == null || !YT_ID_RE.matches(id)) { skipped++; continue }
                tracks.add(
                    Track(
                        id = 0, title = a.optString(1).ifBlank { id }, artist = a.optString(2), album = "",
                        durationMs = a.optLong(3) * 1000, source = Track.SOURCE_YOUTUBE, remoteId = id, artUrl = thumb(id)
                    )
                )
            }
            Imported(root.optString("n"), tracks.distinctBy { it.key }, skipped)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ YouTube playlist import

    /** Reads a public YouTube / YouTube Music playlist (up to [MAX_IMPORT_TRACKS] songs). */
    fun importYouTubePlaylist(listId: String): Imported {
        val service = org.schabi.newpipe.extractor.ServiceList.YouTube
        val url = "https://www.youtube.com/playlist?list=$listId"
        val items = ArrayList<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
        val name: String
        try {
            val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(service, url)
            name = info.name.orEmpty()
            items.addAll(info.relatedItems)
            var page = info.nextPage
            while (page != null && items.size < MAX_IMPORT_TRACKS) {
                val more = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getMoreItems(service, url, page)
                items.addAll(more.items)
                page = more.nextPage
            }
        } catch (e: IOException) {
            throw ImportException(R.string.playlist_import_err_network)
        } catch (e: Exception) {
            throw ImportException(R.string.playlist_import_err_failed)
        }
        val tracks = ArrayList<Track>()
        var skipped = 0
        for (item in items) {
            val id = youtubeVideoId(item.url.orEmpty())
            if (id == null || item.duration <= 0) { skipped++; continue } // deleted / private / live
            tracks.add(
                Track(
                    id = 0, title = item.name.orEmpty(), artist = item.uploaderName.orEmpty(), album = "",
                    durationMs = item.duration * 1000, source = Track.SOURCE_YOUTUBE, remoteId = id,
                    artUrl = item.thumbnails.firstOrNull()?.url.orEmpty().ifBlank { thumb(id) }
                )
            )
        }
        return Imported(name, tracks.distinctBy { it.key }.take(MAX_IMPORT_TRACKS), skipped)
    }

    // ------------------------------------------------------------------ JSON backup

    /** Every playlist and the liked songs, wrapped with a type marker so a restore can recognise it. */
    fun backupJson(c: Context): String = JSONObject()
        .put("type", "starrysound-playlists")
        .put("version", 1)
        .put("exported", System.currentTimeMillis())
        .put("playlists", PlaylistStore.exportJson(c))
        .toString(2)

    /** The playlist data inside [text] if it is a StarrySound backup, else null. */
    fun parseBackup(text: String): JSONObject? {
        if (!text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{")) return null
        return try {
            val root = JSONObject(text)
            val data = root.optJSONObject("playlists") ?: root
            if (data.has("lists") || data.has("liked")) data else null
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ files

    fun readText(c: Context, uri: Uri): String {
        val stream = c.contentResolver.openInputStream(uri) ?: throw IOException("cannot open file")
        return stream.use { String(readLimited(it, MAX_FILE_BYTES), Charsets.UTF_8) }
    }

    fun writeText(c: Context, uri: Uri, text: String) {
        val stream = c.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot write file")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** File name without extension, used as the playlist name for an M3U without a #PLAYLIST line. */
    fun fileTitle(c: Context, uri: Uri): String {
        val raw = try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: return ""
        return raw.substringBeforeLast('.').trim()
    }

    private fun readLimited(s: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
