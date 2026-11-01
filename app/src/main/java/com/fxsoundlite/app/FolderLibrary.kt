package com.fxsoundlite.app

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Songs in folders the user picked with the system file picker (Storage Access Framework).
 *
 * This is how an OTG (USB) drive is read: many phones never add a USB drive to MediaStore, so its songs
 * are invisible to [MusicLibrary]'s normal query. The user picks the drive (or a folder on it) once; the
 * permission is kept across restarts. It also works for an SD card that the phone does not index.
 *
 * A folder song is an ordinary local [Track] ([Track.SOURCE_LOCAL]) with a negative [Track.id] and the
 * document uri in [Track.remoteId]; [Track.uri] returns that uri, so playback, artwork, playlists and
 * Drive sync treat it like any other device song.
 */
object FolderLibrary {

    private const val PREFS = "folder_library"
    private const val KEY_TREES = "trees"
    private const val CACHE_FILE = "folder_meta.json"
    private const val MAX_DEPTH = 15
    private const val MAX_FILES = 20_000
    private const val MAX_SCAN_MS = 90_000L
    private const val MIN_SONG_MS = 10_000L // same rule as MusicLibrary: shorter clips are not songs

    private val AUDIO_EXT = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mka", "m4b", "3gp"
    )
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "3g2", "mpg", "mpeg", "flv", "wmv", "ogv"
    )
    private val SKIP_DIRS = setOf("android", "lost.dir", "\$recycle.bin", "system volume information")

    private class Doc(val docId: String, val name: String, val size: Long, val modified: Long)
    private class Meta(
        val size: Long, val modified: Long,
        val title: String, val artist: String, val album: String, val durationMs: Long
    )

    @Volatile private var cache: ConcurrentHashMap<String, Meta>? = null
    private val enriching = AtomicBoolean(false)

    // ------------------------------------------------------------------ picked folders

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The folders the user added, in the order added. */
    fun trees(ctx: Context): List<Uri> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY_TREES, "[]"))
        (0 until arr.length()).map { Uri.parse(arr.getString(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun hasTrees(ctx: Context): Boolean = trees(ctx).isNotEmpty()

    private fun saveTrees(ctx: Context, list: List<Uri>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toString()) }
        prefs(ctx).edit().putString(KEY_TREES, arr.toString()).apply()
    }

    /** Keeps read access to [tree] across restarts and remembers it. False if the system refused the permission. */
    fun addTree(ctx: Context, tree: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            ctx.contentResolver.takePersistableUriPermission(tree, rw) // write lets "delete song" work
        } catch (e: Exception) {
            try {
                ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: Exception) {
                return false
            }
        }
        val cur = trees(ctx)
        if (cur.none { it == tree }) saveTrees(ctx, cur + tree)
        MusicLibrary.notifyChanged()
        return true
    }

    fun removeTree(ctx: Context, tree: Uri) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Already gone: nothing to release.
        }
        saveTrees(ctx, trees(ctx).filter { it != tree })
        val prefix = tree.toString()
        cache?.keys?.removeAll { it.startsWith(prefix) }
        writeCache(ctx)
        MusicLibrary.notifyChanged()
    }

    /** Volume key of a picked folder: "primary" or the lower-case uuid of the card / drive. */
    fun volumeKey(tree: Uri): String = try {
        DocumentsContract.getTreeDocumentId(tree).substringBefore(':').lowercase(Locale.ROOT)
    } catch (e: Exception) {
        Storages.PRIMARY
    }

    /** Short text for a picked folder: "1234-5678 / Music". */
    fun label(tree: Uri): String = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val path = id.substringAfter(':', "").trim('/')
        id.substringBefore(':') + if (path.isEmpty()) "" else " / $path"
    } catch (e: Exception) {
        tree.toString()
    }

    // ------------------------------------------------------------------ scan

    /**
     * Songs (or, with [video], video files: [Track.SOURCE_VIDEO] tracks) in every picked folder, minus those whose
     * path is in [skip] (MediaStore already lists them).
     * Blocking: call from a background thread. A song seen for the first time shows its file name until its
     * tags are read (in the background; the library is refreshed when that is done).
     */
    fun load(ctx: Context, skip: Set<String>, video: Boolean = false): List<Track> {
        val trees = trees(ctx)
        if (trees.isEmpty()) return emptyList()
        val app = ctx.applicationContext
        val meta = readCache(app)
        val out = ArrayList<Track>()
        val toRead = ArrayList<Pair<Doc, Uri>>()
        val seen = HashSet<String>()
        val deadline = System.currentTimeMillis() + MAX_SCAN_MS
        var count = 0
        for (tree in trees) {
            val docs = ArrayList<Doc>()
            try {
                walk(app, tree, deadline, docs, MAX_FILES - count, video)
            } catch (e: Exception) {
                android.util.Log.w("FolderLibrary", "scan failed for $tree", e) // drive unplugged / permission lost
            }
            count += docs.size
            for (d in docs) {
                val pathKey = d.docId.lowercase(Locale.ROOT)
                if (pathKey in skip || !seen.add(pathKey)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.docId)
                val key = uri.toString()
                val m = meta[key]?.takeIf { it.size == d.size && it.modified == d.modified }
                if (m == null) toRead.add(d to uri)
                val dur = m?.durationMs ?: 0L
                if (!video && dur in 1 until MIN_SONG_MS) continue
                out.add(
                    Track(
                        id = idOf(key),
                        title = m?.title?.takeIf { it.isNotBlank() } ?: d.name.substringBeforeLast('.'),
                        artist = m?.artist.orEmpty(),
                        album = m?.album.orEmpty(),
                        durationMs = dur,
                        source = if (video) Track.SOURCE_VIDEO else Track.SOURCE_LOCAL,
                        remoteId = key
                    )
                )
            }
        }
        if (toRead.isNotEmpty()) enrich(app, toRead)
        return out
    }

    /** Directory walk with plain ContentResolver queries (much faster than DocumentFile). */
    private fun walk(ctx: Context, tree: Uri, deadline: Long, out: MutableList<Doc>, room: Int, video: Boolean) {
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(DocumentsContract.getTreeDocumentId(tree) to 0)
        while (stack.isNotEmpty()) {
            if (System.currentTimeMillis() > deadline || out.size >= room) return
            val (dirId, depth) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (name.startsWith(".")) continue // hidden: trash, macOS "._" copies of songs, ...
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH && name.lowercase(Locale.ROOT) !in SKIP_DIRS) stack.addLast(id to depth + 1)
                    } else if (if (video) isVideo(name, mime) else isAudio(name, mime)) {
                        out.add(Doc(id, name, if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
                        if (out.size >= room) return
                    }
                }
            }
        }
    }

    private fun isVideo(name: String, mime: String): Boolean =
        mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXT

    private fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXT

    /** Stable negative id (never clashes with MediaStore's positive ids): 64-bit FNV-1a of the document uri. */
    private fun idOf(key: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis
        for (ch in key) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return -((h ushr 1) + 1)
    }

    // ------------------------------------------------------------------ tags (title / artist / length), read once and cached

    private fun enrich(app: Context, items: List<Pair<Doc, Uri>>) {
        if (!enriching.compareAndSet(false, true)) return
        Thread {
            try {
                val map = readCache(app)
                var done = 0
                for ((d, uri) in items) {
                    map[uri.toString()] = readTags(app, d, uri)
                    if (++done % 200 == 0) writeCache(app)
                }
                writeCache(app)
            } catch (e: Throwable) {
                android.util.Log.w("FolderLibrary", "tag reading stopped", e)
            } finally {
                enriching.set(false)
                MusicLibrary.notifyChanged() // the list now shows real titles / lengths
            }
        }.apply { name = "folder-tags"; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun readTags(ctx: Context, d: Doc, uri: Uri): Meta {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            fun tag(k: Int) = r.extractMetadata(k)?.trim().orEmpty()
            Meta(
                d.size, d.modified,
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { d.name.substringBeforeLast('.') },
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .ifBlank { tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L
            )
        } catch (e: Exception) {
            // Unreadable file: remember it with its file name so it is not retried on every scan.
            Meta(d.size, d.modified, d.name.substringBeforeLast('.'), "", "", 0L)
        } finally {
            try { r.release() } catch (e: Exception) { /* nothing to release */ }
        }
    }

    @Synchronized
    private fun readCache(ctx: Context): ConcurrentHashMap<String, Meta> {
        cache?.let { return it }
        val map = ConcurrentHashMap<String, Meta>()
        try {
            val f = File(ctx.filesDir, CACHE_FILE)
            if (f.exists()) {
                val root = JSONObject(f.readText())
                for (k in root.keys()) {
                    val o = root.getJSONObject(k)
                    map[k] = Meta(
                        o.optLong("s"), o.optLong("m"), o.optString("t"),
                        o.optString("a"), o.optString("al"), o.optLong("d")
                    )
                }
            }
        } catch (e: Exception) {
            map.clear() // corrupt cache: just read the tags again
        }
        cache = map
        return map
    }

    @Synchronized
    private fun writeCache(ctx: Context) {
        val map = cache ?: return
        try {
            val root = JSONObject()
            for ((k, m) in map) {
                root.put(k, JSONObject().put("s", m.size).put("m", m.modified).put("t", m.title)
                    .put("a", m.artist).put("al", m.album).put("d", m.durationMs))
            }
            val f = File(ctx.filesDir, CACHE_FILE)
            val tmp = File(ctx.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            android.util.Log.w("FolderLibrary", "could not save tag cache", e)
        }
    }
}
