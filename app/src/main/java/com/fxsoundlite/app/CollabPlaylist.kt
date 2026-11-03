package com.fxsoundlite.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collaborative ("shared") playlists, like Spotify's: everyone who has the code can add and remove
 * songs, and every phone ends up with the same list.
 *
 * Same backend as [ListenTogether] (Firebase Realtime Database over plain REST, no SDK, no login):
 *
 *   rooms/_collab/<CODE>/meta              {name, desc, owner}   owner = uid of the creator
 *   rooms/_collab/<CODE>/tracks/<hash>     {t: track json, by: uid, bn: name of who added it, at: time}
 *   rooms/_collab/<CODE>/members/<uid>     {n: display name}
 *
 * The local playlist stays a normal [Playlist] in [PlaylistStore] (so playing, sorting, selecting,
 * Drive backup... all keep working). This object only links it to a code and reconciles it with the
 * server with a three-way merge against [Link.base], the set of songs both sides had at the last
 * successful sync:
 *
 *   in local, not in base, not on server  -> added here        -> pushed
 *   in base, not in local, still on server -> removed here     -> deleted on the server
 *   on server, not in base, not in local  -> added elsewhere   -> added here
 *   in base and local, gone from server   -> removed elsewhere -> removed here
 *
 * so two people editing at the same time never overwrite each other and no deletion records are needed.
 * Songs are keyed by a hash of [Track.syncKey]. Only songs that mean the same on every phone are shared
 * (YouTube, radio, Online tab): a file on your device or Drive cannot be played by a friend.
 *
 * Changes made by other people arrive by polling every [POLL_MS] while the playlist page is on screen
 * ([startPolling] / [stopPolling]); your own changes are pushed about a second after you make them.
 * Network work runs on one background thread; callbacks run on the main thread.
 */
object CollabPlaylist {

    enum class Fail { NOT_CONFIGURED, NETWORK, NOT_FOUND, ALREADY, DENIED }

    /** Under "rooms" on purpose: that is the node the Listen together setup already opens in the database rules. */
    private const val ROOT = "rooms/_collab"

    /** HTTP status of the last server answer (0 = none / no connection), to tell "rules refuse" from "offline". */
    @Volatile private var lastCode = 0

    private fun failure() = if (lastCode == 401 || lastCode == 403) Fail.DENIED else Fail.NETWORK

    /** People in a shared playlist and how many songs each added (name "" = no name chosen). */
    class Info(val members: Int, val contributors: List<Pair<String, Int>>)

    private class Link(val code: String, val owner: Boolean, val base: Set<String>)
    private class Remote(val track: Track, val at: Long, val byName: String)
    private class Snapshot(
        val name: String, val desc: String, val owner: String,
        val tracks: LinkedHashMap<String, Remote>, val memberNames: Map<String, String>
    )

    const val CODE_LEN = 8
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PREFS = "collab_prefs"
    private const val POLL_MS = 7000L
    private const val DEBOUNCE_MS = 800L

    /** Called on the main thread when a sync changed a local playlist (so the open screen can redraw). */
    var onRemoteChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val infos = ConcurrentHashMap<String, Info>()
    private val pending = HashMap<String, Runnable>()
    private val busy = AtomicBoolean(false)
    @Volatile private var polling = false
    private var app: Context? = null

    /** Set on the sync thread while it edits [PlaylistStore], so those edits are not pushed back as new changes. */
    private val suppress = ThreadLocal<Boolean>()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ identity + links

    /** Stable anonymous id of this install (people keep their membership between app starts). */
    private fun uid(c: Context): String {
        val p = prefs(c)
        p.getString("uid", null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        p.edit().putString("uid", id).apply()
        return id
    }

    private fun link(c: Context, id: String): Link? {
        val raw = prefs(c).getString("pl:$id", null) ?: return null
        return try {
            val o = JSONObject(raw)
            val base = HashSet<String>()
            o.optJSONArray("base")?.let { a -> for (i in 0 until a.length()) base += a.optString(i) }
            Link(o.getString("code"), o.optBoolean("owner"), base)
        } catch (e: Exception) { null }
    }

    private fun saveLink(c: Context, id: String, l: Link) {
        val o = JSONObject().put("code", l.code).put("owner", l.owner)
            .put("base", JSONArray().also { a -> l.base.forEach { a.put(it) } })
        prefs(c).edit().putString("pl:$id", o.toString()).apply()
    }

    private fun unlink(c: Context, id: String) {
        prefs(c).edit().remove("pl:$id").apply()
        infos.remove(id)
    }

    private fun linkedIds(c: Context): List<String> =
        prefs(c).all.keys.filter { it.startsWith("pl:") }.map { it.removePrefix("pl:") }

    /** The code of a shared playlist, "" when [id] is a normal one. */
    fun codeOf(c: Context, id: String): String = link(c, id)?.code.orEmpty()

    fun isShared(c: Context, id: String) = link(c, id) != null

    fun isOwner(c: Context, id: String) = link(c, id)?.owner == true

    /** Last known people / contributors of a shared playlist (null before the first sync). */
    fun info(id: String): Info? = infos[id]

    /** Songs that mean the same on every phone, so they can be shared. */
    fun shareable(t: Track): Boolean = t.remoteId.isNotBlank() &&
        (t.source == Track.SOURCE_YOUTUBE || t.source == Track.SOURCE_RADIO || t.source == Track.SOURCE_ONLINE)

    // ------------------------------------------------------------------ server access (io thread)

    private fun db(c: Context) = ListenTogether.dbUrl(c)

    private fun get(c: Context, path: String): String? = try {
        Http.request("GET", "${db(c)}/$path.json").also { lastCode = it.code }.takeIf { it.code in 200..299 }?.body?.trim()
    } catch (e: Exception) { lastCode = 0; null }

    private fun put(c: Context, path: String, json: String): Boolean = try {
        Http.request("PUT", "${db(c)}/$path.json", body = json, contentType = "application/json").also { lastCode = it.code }.code in 200..299
    } catch (e: Exception) { lastCode = 0; false }

    private fun delete(c: Context, path: String): Boolean = try {
        Http.request("DELETE", "${db(c)}/$path.json").also { lastCode = it.code }.code in 200..299
    } catch (e: Exception) { lastCode = 0; false }

    private fun serverTime() = JSONObject().put(".sv", "timestamp")

    private fun hash(t: Track): String =
        MessageDigest.getInstance("SHA-1").digest(t.syncKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(20)

    private fun trackBody(t: Track, uid: String, name: String, at: Any): JSONObject =
        JSONObject().put("t", PlaylistStore.trackToJson(t.copy(addedAt = 0L)))
            .put("by", uid).put("bn", name).put("at", at)

    private fun parse(body: String): Snapshot? {
        return try {
            val root = JSONObject(body)
            val meta = root.optJSONObject("meta") ?: return null
            val tracks = LinkedHashMap<String, Remote>()
            root.optJSONObject("tracks")?.let { rt ->
                for (h in rt.keys()) {
                    val o = rt.optJSONObject(h) ?: continue
                    val t = PlaylistStore.trackFromJson(o.optJSONObject("t") ?: continue) ?: continue
                    tracks[h] = Remote(t, o.optLong("at"), o.optString("bn"))
                }
            }
            val names = HashMap<String, String>()
            root.optJSONObject("members")?.let { rm ->
                for (u in rm.keys()) names[u] = rm.optJSONObject(u)?.optString("n").orEmpty()
            }
            Snapshot(meta.optString("name"), meta.optString("desc"), meta.optString("owner"), tracks, names)
        } catch (e: Exception) { null }
    }

    private fun infoOf(s: Snapshot): Info {
        val counts = LinkedHashMap<String, Int>()
        s.tracks.values.forEach { counts[it.byName] = (counts[it.byName] ?: 0) + 1 }
        return Info(s.memberNames.size.coerceAtLeast(1), counts.entries.sortedByDescending { it.value }.map { it.key to it.value })
    }

    private fun randomCode(): String = buildString { repeat(CODE_LEN) { append(ALPHABET.random()) } }

    // ------------------------------------------------------------------ create / join / leave

    /**
     * Makes [id] a shared playlist: picks a free code and uploads the songs that can be shared.
     * [done] (main thread): reason or null, the code, and how many songs were left out (device / Drive files).
     */
    fun share(c: Context, id: String, done: (Fail?, String, Int) -> Unit) {
        link(c, id)?.let { done(null, it.code, 0); return }
        if (!ListenTogether.isConfigured(c)) { done(Fail.NOT_CONFIGURED, "", 0); return }
        val ctx = c.applicationContext
        app = ctx
        io.execute {
            val pl = PlaylistStore.get(ctx, id)
            if (pl == null) { main.post { done(failure(), "", 0) }; return@execute }
            var code = ""
            for (i in 0 until 6) {
                val cand = randomCode()
                val ex = get(ctx, "$ROOT/$cand/meta")
                if (ex == null) { main.post { done(failure(), "", 0) }; return@execute }
                if (ex == "null" || ex.isEmpty()) { code = cand; break }
            }
            if (code.isEmpty()) { main.post { done(failure(), "", 0) }; return@execute }

            val me = uid(ctx)
            val myName = ListenTogether.myName(ctx)
            val send = LinkedHashMap<String, Track>()
            pl.tracks.filter { shareable(it) }.forEach { send.putIfAbsent(hash(it), it) }
            val now = System.currentTimeMillis()
            val tracks = JSONObject()
            var i = 0L
            for ((h, t) in send) tracks.put(h, trackBody(t, me, myName, now + i++)) // keeps the list order
            val root = JSONObject()
                .put("meta", JSONObject().put("name", pl.name).put("desc", pl.description).put("owner", me))
                .put("members", JSONObject().put(me, JSONObject().put("n", myName)))
            if (send.isNotEmpty()) root.put("tracks", tracks)
            if (!put(ctx, "$ROOT/$code", root.toString())) { main.post { done(failure(), "", 0) }; return@execute }

            saveLink(ctx, id, Link(code, true, send.keys))
            val skipped = pl.tracks.size - send.size
            main.post { done(null, code, skipped) }
        }
    }

    /**
     * Joins the shared playlist with [rawCode]: a new local playlist is created with its songs.
     * [done] (main thread): reason or null, and the id of the local playlist (also for [Fail.ALREADY]).
     */
    fun join(c: Context, rawCode: String, done: (Fail?, String) -> Unit) {
        if (!ListenTogether.isConfigured(c)) { done(Fail.NOT_CONFIGURED, ""); return }
        val code = rawCode.trim().uppercase().filter { it in ALPHABET }
        if (code.length != CODE_LEN) { done(Fail.NOT_FOUND, ""); return }
        val ctx = c.applicationContext
        app = ctx
        linkedIds(ctx).firstOrNull { link(ctx, it)?.code == code }?.let { done(Fail.ALREADY, it); return }
        io.execute {
            val body = get(ctx, "$ROOT/$code")
            if (body == null) { main.post { done(failure(), "") }; return@execute }
            val snap = if (body == "null" || body.isEmpty()) null else parse(body)
            if (snap == null) { main.post { done(Fail.NOT_FOUND, "") }; return@execute }

            val pl = PlaylistStore.createWith(ctx, snap.name.ifBlank { code }, snap.tracks.values.sortedBy { it.at }.map { it.track })
            if (snap.desc.isNotBlank()) PlaylistStore.setDescription(ctx, pl.id, snap.desc)
            saveLink(ctx, pl.id, Link(code, snap.owner == uid(ctx), snap.tracks.keys.toSet()))
            put(ctx, "$ROOT/$code/members/${uid(ctx)}", JSONObject().put("n", ListenTogether.myName(ctx)).toString())
            infos[pl.id] = infoOf(snap)
            main.post { done(null, pl.id) }
        }
    }

    /**
     * Stops syncing [id]; the local playlist stays as an ordinary one. With [stopForEveryone] (owner only) the
     * shared list is deleted from the server too, so the others' copies stop syncing as well.
     */
    fun leave(c: Context, id: String, stopForEveryone: Boolean = false, done: () -> Unit = {}) {
        val ctx = c.applicationContext
        val l = link(ctx, id)
        if (l == null) { done(); return }
        unlink(ctx, id) // right away: nothing more is pushed from here
        io.execute {
            if (stopForEveryone && l.owner) delete(ctx, "$ROOT/${l.code}")
            else {
                delete(ctx, "$ROOT/${l.code}/members/${uid(ctx)}")
                val left = get(ctx, "$ROOT/${l.code}/members")
                if (left == "null" || left == "") delete(ctx, "$ROOT/${l.code}") // last one out: clean up
            }
            main.post { done() }
        }
    }

    // ------------------------------------------------------------------ sync

    /** Syncs [id] now (background) and calls [done] on the main thread; [done] gets true if the sync worked. */
    fun refresh(c: Context, id: String, done: (Boolean) -> Unit) {
        val ctx = c.applicationContext
        app = ctx
        io.execute {
            val r = syncNow(ctx, id)
            main.post {
                if (r == Result.CHANGED) onRemoteChange?.invoke()
                done(r != Result.FAILED)
            }
        }
    }

    private enum class Result { SAME, CHANGED, FAILED }

    /** One reconcile of [id] with the server (io thread). */
    private fun syncNow(c: Context, id: String): Result {
        val l = link(c, id) ?: return Result.SAME
        val body = get(c, "$ROOT/${l.code}") ?: return Result.FAILED
        if (body == "null" || body.isEmpty()) { // closed by its owner
            unlink(c, id)
            main.post { Toast.makeText(c, R.string.collab_ended, Toast.LENGTH_LONG).show() }
            return Result.CHANGED
        }
        val snap = parse(body) ?: return Result.FAILED
        val pl = PlaylistStore.get(c, id)
        if (pl == null) { unlink(c, id); return Result.SAME } // the local list was deleted

        val me = uid(c)
        val myName = ListenTogether.myName(c)
        val local = LinkedHashMap<String, Track>()
        pl.tracks.filter { shareable(it) }.forEach { local.putIfAbsent(hash(it), it) }
        val remote = snap.tracks
        val base = l.base

        val toPush = local.keys.filter { it !in base && it !in remote }
        val toDeleteRemote = base.filter { it !in local && it in remote }
        val toAdd = remote.keys.filter { it !in base && it !in local }.sortedBy { remote[it]!!.at }
        val toDropLocal = local.keys.filter { it in base && it !in remote }

        // 1) our changes to the server
        var ok = true
        for (h in toPush) ok = put(c, "$ROOT/${l.code}/tracks/$h", trackBody(local[h]!!, me, myName, serverTime()).toString()) && ok
        for (h in toDeleteRemote) ok = delete(c, "$ROOT/${l.code}/tracks/$h") && ok
        if (l.owner && (pl.name != snap.name || pl.description != snap.desc)) {
            val meta = JSONObject().put("name", pl.name).put("desc", pl.description).put("owner", me)
            ok = put(c, "$ROOT/${l.code}/meta", meta.toString()) && ok
        }
        if (snap.memberNames[me] != myName) ok = put(c, "$ROOT/${l.code}/members/$me", JSONObject().put("n", myName).toString()) && ok

        // 2) other people's changes to this phone
        var changed = false
        suppress.set(true)
        try {
            if (toAdd.isNotEmpty()) { PlaylistStore.addTracks(c, id, toAdd.map { remote[it]!!.track }); changed = true }
            if (toDropLocal.isNotEmpty()) { PlaylistStore.removeTracks(c, id, toDropLocal.map { local[it]!! }); changed = true }
            if (!l.owner) {
                if (snap.name.isNotBlank() && snap.name != pl.name) { PlaylistStore.rename(c, id, snap.name); changed = true }
                if (snap.desc != pl.description) { PlaylistStore.setDescription(c, id, snap.desc); changed = true }
            }
        } finally { suppress.remove() }

        // 3) remember what both sides have now (only after every write worked, so a failure is simply retried)
        if (ok) saveLink(c, id, Link(l.code, l.owner, (local.keys - toDropLocal.toSet()) + toAdd))
        infos[id] = infoOf(snap)
        return when { !ok -> Result.FAILED; changed -> Result.CHANGED; else -> Result.SAME }
    }

    private fun syncAll() {
        val ctx = app ?: return
        val ids = linkedIds(ctx)
        if (ids.isEmpty() || !busy.compareAndSet(false, true)) return
        io.execute {
            var changed = false
            try { for (id in ids) if (syncNow(ctx, id) == Result.CHANGED) changed = true }
            finally { busy.set(false) }
            if (changed) main.post { onRemoteChange?.invoke() }
        }
    }

    // ------------------------------------------------------------------ hooks from PlaylistStore

    /** [PlaylistStore] changed the songs / name / description of [id]: push it soon (no-op for ordinary playlists). */
    fun localChanged(c: Context, id: String) {
        if (suppress.get() == true) return
        val ctx = c.applicationContext
        if (prefs(ctx).getString("pl:$id", null) == null) return
        app = ctx
        main.post {
            pending.remove(id)?.let { main.removeCallbacks(it) }
            val r = Runnable {
                pending.remove(id)
                io.execute { if (syncNow(ctx, id) == Result.CHANGED) main.post { onRemoteChange?.invoke() } }
            }
            pending[id] = r
            main.postDelayed(r, DEBOUNCE_MS)
        }
    }

    /** The user deleted the local playlist: leave the shared one (it goes on for the others). */
    fun localDeleted(c: Context, id: String) {
        if (prefs(c).getString("pl:$id", null) != null) leave(c, id)
    }

    // ------------------------------------------------------------------ polling

    private val tick = object : Runnable {
        override fun run() {
            if (!polling) return
            syncAll()
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Starts checking the server for changes (call while the playlist page is visible). */
    fun startPolling(c: Context) {
        app = c.applicationContext
        if (polling) return
        polling = true
        main.post(tick)
    }

    fun stopPolling() {
        polling = false
        main.removeCallbacks(tick)
    }
}
