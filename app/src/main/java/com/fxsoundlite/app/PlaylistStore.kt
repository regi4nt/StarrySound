package com.fxsoundlite.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A user playlist. [LIKED_ID] is the built-in "Liked songs" list (its [name] is filled in by the UI). */
/** [description] is free text shown at the top of the opened list (synced to Drive; the custom cover image stays on the device). */
data class Playlist(val id: String, val name: String, val tracks: List<Track>, val description: String = "")

/**
 * Local storage for playlists and liked songs (SharedPreferences + JSON, same approach as the
 * YouTube history in [MusicPrefs]). Tracks are stored with everything needed to play them again,
 * so YouTube songs keep working without a new search.
 */
object PlaylistStore {
    const val LIKED_ID = "liked"

    private const val PREFS = "playlist_prefs"
    private const val KEY_LISTS = "playlists"
    private const val KEY_LIKED = "liked"
    private const val KEY_TOMBS = "deleted"

    /** Deletion records older than this are forgotten (a device offline longer than that may resurrect old items). */
    private const val TOMB_TTL_MS = 180L * 24 * 3600 * 1000

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ JSON

    internal fun trackToJson(t: Track) = JSONObject()
        .put("src", t.source).put("lid", t.id).put("rid", t.remoteId)
        .put("title", t.title).put("artist", t.artist).put("album", t.album)
        .put("dur", t.durationMs).put("art", t.artUrl).put("added", t.addedAt)

    internal fun trackFromJson(o: JSONObject): Track? {
        val src = o.optInt("src", Track.SOURCE_LOCAL)
        val rid = o.optString("rid")
        val lid = o.optLong("lid")
        if (src != Track.SOURCE_LOCAL && rid.isBlank()) return null // YouTube id / radio stream URL
        if (src == Track.SOURCE_LOCAL && lid <= 0L) return null
        return Track(
            id = lid, title = o.optString("title"), artist = o.optString("artist"),
            album = o.optString("album"), durationMs = o.optLong("dur"),
            source = src, remoteId = rid, artUrl = o.optString("art"), addedAt = o.optLong("added")
        )
    }

    private fun tracksToJson(list: List<Track>) = JSONArray().also { a -> list.forEach { a.put(trackToJson(it)) } }

    private fun tracksFromJson(a: JSONArray?): List<Track> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { trackFromJson(it) } }
    }

    // ------------------------------------------------------------------ read

    private fun listsFromJson(arr: JSONArray?): List<Playlist> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null else Playlist(id, o.optString("name"), tracksFromJson(o.optJSONArray("tracks")), o.optString("desc"))
        }
    }

    private fun listsToJson(lists: List<Playlist>) = JSONArray().also { arr ->
        lists.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("desc", it.description).put("tracks", tracksToJson(it.tracks)))
        }
    }

    /** As stored (local songs still carry the MediaStore id of the device that added them). */
    @Synchronized
    private fun rawPlaylists(c: Context): List<Playlist> {
        val raw = p(c).getString(KEY_LISTS, null) ?: return emptyList()
        return try { listsFromJson(JSONArray(raw)) } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    private fun rawLiked(c: Context): List<Track> {
        val raw = p(c).getString(KEY_LIKED, null) ?: return emptyList()
        return try { tracksFromJson(JSONArray(raw)) } catch (e: Exception) { emptyList() }
    }

    /** Local songs matched to this device's files (or flagged [Track.missing]), see [LocalMatcher]. */
    fun playlists(c: Context): List<Playlist> =
        rawPlaylists(c).map { pl -> pl.copy(tracks = pl.tracks.map { LocalMatcher.resolve(c, it) }) }

    fun liked(c: Context): List<Track> = rawLiked(c).map { LocalMatcher.resolve(c, it) }

    fun get(c: Context, id: String): Playlist? =
        if (id == LIKED_ID) Playlist(LIKED_ID, "", liked(c)) else playlists(c).firstOrNull { it.id == id }

    fun isLiked(c: Context, t: Track): Boolean = rawLiked(c).any { it.syncKey == t.syncKey }

    // ------------------------------------------------------------------ deletion records
    //
    // Merging two devices is a union, so without a record of what was deleted, a deleted playlist
    // (or removed song) would come back from the other side. Keys: "L:<playlistId>" for a deleted
    // playlist (ids are random and never reused), "T:<listId>|<song syncKey>" for a removed song.
    // A removed song only stays removed if it was not added again afterwards (Track.addedAt).

    private fun loadTombs(c: Context): MutableMap<String, Long> {
        val raw = p(c).getString(KEY_TOMBS, null) ?: return HashMap()
        return try { tombsFromJson(JSONObject(raw)) } catch (e: Exception) { HashMap() }
    }

    private fun tombsFromJson(o: JSONObject?): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        if (o == null) return out
        for (k in o.keys()) { val v = o.optLong(k); if (v > 0) out[k] = v }
        return out
    }

    private fun tombsToJson(m: Map<String, Long>): JSONObject {
        val cutoff = System.currentTimeMillis() - TOMB_TTL_MS
        val o = JSONObject()
        for ((k, v) in m) if (v >= cutoff) o.put(k, v)
        return o
    }

    private fun saveTombs(c: Context, m: Map<String, Long>) {
        p(c).edit().putString(KEY_TOMBS, tombsToJson(m).toString()).apply()
    }

    private fun listTomb(id: String) = "L:$id"
    private fun trackTomb(listId: String, t: Track) = "T:$listId|${t.syncKey}"

    /** True unless [t] was removed from [listId] after it was added. */
    private fun alive(tombs: Map<String, Long>, listId: String, t: Track): Boolean {
        val ts = tombs[trackTomb(listId, t)] ?: return true
        return ts <= t.addedAt
    }

    // ------------------------------------------------------------------ write

    private fun saveLists(c: Context, lists: List<Playlist>) {
        p(c).edit().putString(KEY_LISTS, listsToJson(lists).toString()).apply()
        DriveSync.markDirty(c)
    }

    private fun saveLiked(c: Context, list: List<Track>) {
        p(c).edit().putString(KEY_LIKED, tracksToJson(list).toString()).apply()
        DriveSync.markDirty(c)
    }

    // ------------------------------------------------------------------ Google Drive sync

    /** Everything this store holds (including deletion records), for the Drive backup. */
    @Synchronized
    fun exportJson(c: Context): JSONObject =
        JSONObject().put("lists", listsToJson(rawPlaylists(c))).put("liked", tracksToJson(rawLiked(c)))
            .put("deleted", tombsToJson(loadTombs(c)))

    /**
     * Applies a snapshot. [merge] = false replaces local data; true keeps everything local and adds
     * what only the snapshot has (playlists by id, songs by [Track.syncKey]), minus whatever either
     * side deleted. [fromDrive] = false is a restore from a backup FILE: the user asked for what is
     * in it, so local deletions of those items are forgotten and the file's own deletions ignored.
     * Does not mark the data as changed, so applying a download never triggers an upload by itself.
     */
    @Synchronized
    fun importJson(c: Context, o: JSONObject, merge: Boolean, fromDrive: Boolean = true) {
        val remoteLists = listsFromJson(o.optJSONArray("lists"))
        val remoteLiked = tracksFromJson(o.optJSONArray("liked"))
        val remoteTombs = tombsFromJson(o.optJSONObject("deleted"))
        val lists: List<Playlist>
        val liked: List<Track>
        val tombs: MutableMap<String, Long>
        if (!merge) {
            lists = remoteLists
            liked = remoteLiked
            tombs = remoteTombs
        } else {
            tombs = loadTombs(c)
            if (fromDrive) {
                for ((k, v) in remoteTombs) if (v > (tombs[k] ?: 0L)) tombs[k] = v
            } else {
                remoteLists.forEach { pl ->
                    tombs.remove(listTomb(pl.id))
                    pl.tracks.forEach { tombs.remove(trackTomb(pl.id, it)) }
                }
                remoteLiked.forEach { tombs.remove(trackTomb(LIKED_ID, it)) }
            }
            fun mergeTracks(listId: String, local: List<Track>, remote: List<Track>): List<Track> {
                val have = local.map { it.syncKey }.toHashSet()
                val extra = remote.filter { have.add(it.syncKey) }
                return (local + extra).filter { alive(tombs, listId, it) }
            }
            val local = rawPlaylists(c)
            val localIds = local.map { it.id }.toSet()
            lists = local.filter { listTomb(it.id) !in tombs }.map { l ->
                val remote = remoteLists.firstOrNull { it.id == l.id }?.tracks.orEmpty()
                l.copy(
                    tracks = mergeTracks(l.id, l.tracks, remote),
                    description = l.description.ifBlank { remoteLists.firstOrNull { it.id == l.id }?.description.orEmpty() }
                )
            } + remoteLists.filter { it.id !in localIds && listTomb(it.id) !in tombs }
                .map { it.copy(tracks = mergeTracks(it.id, emptyList(), it.tracks)) }
            liked = mergeTracks(LIKED_ID, rawLiked(c), remoteLiked)
        }
        p(c).edit()
            .putString(KEY_LISTS, listsToJson(lists).toString())
            .putString(KEY_LIKED, tracksToJson(liked).toString())
            .putString(KEY_TOMBS, tombsToJson(tombs).toString())
            .apply()
    }

    @Synchronized
    fun create(c: Context, name: String): Playlist {
        val pl = Playlist(UUID.randomUUID().toString(), name.trim(), emptyList())
        saveLists(c, rawPlaylists(c) + pl)
        return pl
    }

    /** Creates a playlist that already holds [tracks]. If [name] is taken, " (2)", " (3)"... is appended. */
    @Synchronized
    fun createWith(c: Context, name: String, tracks: List<Track>): Playlist {
        val existing = rawPlaylists(c)
        val base = name.trim()
        var candidate = base
        var n = 2
        while (existing.any { it.name == candidate }) candidate = "$base ($n)".also { n++ }
        val now = System.currentTimeMillis()
        val pl = Playlist(
            UUID.randomUUID().toString(), candidate,
            tracks.distinctBy { it.syncKey }.map { if (it.addedAt == 0L) it.copy(addedAt = now) else it }
        )
        saveLists(c, existing + pl)
        return pl
    }

    @Synchronized
    fun rename(c: Context, id: String, name: String) {
        saveLists(c, rawPlaylists(c).map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    @Synchronized
    fun setDescription(c: Context, id: String, text: String) {
        saveLists(c, rawPlaylists(c).map { if (it.id == id) it.copy(description = text.trim()) else it })
    }

    // ---- custom cover image: a small JPEG in the app's own folder (device only)

    fun coverFile(c: Context, id: String): java.io.File =
        java.io.File(java.io.File(c.applicationContext.filesDir, "playlist_covers").also { it.mkdirs() }, "$id.jpg")

    fun hasCover(c: Context, id: String): Boolean = coverFile(c, id).exists()

    /** Saves [bmp] as the cover of [id]; null removes the custom cover. */
    fun setCover(c: Context, id: String, bmp: android.graphics.Bitmap?) {
        val f = coverFile(c, id)
        if (bmp == null) { f.delete(); return }
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        coverFile(c, id).delete()
        saveTombs(c, loadTombs(c).also { it[listTomb(id)] = System.currentTimeMillis() })
        saveLists(c, rawPlaylists(c).filter { it.id != id })
    }

    /** Adds [t] to the end of the list; returns false if it was already in it. */
    @Synchronized
    fun addTrack(c: Context, id: String, t: Track): Boolean {
        val track = t.copy(addedAt = System.currentTimeMillis(), missing = false)
        if (id == LIKED_ID) {
            val cur = rawLiked(c)
            if (cur.any { it.syncKey == t.syncKey }) return false
            saveLiked(c, listOf(track) + cur) // newest like first
            return true
        }
        var added = false
        saveLists(c, rawPlaylists(c).map {
            if (it.id == id && it.tracks.none { x -> x.syncKey == t.syncKey }) {
                added = true
                it.copy(tracks = it.tracks + track)
            } else it
        })
        return added
    }

    @Synchronized
    fun removeTrack(c: Context, id: String, t: Track) {
        saveTombs(c, loadTombs(c).also { it[trackTomb(id, t)] = System.currentTimeMillis() })
        if (id == LIKED_ID) {
            saveLiked(c, rawLiked(c).filter { it.syncKey != t.syncKey })
            return
        }
        saveLists(c, rawPlaylists(c).map {
            if (it.id == id) it.copy(tracks = it.tracks.filter { x -> x.syncKey != t.syncKey }) else it
        })
    }

    /** Likes / un-likes [t]; returns the new state (true = now liked). */
    fun toggleLike(c: Context, t: Track): Boolean {
        return if (isLiked(c, t)) {
            removeTrack(c, LIKED_ID, t); false
        } else {
            addTrack(c, LIKED_ID, t); true
        }
    }
}
