package com.fxsoundlite.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Blend, like Spotify's: two or more people join with a code and get one playlist that mixes everybody's taste,
 * together with a match score ("72% match") and the artists they have in common.
 *
 * Same backend as [CollabPlaylist] and [ListenTogether] (Firebase Realtime Database over plain REST, no SDK, no login):
 *
 *   rooms/_blend/<CODE>/meta               {owner, at}
 *   rooms/_blend/<CODE>/members/<uid>      {n: name, at: time, a: [{n: artist, w: weight}], t: [track json]}
 *
 * Each phone publishes only a small taste snapshot of its owner (top artists with weights + a few online songs
 * that anyone can play). Nobody edits a shared list: every phone builds the blend playlist locally from the
 * snapshots of all members, so a refresh just recomputes it. The blend playlist is an ordinary [Playlist]
 * in [PlaylistStore] (playing, sorting, Drive backup... all work); this object only links it to a code.
 *
 * Match score: cosine similarity of the members' artist vectors (square-root damped weights, so one
 * artist played 500 times does not drown out the rest), averaged over every pair and lifted with a square root, because two people
 * who share a few artists out of dozens are already a good match. It is a fun heuristic, not science.
 *
 * Network work runs on one background thread; callbacks run on the main thread.
 */
object PlaylistBlend {

    private const val ROOT = "rooms/_blend"
    private const val PREFS = "blend_prefs"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LEN = 6
    const val MAX_MEMBERS = 8

    private const val PROFILE_ARTISTS = 30
    private const val PROFILE_TRACKS = 40
    private const val BLEND_SIZE = 40
    private const val DISCOVER_ARTISTS = 3
    private const val DISCOVER_PER_ARTIST = 2
    private const val MIN_MS = 60_000L
    private const val MAX_MS = 9 * 60_000L

    /** Result of the last blend computation of one playlist. */
    class Summary(val percent: Int, val names: List<String>, val sharedArtists: List<String>)

    private class Member(
        val uid: String, val name: String,
        val artists: Map<String, Pair<String, Int>>, // normalized artist -> (display name, weight)
        val tracks: List<Track>
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val summaries = ConcurrentHashMap<String, Summary>()

    @Volatile private var lastCode = 0

    private fun failure() =
        if (lastCode == 401 || lastCode == 403) CollabPlaylist.Fail.DENIED else CollabPlaylist.Fail.NETWORK

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ identity + links

    private fun uid(c: Context): String {
        val p = prefs(c)
        p.getString("uid", null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        p.edit().putString("uid", id).apply()
        return id
    }

    /** The Blend code of playlist [id], "" when it is an ordinary playlist. */
    fun codeOf(c: Context, id: String): String = prefs(c).getString("pl:$id", null).orEmpty()

    fun isBlend(c: Context, id: String) = codeOf(c, id).isNotEmpty()

    /** Last computed match in percent, or -1 before the first one (survives app restarts). */
    fun percentOf(c: Context, id: String): Int = summaries[id]?.percent ?: prefs(c).getInt("pct:$id", -1)

    fun summary(id: String): Summary? = summaries[id]

    private fun link(c: Context, id: String, code: String) {
        prefs(c).edit().putString("pl:$id", code).apply()
    }

    private fun unlink(c: Context, id: String) {
        prefs(c).edit().remove("pl:$id").remove("pct:$id").apply()
        summaries.remove(id)
    }

    private fun linkedIds(c: Context): List<String> =
        prefs(c).all.keys.filter { it.startsWith("pl:") }.map { it.removePrefix("pl:") }

    // ------------------------------------------------------------------ server access (io thread)

    private fun db(c: Context) = ListenTogether.dbUrl(c)

    private fun get(c: Context, path: String): String? = try {
        Http.request("GET", "${db(c)}/$path.json").also { lastCode = it.code }.takeIf { it.code in 200..299 }?.body?.trim()
    } catch (e: Exception) { lastCode = 0; null }

    private fun put(c: Context, path: String, json: String): Boolean = try {
        Http.request("PUT", "${db(c)}/$path.json", body = json, contentType = "application/json")
            .also { lastCode = it.code }.code in 200..299
    } catch (e: Exception) { lastCode = 0; false }

    private fun delete(c: Context, path: String): Boolean = try {
        Http.request("DELETE", "${db(c)}/$path.json").also { lastCode = it.code }.code in 200..299
    } catch (e: Exception) { lastCode = 0; false }

    private fun randomCode(): String = buildString { repeat(CODE_LEN) { append(ALPHABET.random()) } }

    private fun cleanCode(raw: String) = raw.trim().uppercase().filter { it in ALPHABET }

    // ------------------------------------------------------------------ my taste snapshot (io thread)

    /** Builds this phone's snapshot as [Member] data: top artists with weights and the online songs worth sharing. */
    private fun myProfile(c: Context): Pair<Map<String, Pair<String, Int>>, List<Track>> {
        LastFm.refreshTaste(c) // blocking, only hits the network when the cached taste is stale
        val liked = PlaylistStore.liked(c)
        val history = MusicPrefs.youtubeHistory(c)
        val weight = HashMap<String, Int>()
        val display = HashMap<String, String>()
        PlaylistGenerator.tasteWeights(c, liked, history, weight, display)
        val artists = LinkedHashMap<String, Pair<String, Int>>()
        weight.entries.sortedByDescending { it.value }.take(PROFILE_ARTISTS)
            .forEach { artists[it.key] = (display[it.key] ?: it.key) to it.value }

        // Songs: liked ones first (what you love), then recent history. Only what a friend's phone can play too.
        val seen = HashSet<String>()
        val tracks = (liked + history)
            .filter { CollabPlaylist.shareable(it) && it.source != Track.SOURCE_RADIO && seen.add(it.syncKey) }
            .take(PROFILE_TRACKS)
            .map { it.copy(addedAt = 0L, missing = false) }
        return artists to tracks
    }

    private fun publish(c: Context, code: String, name: String, profile: Pair<Map<String, Pair<String, Int>>, List<Track>>): Boolean {
        val a = JSONArray().also { arr ->
            profile.first.values.forEach { (n, w) -> arr.put(JSONObject().put("n", n).put("w", w)) }
        }
        val t = JSONArray().also { arr -> profile.second.forEach { arr.put(PlaylistStore.trackToJson(it)) } }
        val body = JSONObject().put("n", name).put("at", System.currentTimeMillis()).put("a", a).put("t", t)
        return put(c, "$ROOT/$code/members/${uid(c)}", body.toString())
    }

    private fun parseMembers(body: String): List<Member>? = try {
        val root = JSONObject(body)
        val ms = root.optJSONObject("members") ?: return null
        val out = ArrayList<Member>()
        for (u in ms.keys()) {
            val o = ms.optJSONObject(u) ?: continue
            val artists = LinkedHashMap<String, Pair<String, Int>>()
            o.optJSONArray("a")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    val n = PlaylistGenerator.normArtist(x.optString("n"))
                    if (n.isNotEmpty()) artists[n] = x.optString("n") to x.optInt("w", 1).coerceAtLeast(1)
                }
            }
            val tracks = ArrayList<Track>()
            o.optJSONArray("t")?.let { t ->
                for (i in 0 until t.length()) t.optJSONObject(i)?.let { j -> PlaylistStore.trackFromJson(j)?.let { tracks += it } }
            }
            out += Member(u, o.optString("n"), artists, tracks)
        }
        out
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ the blend itself (pure, no network)

    private fun vec(m: Member): Map<String, Double> = m.artists.mapValues { sqrt(it.value.second.toDouble()) }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0
        for ((k, v) in a) b[k]?.let { dot += v * it }
        val na = sqrt(a.values.sumOf { it * it })
        val nb = sqrt(b.values.sumOf { it * it })
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
    }

    /** 0..100. One member alone has no match yet (0). */
    private fun matchPercent(members: List<Member>): Int {
        if (members.size < 2) return 0
        val v = members.map { vec(it) }
        var sum = 0.0
        var n = 0
        for (i in v.indices) for (j in i + 1 until v.size) { sum += cosine(v[i], v[j]); n++ }
        return (sqrt(sum / n) * 100).roundToInt().coerceIn(0, 100)
    }

    /** Artists at least two members listen to, the most "shared" first. */
    private fun sharedArtists(members: List<Member>): List<String> {
        val keys = members.flatMap { it.artists.keys }.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
        return keys.sortedByDescending { k -> members.sumOf { m -> m.artists[k]?.second?.let { sqrt(it.toDouble()) } ?: 0.0 } }
            .map { k -> members.firstNotNullOf { it.artists[k]?.first } }
    }

    /**
     * Songs of everybody, mixed: each member's songs are ranked (artists the group has in common first, then by how
     * much that member plays the artist) and taken in turns, so every person is heard evenly.
     */
    private fun mix(members: List<Member>, shared: Set<String>): List<Track> {
        val pools = members.map { m ->
            m.tracks.sortedByDescending { t ->
                val k = PlaylistGenerator.normArtist(t.artist)
                (if (k in shared) 1000.0 else 0.0) + sqrt((m.artists[k]?.second ?: 0).toDouble())
            }.toMutableList()
        }
        val out = LinkedHashMap<String, Track>()
        val seen = HashSet<String>()
        fun add(t: Track) {
            if (out.size >= BLEND_SIZE) return
            if (seen.add(PlaylistGenerator.normTitle(t.title) + "|" + PlaylistGenerator.normArtist(t.artist))) out.putIfAbsent(t.syncKey, t)
        }
        while (out.size < BLEND_SIZE && pools.any { it.isNotEmpty() }) {
            for (p in pools) if (p.isNotEmpty()) add(p.removeAt(0))
        }
        return out.values.toList()
    }

    /** A few new songs by the artists the group shares (or, without any, by each member's favourite). Never throws. */
    private fun discover(c: Context, members: List<Member>, shared: List<String>, have: Set<String>): List<Track> {
        val names: List<String> = shared.take(DISCOVER_ARTISTS).ifEmpty {
            members.mapNotNull { m -> m.artists.values.maxByOrNull { it.second }?.first }.distinct().take(DISCOVER_ARTISTS)
        }
        val found = ArrayList<Track>()
        for (name in names) {
            val n = PlaylistGenerator.normArtist(name)
            try {
                YouTubeApi.search(c, "$name official audio", maxResults = 15)
                    .filter { it.durationMs in MIN_MS..MAX_MS && it.syncKey !in have }
                    .filter { PlaylistGenerator.normArtist(it.artist).let { a -> a.contains(n) || n.contains(a) } }
                    .take(DISCOVER_PER_ARTIST)
                    .forEach { found += it }
            } catch (e: Exception) { /* offline / quota: the blend just has fewer new songs */ }
        }
        return found
    }

    // ------------------------------------------------------------------ create / join / refresh / leave

    /** Applies [members] to the local playlist [id] (io thread). Returns the summary. */
    private fun build(c: Context, id: String, members: List<Member>, me: String): Summary {
        // "me" first so the order of the interleave starts with the person holding the phone.
        val ordered = members.sortedBy { if (it.uid == me) 0 else 1 }
        val shared = sharedArtists(ordered)
        val sharedKeys = shared.map { PlaylistGenerator.normArtist(it) }.toSet()
        val base = mix(ordered, sharedKeys)
        val extra = if (ordered.size >= 2) discover(c, ordered, shared, base.map { it.syncKey }.toSet()) else emptyList()
        // Spread the new songs through the list instead of piling them at the end.
        val tracks = ArrayList(base)
        extra.forEachIndexed { i, t ->
            tracks.add(((i + 1) * (tracks.size + 1) / (extra.size + 1)).coerceAtMost(tracks.size), t)
        }

        val names = ordered.map { it.name.ifBlank { c.getString(R.string.blend_anon) } }
        val percent = matchPercent(ordered)
        val pretty = ordered.mapIndexed { i, m ->
            if (m.uid == me) c.getString(R.string.blend_you) else names[i]
        }
        val sum = Summary(percent, pretty, shared.take(6))

        PlaylistStore.replaceTracks(c, id, tracks)
        PlaylistStore.rename(c, id, c.getString(R.string.blend_playlist_name, pretty.joinToString(" + ")))
        val desc = if (ordered.size < 2) c.getString(R.string.blend_desc_waiting)
        else c.getString(R.string.blend_desc, percent) +
            if (shared.isNotEmpty()) "\n" + c.getString(R.string.blend_desc_shared, shared.take(4).joinToString(", ")) else ""
        PlaylistStore.setDescription(c, id, desc)

        summaries[id] = sum
        prefs(c).edit().putInt("pct:$id", percent).apply()
        return sum
    }

    /**
     * Starts a new Blend: publishes this phone's taste under a fresh code and creates the (still one-person) blend
     * playlist. [done] (main thread): reason or null, the code, and the id of the local playlist.
     */
    fun create(c: Context, done: (CollabPlaylist.Fail?, String, String) -> Unit) {
        if (!ListenTogether.isConfigured(c)) { done(CollabPlaylist.Fail.NOT_CONFIGURED, "", ""); return }
        val ctx = c.applicationContext
        io.execute {
            var code = ""
            for (i in 0 until 6) {
                val cand = randomCode()
                val ex = get(ctx, "$ROOT/$cand/meta")
                if (ex == null) { main.post { done(failure(), "", "") }; return@execute }
                if (ex == "null" || ex.isEmpty()) { code = cand; break }
            }
            if (code.isEmpty()) { main.post { done(failure(), "", "") }; return@execute }

            val me = uid(ctx)
            val name = ListenTogether.myName(ctx)
            val profile = myProfile(ctx)
            val meta = JSONObject().put("owner", me).put("at", System.currentTimeMillis())
            if (!put(ctx, "$ROOT/$code/meta", meta.toString()) || !publish(ctx, code, name, profile)) {
                main.post { done(failure(), "", "") }; return@execute
            }
            val pl = PlaylistStore.createWith(ctx, ctx.getString(R.string.blend_playlist_name, ctx.getString(R.string.blend_you)), emptyList())
            link(ctx, pl.id, code)
            build(ctx, pl.id, listOf(Member(me, name, profile.first, profile.second)), me)
            main.post { done(null, code, pl.id) }
        }
    }

    /**
     * Joins the Blend with [rawCode]: publishes this phone's taste, then builds the blend playlist.
     * [done] (main thread): reason or null, and the id of the local playlist (also for [CollabPlaylist.Fail.ALREADY]).
     */
    fun join(c: Context, rawCode: String, done: (CollabPlaylist.Fail?, String) -> Unit) {
        if (!ListenTogether.isConfigured(c)) { done(CollabPlaylist.Fail.NOT_CONFIGURED, ""); return }
        val code = cleanCode(rawCode)
        if (code.length != CODE_LEN) { done(CollabPlaylist.Fail.NOT_FOUND, ""); return }
        val ctx = c.applicationContext
        linkedIds(ctx).firstOrNull { codeOf(ctx, it) == code }?.let { done(CollabPlaylist.Fail.ALREADY, it); return }
        io.execute {
            val meta = get(ctx, "$ROOT/$code/meta")
            if (meta == null) { main.post { done(failure(), "") }; return@execute }
            if (meta == "null" || meta.isEmpty()) { main.post { done(CollabPlaylist.Fail.NOT_FOUND, "") }; return@execute }
            val me = uid(ctx)
            val cur = get(ctx, "$ROOT/$code")?.let { parseMembers(it) } ?: emptyList()
            if (cur.size >= MAX_MEMBERS && cur.none { it.uid == me }) {
                main.post { done(CollabPlaylist.Fail.DENIED, "") }; return@execute
            }
            val profile = myProfile(ctx)
            val name = ListenTogether.myName(ctx)
            if (!publish(ctx, code, name, profile)) { main.post { done(failure(), "") }; return@execute }

            val all = cur.filter { it.uid != me } + Member(me, name, profile.first, profile.second)
            val pl = PlaylistStore.createWith(ctx, ctx.getString(R.string.blend_playlist_name, ctx.getString(R.string.blend_you)), emptyList())
            link(ctx, pl.id, code)
            build(ctx, pl.id, all, me)
            main.post { done(null, pl.id) }
        }
    }

    /**
     * Re-publishes this phone's taste and rebuilds the blend of [id] from the latest snapshots of everybody.
     * [done] (main thread): the new summary, or null when it failed (offline) or the Blend was closed.
     */
    fun refresh(c: Context, id: String, done: (Summary?) -> Unit) {
        val ctx = c.applicationContext
        val code = codeOf(ctx, id)
        if (code.isEmpty()) { done(null); return }
        io.execute {
            val me = uid(ctx)
            val body = get(ctx, "$ROOT/$code")
            if (body == null) { main.post { done(null) }; return@execute }
            if (body == "null" || body.isEmpty()) { // the room is gone: keep the playlist as an ordinary one
                unlink(ctx, id)
                main.post { done(null) }
                return@execute
            }
            val profile = myProfile(ctx)
            publish(ctx, code, ListenTogether.myName(ctx), profile) // a failed publish only means friends see older data
            val fresh = get(ctx, "$ROOT/$code")?.let { parseMembers(it) }
            if (fresh == null || PlaylistStore.get(ctx, id) == null) { main.post { done(null) }; return@execute }
            val sum = build(ctx, id, fresh, me)
            main.post { done(sum) }
        }
    }

    /** Leaves the Blend: the local playlist stays as an ordinary one, our snapshot is removed from the server. */
    fun leave(c: Context, id: String, done: () -> Unit = {}) {
        val ctx = c.applicationContext
        val code = codeOf(ctx, id)
        if (code.isEmpty()) { done(); return }
        unlink(ctx, id)
        io.execute {
            delete(ctx, "$ROOT/$code/members/${uid(ctx)}")
            val left = get(ctx, "$ROOT/$code/members")
            if (left == "null" || left == "") delete(ctx, "$ROOT/$code") // last one out: clean up
            main.post { done() }
        }
    }

    /** The user deleted the local playlist: leave the Blend too. */
    fun localDeleted(c: Context, id: String) {
        if (isBlend(c, id)) leave(c, id)
    }
}
