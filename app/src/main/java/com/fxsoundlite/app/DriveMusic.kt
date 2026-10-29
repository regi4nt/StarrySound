package com.fxsoundlite.app

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import android.media.MediaDataSource
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The "Drive" music tab: audio files from the user's own Google Drive, played like any other song.
 *
 * Needs the read-only Drive scope (the backup in [DriveSync] only has the hidden app folder), asked
 * for the first time the tab is opened: [GoogleAuthUtil] throws UserRecoverableAuthException with the
 * consent screen's intent, which the caller launches. The Google account is the one signed in under
 * Settings > Google account.
 *
 * Playback: the file is downloaded once to the app cache and played from there (seeking and
 * the equalizer work as for device songs, replays are instant). The cache is trimmed to
 * [CACHE_LIMIT_BYTES]; nothing is uploaded and nothing in Drive is changed.
 */
object DriveMusic {
    const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"

    private const val CACHE_DIR = "drive_music"
    private const val CACHE_LIMIT_BYTES = 100L * 1024 * 1024
    private const val MAX_FILES = 1000
    private const val PAGE_SIZE = 200

    /** Not signed in with Google (Settings > Google account). */
    class NotSignedInException : Exception()

    class DriveHttpException(val code: Int, message: String) : IOException(message)

    @Volatile private var cachedToken: String? = null

    fun isSignedIn(c: Context): Boolean = DriveSync.isSignedIn(c)

    // ------------------------------------------------------------------ token

    /** May throw UserRecoverableAuthException (consent needed): the caller shows [e.intent]. */
    private fun token(c: Context, refresh: Boolean): String {
        val account = GoogleSignIn.getLastSignedInAccount(c)?.account ?: throw NotSignedInException()
        if (refresh) cachedToken?.let { try { GoogleAuthUtil.clearToken(c, it) } catch (_: Exception) { } }
        val t = GoogleAuthUtil.getToken(c, account, "oauth2:$SCOPE_DRIVE_READONLY")
        cachedToken = t
        return t
    }

    private fun open(c: Context, url: String, refresh: Boolean): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer ${token(c, refresh)}")
        return conn
    }

    /** GET with one retry on 401 (stale token). Caller must disconnect. */
    private fun get(c: Context, url: String): HttpURLConnection {
        var conn = open(c, url, false)
        if (conn.responseCode == 401) {
            conn.disconnect()
            conn = open(c, url, true)
        }
        return conn
    }

    // ------------------------------------------------------------------ listing

    /** All audio files in the user's Drive (own + shared with them), by name. Blocking: call off the main thread. */
    fun listAudio(c: Context): List<Track> = fetchAudio(c).map { it.first }

    /** A Drive folder that holds audio files; shown as a playlist ([tracks] are its audio files, by name). */
    class Folder(val id: String, val name: String, val tracks: List<Track>)

    /** Drive folders with at least one audio file, as playlists. Blocking: call off the main thread. */
    fun listFolders(c: Context): List<Folder> {
        val app = c.applicationContext
        val byParent = LinkedHashMap<String, MutableList<Track>>()
        for ((t, parent) in fetchAudio(app)) {
            if (parent != null) byParent.getOrPut(parent) { ArrayList() } += t
        }
        if (byParent.isEmpty()) return emptyList()
        // Folder names (a parent that is not listed, e.g. a shared folder, is skipped).
        val names = HashMap<String, String>()
        val q = URLEncoder.encode("mimeType = 'application/vnd.google-apps.folder' and trashed = false", "UTF-8")
        val fields = URLEncoder.encode("nextPageToken,files(id,name)", "UTF-8")
        var page: String? = null
        do {
            val url = "https://www.googleapis.com/drive/v3/files?q=$q&pageSize=1000&supportsAllDrives=true" +
                "&includeItemsFromAllDrives=true&fields=$fields" +
                (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val conn = get(app, url)
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw DriveHttpException(code, errorText(code, body))
                val json = JSONObject(body)
                val files = json.optJSONArray("files")
                if (files != null) for (i in 0 until files.length()) {
                    val f = files.optJSONObject(i) ?: continue
                    names[f.optString("id")] = f.optString("name")
                }
                page = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        } while (page != null && names.size < 5000)
        return byParent.mapNotNull { (id, tracks) ->
            val n = names[id]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Folder(id, n, tracks)
        }.sortedBy { it.name.lowercase() }
    }

    /** Audio files with the id of their first parent folder (null when Drive did not tell). */
    private fun fetchAudio(c: Context): List<Pair<Track, String?>> = fetchFiles(c, "audio/", Track.SOURCE_DRIVE)

    /** Video files of the user's Drive (video mode), by name. Blocking: call off the main thread. */
    fun listVideos(c: Context): List<Track> = fetchFiles(c, "video/", Track.SOURCE_DRIVE_VIDEO).map { it.first }

    /**
     * URL and headers to stream a Drive video (VideoView.setVideoURI(uri, headers); Drive answers range requests,
     * so seeking works). Blocking (fetches the token): call off the main thread. May throw UserRecoverableAuthException.
     */
    fun streamRequest(c: Context, fileId: String): Pair<String, Map<String, String>> {
        val app = c.applicationContext
        val safe = fileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (safe.isEmpty()) throw IOException("bad file id")
        return "https://www.googleapis.com/drive/v3/files/$safe?alt=media&supportsAllDrives=true" to
            mapOf("Authorization" to "Bearer ${token(app, false)}")
    }

    private fun fetchFiles(c: Context, mimePrefix: String, source: Int): List<Pair<Track, String?>> {
        val app = c.applicationContext
        val q = URLEncoder.encode("mimeType contains '$mimePrefix' and trashed = false", "UTF-8")
        val fields = URLEncoder.encode("nextPageToken,files(id,name,size,parents)", "UTF-8")
        val out = ArrayList<Pair<Track, String?>>()
        var page: String? = null
        do {
            val url = "https://www.googleapis.com/drive/v3/files?q=$q&orderBy=name&pageSize=$PAGE_SIZE" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true&fields=$fields" +
                (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val conn = get(app, url)
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw DriveHttpException(code, errorText(code, body))
                val json = JSONObject(body)
                val files = json.optJSONArray("files")
                if (files != null) for (i in 0 until files.length()) {
                    val f = files.optJSONObject(i) ?: continue
                    val id = f.optString("id")
                    if (id.isBlank()) continue
                    out += toTrack(id, f.optString("name"), source) to f.optJSONArray("parents")?.optString(0)?.takeIf { it.isNotBlank() }
                }
                page = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        } while (page != null && out.size < MAX_FILES)
        return out
    }

    private fun errorText(code: Int, body: String): String {
        val msg = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
        return "HTTP $code" + if (msg.isBlank()) "" else ": " + msg.take(140)
    }

    /** "Artist - Title.mp3" -> artist + title; anything else keeps the whole name as the title. */
    private fun toTrack(id: String, fileName: String, source: Int = Track.SOURCE_DRIVE): Track {
        val base = fileName.substringBeforeLast('.', fileName).replace('_', ' ').trim()
        val dash = base.indexOf(" - ")
        val (artist, title) = if (dash in 1 until base.length - 3)
            base.substring(0, dash).trim() to base.substring(dash + 3).trim()
        else "" to base
        return Track(
            id = 0L, title = title.ifBlank { fileName }, artist = artist, album = "",
            durationMs = 0L, source = source, remoteId = id
        )
    }

    // ------------------------------------------------------------------ play while downloading

    /** Drive did not tell the file size, so it cannot be played while it downloads (the caller downloads it first). */
    class NoLengthException : IOException("unknown size")

    /**
     * A song that is still downloading into the cache. [MediaPlayer] reads it through [readAt]: bytes already
     * downloaded come back at once, bytes not there yet make the read wait. So playback starts after the first
     * few KB, and the finished file stays in the cache for instant replays. Seeking ahead of what is downloaded
     * waits for the download to catch up.
     */
    class StreamingSource internal constructor(private val part: File, private val total: Long) : MediaDataSource() {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val lock = Object()
        private val raf = RandomAccessFile(part, "r")
        @Volatile private var downloaded = 0L
        @Volatile private var failure: IOException? = null
        @Volatile var closed = false
            private set

        internal fun advance(n: Int) {
            synchronized(lock) { downloaded += n; lock.notifyAll() }
        }

        internal fun fail(e: IOException) {
            synchronized(lock) { failure = e; lock.notifyAll() }
        }

        override fun getSize(): Long = total

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= total) return -1
            synchronized(lock) {
                while (downloaded <= position && failure == null && !closed) lock.wait(500)
            }
            if (closed) throw IOException("closed")
            if (downloaded <= position) throw failure ?: IOException("download failed")
            val n = minOf(size.toLong(), total - position, downloaded - position).toInt()
            synchronized(raf) {
                raf.seek(position)
                return raf.read(buffer, offset, n)
            }
        }

        /** Stops the download too (a song that was skipped should not keep downloading). */
        override fun close() {
            synchronized(lock) { closed = true; lock.notifyAll() }
            try { synchronized(raf) { raf.close() } } catch (_: Exception) { }
        }
    }

    /** The song when it is already completely in the cache, else null. */
    fun cachedFile(c: Context, fileId: String): File? {
        val safe = fileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (safe.isEmpty()) return null
        val f = File(cacheDir(c.applicationContext), safe)
        if (!f.isFile || f.length() <= 0) return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    /**
     * Opens the download and returns as soon as Drive answers; the rest downloads on a background thread
     * into the cache while the returned source is being played. Blocking until the answer: call off the main
     * thread. Throws [NoLengthException] when the size is unknown (use [fetch] then), IOException / DriveHttpException otherwise.
     */
    fun startStream(c: Context, fileId: String, cancelled: () -> Boolean): StreamingSource {
        val app = c.applicationContext
        val dir = cacheDir(app)
        val safe = fileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (safe.isEmpty()) throw IOException("bad file id")
        // Leftovers of downloads that were cut short (app killed) are not needed any more.
        dir.listFiles { f -> f.name.endsWith(".part") && System.currentTimeMillis() - f.lastModified() > 3_600_000L }
            ?.forEach { it.delete() }

        val conn = get(app, "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true")
        val total: Long = try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw DriveHttpException(code, errorText(code, body))
            }
            val len = conn.contentLengthLong
            if (len <= 0L) throw NoLengthException()
            len
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
        // Make room BEFORE downloading, so the cache never grows past the limit while a song is coming in.
        trim(dir, keep = File(dir, safe), reserve = total)
        // Own name per download, so a cancelled earlier one can never delete this one's file.
        val part = File(dir, "$safe.${System.nanoTime()}.part")
        val source: StreamingSource
        try {
            part.createNewFile()
            source = StreamingSource(part, total)
        } catch (e: Exception) {
            conn.disconnect()
            part.delete()
            throw e
        }
        val target = File(dir, safe)
        Thread({
            var ok = false
            try {
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (source.closed || cancelled()) throw IOException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            source.advance(n)
                        }
                    }
                }
                ok = part.length() == total
                if (!ok) source.fail(IOException("download incomplete"))
            } catch (e: Exception) {
                source.fail(if (e is IOException) e else IOException(e.message))
            } finally {
                conn.disconnect()
                if (ok && part.renameTo(target)) trim(dir, keep = target) else part.delete()
            }
        }, "drive-download").apply { isDaemon = true; start() }
        return source
    }

    // ------------------------------------------------------------------ playback file

    private fun cacheDir(c: Context) = File(c.applicationContext.cacheDir, CACHE_DIR).also { it.mkdirs() }

    /**
     * The song as a local file, downloading it first when it is not cached. Blocking: call off the
     * main thread. Throws IOException / DriveHttpException on failure.
     */
    fun fetch(c: Context, fileId: String, cancelled: () -> Boolean = { false }): File {
        val app = c.applicationContext
        val dir = cacheDir(app)
        val safe = fileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (safe.isEmpty()) throw IOException("bad file id")
        val target = File(dir, safe)
        if (target.isFile && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis()) // LRU: used just now
            return target
        }
        val part = File(dir, "$safe.part")
        val conn = get(app, "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw DriveHttpException(code, errorText(code, body))
            }
            trim(dir, keep = target, reserve = conn.contentLengthLong.coerceAtLeast(0L))
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        // The user already moved on to another song: stop downloading this one.
                        if (cancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (part.length() == 0L || !part.renameTo(target)) {
            part.delete()
            throw IOException("download failed")
        }
        trim(dir, keep = target)
        return target
    }

    /**
     * Keeps the cache under the limit by deleting the least recently used songs. [reserve] is the size of a song
     * about to be downloaded (room is made for it first). Downloads in progress (.part) count towards the total
     * but are never deleted here; leftovers of dead downloads are removed after 10 minutes.
     */
    private fun trim(dir: File, keep: File, reserve: Long = 0L) {
        val all = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        all.filter { it.name.endsWith(".part") && now - it.lastModified() > 600_000L }.forEach { it.delete() }
        val parts = all.filter { it.isFile && it.name.endsWith(".part") && it.exists() }.sumOf { it.length() }
        val songs = all.filter { it.isFile && !it.name.endsWith(".part") }
        var total = songs.sumOf { it.length() } + parts + reserve
        if (total <= CACHE_LIMIT_BYTES) return
        for (f in songs.sortedBy { it.lastModified() }) {
            if (total <= CACHE_LIMIT_BYTES) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }

    /** Trims the song cache to the limit (called at app start). */
    fun trimToLimit(c: Context) {
        try { trim(cacheDir(c), keep = File("")) } catch (_: Exception) { }
    }

    /** Forget the account's token and downloaded songs (sign-out). */
    fun clear(c: Context) {
        cachedToken = null
        try { cacheDir(c).listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
    }
}
