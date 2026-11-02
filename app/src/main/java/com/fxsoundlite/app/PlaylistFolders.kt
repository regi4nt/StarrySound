package com.fxsoundlite.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A folder on the Playlist page that groups own playlists. Folders are flat: they hold playlists only. */
data class PlaylistFolder(val id: String, val name: String)

/**
 * Folders for the user's own playlists. A playlist is in at most one folder; the folder only decides where the row
 * shows on the Playlist page, so the playlist itself (songs, Drive sync, share links, backups) is untouched.
 * Stored on this device only, like [PlaylistPins].
 */
object PlaylistFolders {
    private const val PREFS = "playlist_folders"
    private const val KEY_FOLDERS = "folders"
    private const val KEY_MEMBERS = "members" // playlist id -> folder id

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun folders(c: Context): List<PlaylistFolder> {
        val raw = p(c).getString(KEY_FOLDERS, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) null else PlaylistFolder(id, o.optString("name"))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun get(c: Context, id: String): PlaylistFolder? = folders(c).firstOrNull { it.id == id }

    @Synchronized
    private fun members(c: Context): Map<String, String> {
        val raw = p(c).getString(KEY_MEMBERS, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
        } catch (e: Exception) { emptyMap() }
    }

    private fun save(c: Context, folders: List<PlaylistFolder>, members: Map<String, String>) {
        val a = JSONArray().also { arr -> folders.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) } }
        val m = JSONObject().also { o -> members.forEach { (k, v) -> o.put(k, v) } }
        p(c).edit().putString(KEY_FOLDERS, a.toString()).putString(KEY_MEMBERS, m.toString()).apply()
        DriveSync.markDirty(c)
    }

    /** Folders and which playlist sits in which one, for the Drive backup. */
    @Synchronized
    fun exportJson(c: Context): JSONObject {
        val a = JSONArray().also { arr -> folders(c).forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) } }
        val m = JSONObject().also { o -> members(c).forEach { (k, v) -> o.put(k, v) } }
        return JSONObject().put("folders", a).put("members", m)
    }

    /**
     * Applies the backup's folders. [merge] = false replaces; true keeps this device's folders and placements and
     * adds the ones only the backup has. Does not mark the data as changed (applying a download never uploads).
     */
    @Synchronized
    fun importJson(c: Context, o: JSONObject, merge: Boolean) {
        val inF = ArrayList<PlaylistFolder>()
        o.optJSONArray("folders")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                val id = it.optString("id")
                if (id.isNotBlank()) inF += PlaylistFolder(id, it.optString("name"))
            }
        }
        val inM = HashMap<String, String>()
        o.optJSONObject("members")?.let { m -> m.keys().forEach { k -> m.optString(k).takeIf { v -> v.isNotBlank() }?.let { v -> inM[k] = v } } }
        val newFolders: List<PlaylistFolder>
        val newMembers: Map<String, String>
        if (!merge) { newFolders = inF; newMembers = inM }
        else {
            val mine = folders(c)
            newFolders = mine + inF.filter { f -> mine.none { it.id == f.id } }
            newMembers = inM + members(c)
        }
        val a = JSONArray().also { arr -> newFolders.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) } }
        val m = JSONObject().also { obj -> newMembers.forEach { (k, v) -> obj.put(k, v) } }
        p(c).edit().putString(KEY_FOLDERS, a.toString()).putString(KEY_MEMBERS, m.toString()).apply()
    }

    /** Id of the folder holding [playlistId], or null when it sits in the main list (or its folder is gone). */
    fun folderOf(c: Context, playlistId: String): String? {
        val f = members(c)[playlistId] ?: return null
        return if (folders(c).any { it.id == f }) f else null
    }

    /** Ids of the playlists in [folderId]. */
    fun playlistIds(c: Context, folderId: String): Set<String> =
        members(c).filterValues { it == folderId }.keys

    @Synchronized
    fun create(c: Context, name: String): PlaylistFolder {
        val f = PlaylistFolder(UUID.randomUUID().toString(), name.trim())
        save(c, folders(c) + f, members(c))
        return f
    }

    @Synchronized
    fun rename(c: Context, id: String, name: String) {
        save(c, folders(c).map { if (it.id == id) it.copy(name = name.trim()) else it }, members(c))
    }

    /** Deletes the folder only: its playlists are kept and go back to the main list. */
    @Synchronized
    fun delete(c: Context, id: String) {
        save(c, folders(c).filter { it.id != id }, members(c).filterValues { it != id })
    }

    /** Puts [playlistId] in [folderId]; null takes it out of any folder. */
    @Synchronized
    fun move(c: Context, playlistId: String, folderId: String?) {
        val m = members(c).toMutableMap()
        if (folderId == null) m.remove(playlistId) else m[playlistId] = folderId
        save(c, folders(c), m)
    }

    /** A playlist was deleted: forget where it was. */
    @Synchronized
    fun forget(c: Context, playlistId: String) {
        val m = members(c)
        if (playlistId in m) save(c, folders(c), m - playlistId)
    }
}

/**
 * Remembers which own playlists came from a Spotify import, so the Playlist page can group them in the "Spotify"
 * source folder. Only a marker: the playlists themselves are ordinary ones. Stored on this device only.
 */
object SpotifyLists {
    private const val PREFS = "playlist_spotify_ids"
    private const val KEY = "ids"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun ids(c: Context): Set<String> = HashSet(p(c).getStringSet(KEY, emptySet()) ?: emptySet())

    @Synchronized
    fun add(c: Context, playlistId: String) {
        p(c).edit().putStringSet(KEY, ids(c) + playlistId).apply()
        DriveSync.markDirty(c)
    }

    /** For the Drive backup. */
    fun exportJson(c: Context): JSONArray = JSONArray().also { a -> ids(c).forEach { a.put(it) } }

    /** Applies the backup's Spotify marks (union when [merge]); never marks the data as changed. */
    @Synchronized
    fun importJson(c: Context, a: JSONArray, merge: Boolean) {
        val incoming = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
        p(c).edit().putStringSet(KEY, if (merge) ids(c) + incoming else incoming).apply()
    }

    @Synchronized
    fun forget(c: Context, playlistId: String) {
        val s = ids(c)
        if (playlistId in s) { p(c).edit().putStringSet(KEY, s - playlistId).apply(); DriveSync.markDirty(c) }
    }
}
