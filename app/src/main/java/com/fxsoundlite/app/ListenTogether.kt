package com.fxsoundlite.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * "Listen together": a host and guests play the same YouTube song at the same position.
 *
 * No audio is sent: every phone plays the song itself, only the playback STATE travels through a
 * Firebase Realtime Database, used over its plain REST API (no SDK, no google-services.json):
 *
 *   rooms/<CODE>/host            uid of the host
 *   rooms/<CODE>/state           what the host is playing: video id, title, position, playing, server time
 *   rooms/<CODE>/members/<uid>/t presence heartbeat (server timestamp)
 *   rooms/<CODE>/members/<uid>/n optional display name the person chose (see [setMyName])
 *   rooms/<CODE>/requests/<id>   a song a guest suggests (the host moves it into its own queue, then deletes it)
 *   rooms/<CODE>/upnext          the songs queued after the current one (title + artist), written by the host
 *
 * The host writes the whole `state` object whenever the song / play state changes or the position
 * jumps; guests listen to it with a server-sent-events stream and follow. Positions are tied to the
 * database's own clock (offset measured when joining), so a phone with a wrong clock still lines up.
 * Only the host controls playback; guests can leave at any time. YouTube songs are synced as they are; a
 * local / Drive song of the host is looked up on YouTube by every guest (title + artist, closest length).
 *
 * All service calls and UI callbacks run on the main thread; network work runs on [io].
 */
object ListenTogether {

    enum class Role { NONE, HOST, GUEST }

    @Volatile var role = Role.NONE
        private set
    @Volatile var code = ""
        private set
    /** People in the room (host included), from the presence heartbeat. */
    @Volatile var members = 0
        private set
    /** Anonymous ids of the people in the room (host included), from the presence heartbeat. */
    @Volatile var memberIds: List<String> = emptyList()
        private set
    /** Display names chosen by the people in the room (id -> name); people without one are not in here. */
    @Volatile var memberNames: Map<String, String> = emptyMap()
        private set
    /** Id of the host (equals [myId] when we are the host). */
    @Volatile var hostId = ""
        private set
    /** Songs that play after the current one (title to artist), written by the host, shown to everyone. */
    @Volatile var upNext: List<Pair<String, String>> = emptyList()
        private set
    /** Our own anonymous id in the current room. */
    val myId: String get() = uid

    /** Called on the main thread whenever role / members / the room's ended state change. */
    var onChange: (() -> Unit)? = null

    private class State(
        val vid: String, val title: String, val artist: String, val art: String, val dur: Long,
        val playing: Boolean, val pos: Long, val ref: Long,
        /** Search text for songs that are not on YouTube (local / Drive): the guest looks them up there. */
        val q: String = "",
        /** "" = YouTube, "online" = Online-tab song, "radio" = live radio station. For the last two [vid] is "u:" + the audio link. */
        val src: String = ""
    )

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val DRIFT_MS = 2000L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private var app: Context? = null
    private var base = ""
    private var uid = ""
    @Volatile private var offsetMs = 0L
    @Volatile private var session = 0
    private var streamConn: HttpURLConnection? = null

    // host
    private var lastPushed: State? = null
    // guest
    private var lastState: State? = null
    /** Host song id ("q:...") -> the YouTube track a guest found for it ("" remoteId = nothing found). */
    private val resolved = HashMap<String, Track>()
    private val resolving = HashSet<String>()
    private var needsInitial = false
    private var lastSeekAt = 0L
    private var lastUpNextJson = ""

    // ------------------------------------------------------------------ config

    private fun prefs(c: Context) = c.getSharedPreferences("listen_together", Context.MODE_PRIVATE)

    const val MAX_NAME = 24

    /** The name other people see for us; blank = none chosen (they see "Host" / "Listener N"). */
    fun myName(c: Context): String = prefs(c).getString("name", "").orEmpty()

    /**
     * Saves our display name and, inside a room, shows it to everyone right away. Blank removes it.
     * Names are trimmed, stripped of control characters and cut to [MAX_NAME] characters.
     */
    fun setMyName(c: Context, raw: String) {
        val name = raw.filter { !it.isISOControl() }.trim().take(MAX_NAME).trim()
        prefs(c).edit().putString("name", name).apply()
        if (!active) return
        val room = code
        val me = uid
        val my = session
        memberNames = if (name.isEmpty()) memberNames - me else memberNames + (me to name)
        onChange?.invoke()
        io.execute { if (my == session) publishName(room, me, name) }
    }

    /** Writes (or removes) our name in the room (io thread). */
    private fun publishName(room: String, me: String, name: String) {
        if (name.isEmpty()) delete("rooms/$room/members/$me/n")
        else put("rooms/$room/members/$me/n", JSONObject.quote(name))
    }

    /** Database URL: what the user typed, else the one baked into res/values/listen_config.xml. */
    fun dbUrl(c: Context): String {
        val typed = prefs(c).getString("db", "").orEmpty().trim()
        val v = typed.ifBlank { c.getString(R.string.listen_default_db_url).trim() }
        return v.trimEnd('/')
    }

    fun setDbUrl(c: Context, url: String) {
        prefs(c).edit().putString("db", url.trim()).apply()
    }

    fun isConfigured(c: Context) = dbUrl(c).startsWith("https://")

    val active: Boolean get() = role != Role.NONE

    // ------------------------------------------------------------------ REST helpers (io thread)

    private fun put(path: String, json: String): Http.Response? = try {
        Http.request("PUT", "$base/$path.json", body = json, contentType = "application/json")
            .takeIf { it.code in 200..299 }
    } catch (e: Exception) { null }

    private fun get(path: String): String? = try {
        Http.request("GET", "$base/$path.json").takeIf { it.code in 200..299 }?.body?.trim()
    } catch (e: Exception) { null }

    private fun delete(path: String) {
        try { Http.request("DELETE", "$base/$path.json") } catch (_: Exception) { }
    }

    /** Server time minus local time, measured with one write/read round trip. */
    private fun syncClock(room: String): Boolean {
        val t0 = System.currentTimeMillis()
        val r = put("rooms/$room/members/$uid/t", "{\".sv\":\"timestamp\"}") ?: return false
        val t1 = System.currentTimeMillis()
        val server = r.body.trim().toLongOrNull() ?: return false
        offsetMs = server - (t0 + t1) / 2
        return true
    }

    private fun serverNow() = System.currentTimeMillis() + offsetMs

    // ------------------------------------------------------------------ start / leave

    /** Result of [host] / [join]: null = ok, else a short reason for the toast. */
    enum class Fail { NOT_CONFIGURED, NETWORK, NOT_FOUND, BUSY }

    fun host(c: Context, done: (Fail?, String) -> Unit) {
        if (active) { done(Fail.BUSY, ""); return }
        if (!isConfigured(c)) { done(Fail.NOT_CONFIGURED, ""); return }
        base = dbUrl(c)
        app = c.applicationContext
        val my = ++session
        uid = randomId(10)
        io.execute {
            var room = ""
            for (i in 0 until 6) {
                val candidate = randomId(6, ALPHABET)
                val existing = get("rooms/$candidate/host") ?: run { fail(my, Fail.NETWORK, done); return@execute }
                if (existing == "null" || existing.isEmpty()) { room = candidate; break }
            }
            if (room.isEmpty()) { fail(my, Fail.NETWORK, done); return@execute }
            if (put("rooms/$room/host", JSONObject.quote(uid)) == null || !syncClock(room)) {
                fail(my, Fail.NETWORK, done); return@execute
            }
            main.post {
                if (my != session) return@post
                code = room
                role = Role.HOST
                members = 1
                hostId = uid
                memberIds = listOf(uid)
                lastPushed = null
                MusicService.addGlobalListener(hostListener)
                hostTick(force = true)
                main.postDelayed(hostLoop, 2000)
                startPresence(my)
                startQueueSync(my)
                done(null, room)
                onChange?.invoke()
            }
        }
    }

    fun join(c: Context, rawCode: String, done: (Fail?, String) -> Unit) {
        if (active) { done(Fail.BUSY, ""); return }
        if (!isConfigured(c)) { done(Fail.NOT_CONFIGURED, ""); return }
        val room = rawCode.trim().uppercase().filter { it in ALPHABET }
        if (room.length != 6) { done(Fail.NOT_FOUND, ""); return }
        base = dbUrl(c)
        app = c.applicationContext
        val my = ++session
        uid = randomId(10)
        io.execute {
            val host = get("rooms/$room/host") ?: run { fail(my, Fail.NETWORK, done); return@execute }
            if (host == "null" || host.isEmpty()) { fail(my, Fail.NOT_FOUND, done); return@execute }
            if (!syncClock(room)) { fail(my, Fail.NETWORK, done); return@execute }
            main.post {
                if (my != session) return@post
                code = room
                role = Role.GUEST
                members = 1
                hostId = host.trim().trim('"')
                memberIds = listOf(hostId, uid).distinct()
                lastState = null
                resolved.clear(); resolving.clear()
                needsInitial = false
                main.postDelayed(guestLoop, 1500)
                io.execute { streamLoop(my) }
                startPresence(my)
                startQueueSync(my)
                done(null, room)
                onChange?.invoke()
            }
        }
    }

    private fun fail(my: Int, why: Fail, done: (Fail?, String) -> Unit) {
        main.post { if (my == session) done(why, "") }
    }

    /** Leaves the room. The host closing the room removes it, which ends it for the guests too. */
    fun leave() {
        if (!active) return
        val wasHost = role == Role.HOST
        val room = code
        val me = uid
        session++ // every loop / stream of the old session stops
        role = Role.NONE
        code = ""
        members = 0
        hostId = ""
        memberIds = emptyList()
        memberNames = emptyMap()
        main.removeCallbacks(hostLoop)
        main.removeCallbacks(guestLoop)
        MusicService.removeGlobalListener(hostListener)
        try { streamConn?.disconnect() } catch (_: Exception) { }
        streamConn = null
        lastPushed = null
        lastState = null
        upNext = emptyList()
        lastUpNextJson = ""
        resolved.clear(); resolving.clear()
        io.execute { if (wasHost) delete("rooms/$room") else delete("rooms/$room/members/$me") }
        onChange?.invoke()
    }

    private fun endedByRemote() {
        if (!active) return
        leave()
        app?.let { android.widget.Toast.makeText(it, R.string.listen_ended, android.widget.Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ host side

    private val hostListener = object : MusicService.Listener {
        override fun onMusicStateChanged() { hostTick(force = false) }
    }

    private val hostLoop = object : Runnable {
        override fun run() {
            if (role != Role.HOST) return
            hostTick(force = false)
            main.postDelayed(this, 2000)
        }
    }

    /** Pushes the host's state when the song / play state changed or the position jumped. */
    private fun hostTick(force: Boolean) {
        if (role != Role.HOST) return
        val svc = MusicService.instance
        val t = svc?.current
        val yt = svc != null && t != null && t.source == Track.SOURCE_YOUTUBE && t.remoteId.isNotBlank()
        // A local / Drive song cannot be streamed to guests: they search it on YouTube instead.
        val alt = !yt && svc != null && t != null && t.title.isNotBlank() &&
            (t.source == Track.SOURCE_LOCAL || t.source == Track.SOURCE_DRIVE)
        // Online-tab songs and radio stations are plain https audio links: guests play the same link.
        val link = !yt && !alt && svc != null && t != null &&
            (t.source == Track.SOURCE_ONLINE || t.source == Track.SOURCE_RADIO) && t.remoteId.startsWith("http")
        val live = link && t!!.source == Track.SOURCE_RADIO // a live stream has no position to sync
        val has = yt || alt || link
        val vid = if (yt) t!!.remoteId else if (alt) "q:" + t!!.key else if (link) "u:" + t!!.remoteId else ""
        val playing = has && svc!!.isPlaying
        val pos = if (has && !live) svc!!.position.toLong() else 0L
        val now = serverNow()
        val last = lastPushed
        val expected = when {
            last == null -> 0L
            last.playing -> last.pos + (now - last.ref)
            else -> last.pos
        }
        val changed = last == null || last.vid != vid || last.playing != playing || (!live && abs(pos - expected) > 1500)
        if (!changed && !force) return
        val st = State(
            vid, if (has) t!!.title else "", if (has) t!!.artist else "", if (has) t!!.artUrl else "",
            if (has) svc!!.duration.toLong() else 0L, playing, pos, now,
            if (alt) "${t!!.title} ${t.artist}".trim() else "",
            if (live) "radio" else if (link) "online" else ""
        )
        lastPushed = st
        val json = JSONObject()
            .put("vid", st.vid).put("title", st.title).put("artist", st.artist).put("art", st.art)
            .put("dur", st.dur).put("playing", st.playing).put("pos", st.pos).put("ref", st.ref)
            .put("q", st.q).put("src", st.src)
            .toString()
        val room = code
        io.execute { put("rooms/$room/state", json) }
    }

    // ------------------------------------------------------------------ guest side

    /** Server-sent-events stream of rooms/<code>/state; reconnects until the session ends. */
    private fun streamLoop(my: Int) {
        var failures = 0
        var haveState = false
        cache = null
        while (my == session) {
            try {
                val conn = URL("$base/rooms/$code/state.json").openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 10_000
                conn.readTimeout = 70_000 // Firebase sends a keep-alive about every 30 s
                streamConn = conn
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    failures = 0
                    var event = ""
                    while (my == session) {
                        val line = r.readLine() ?: break
                        when {
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                if (event == "put" || event == "patch") {
                                    val data = line.substring(5).trim()
                                    val o = try { JSONObject(data) } catch (e: Exception) { null }
                                    if (o != null) {
                                        // Firebase streams DELTAS: a put / patch can target a child ("/pos") or carry
                                        // only the changed fields, so merge into a local copy of the state.
                                        val path = o.optString("path", "/")
                                        val value: Any? = if (o.isNull("data")) null else o.opt("data")
                                        cache = mergeState(cache, path, value, event == "patch")
                                        val c = cache
                                        if (c != null && c.has("vid")) {
                                            haveState = true
                                            val parsed = parse(c)
                                            main.post { if (my == session) applyState(parsed) }
                                        } else if (c == null && haveState) {
                                            // The state vanished after we had seen it: the host closed the room.
                                            main.post { if (my == session) endedByRemote() }
                                            return
                                        }
                                    }
                                } else if (event == "cancel" || event == "auth_revoked") {
                                    return@use
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                failures++
            }
            if (my != session) return
            if (failures >= 8) { // ~16 s of no connection in a row: give up
                main.post { if (my == session) endedByRemote() }
                return
            }
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    /** Local copy of rooms/<code>/state, rebuilt from the stream's put / patch events. */
    private var cache: JSONObject? = null

    private fun mergeState(cur: JSONObject?, path: String, value: Any?, patch: Boolean): JSONObject? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            if (value == null) return null
            if (value !is JSONObject) return cur
            if (!patch || cur == null) return JSONObject(value.toString())
            val k = value.keys()
            while (k.hasNext()) { val key = k.next(); if (value.isNull(key)) cur.remove(key) else cur.put(key, value.get(key)) }
            return cur
        }
        val root = cur ?: JSONObject()
        val key = parts[0] // the state is flat: only the first level matters
        if (value == null) root.remove(key) else root.put(key, value)
        return root
    }

    private fun parse(o: JSONObject) = State(
        vid = o.optString("vid"), title = o.optString("title"), artist = o.optString("artist"),
        art = o.optString("art"), dur = o.optLong("dur"), playing = o.optBoolean("playing"),
        pos = o.optLong("pos"), ref = o.optLong("ref"), q = o.optString("q"), src = o.optString("src")
    )

    private fun isLive(st: State) = st.src == "radio"

    /** Same song already loaded in our player? (YouTube id, or the audio link of an Online / radio song.) */
    private fun sameSong(cur: Track?, tr: Track) = cur != null && cur.source == tr.source && cur.remoteId == tr.remoteId

    private fun expectedPos(st: State): Long =
        if (st.playing) st.pos + (serverNow() - st.ref).coerceAtLeast(0) else st.pos

    /** The song a guest plays for [st]: the host's own YouTube video / Online link / radio stream, or the YouTube match for a local / Drive song. */
    private fun guestTrack(st: State): Track? {
        if (st.vid.startsWith("u:")) { // Online-tab song or radio station of the host: the same link
            val src = if (isLive(st)) Track.SOURCE_RADIO else Track.SOURCE_ONLINE
            return Track(0, st.title, st.artist, "", if (isLive(st)) 0L else st.dur, src, st.vid.substring(2), st.art)
        }
        if (!st.vid.startsWith("q:")) return Track(0, st.title, st.artist, "", st.dur, Track.SOURCE_YOUTUBE, st.vid, st.art)
        return resolved[st.vid]?.takeIf { it.remoteId.isNotBlank() }
    }

    /** Looks the host's local / Drive song up on YouTube (once per song) and then re-applies the host state. */
    private fun resolveAlt(st: State, my: Int) {
        val ctx = app ?: return
        if (st.q.isBlank() || !resolving.add(st.vid)) return
        io.execute {
            val best = try {
                val list = YouTubeApi.search(ctx, st.q, category = null, maxResults = 6)
                // same recording = about the same length
                if (st.dur > 0) list.minByOrNull { abs(it.durationMs - st.dur) } else list.firstOrNull()
            } catch (e: Exception) { null }
            main.post {
                if (my != session) return@post
                resolving.remove(st.vid)
                resolved[st.vid] = best?.copy(title = st.title.ifBlank { best.title }) ?: Track(0, "", "", "", 0L, Track.SOURCE_YOUTUBE, "")
                lastState?.takeIf { it.vid == st.vid }?.let { applyState(it) }
            }
        }
    }

    /** A new host state arrived: follow it (song, play / pause, position). */
    private fun applyState(st: State) {
        if (role != Role.GUEST) return
        lastState = st
        val svc = MusicService.instance ?: run { ensureService(); return } // guestLoop re-applies once it is up
        if (st.vid.isBlank()) { // host plays nothing syncable (e.g. nothing yet): just stop
            if (svc.isPlaying) svc.pause()
            return
        }
        val tr = guestTrack(st)
        if (tr == null) { // local / Drive song of the host: find it on YouTube first
            if (!resolved.containsKey(st.vid)) resolveAlt(st, session)
            return
        }
        val cur = svc.current
        if (!sameSong(cur, tr)) {
            needsInitial = !isLive(st) // position / pause are fixed once the song has actually started (live radio has none)
            svc.setQueueAndPlay(listOf(tr), 0)
            return
        }
        if (st.playing && !svc.isPlaying) svc.play()
        if (!st.playing && svc.isPlaying) svc.pause()
        if (isLive(st)) return // a live stream cannot be sought: playing / paused is all there is to follow
        val expected = expectedPos(st)
        if (svc.position > 0 && abs(svc.position - expected) > 1500) seek(svc, expected)
    }

    private fun seek(svc: MusicService, ms: Long) {
        lastSeekAt = System.currentTimeMillis()
        svc.seekTo(ms.coerceAtLeast(0).toInt())
    }

    /** Every 1.5 s: finishes a song switch and corrects slow drift (only while the guest is playing). */
    private val guestLoop = object : Runnable {
        override fun run() {
            if (role != Role.GUEST) return
            reconcile()
            main.postDelayed(this, 1500)
        }
    }

    /** The player service only exists once something started it: a guest who never played a song has none. */
    private fun ensureService() {
        val c = app ?: return
        try {
            androidx.core.content.ContextCompat.startForegroundService(c, android.content.Intent(c, MusicService::class.java))
        } catch (e: Exception) {
            android.util.Log.w("ListenTogether", "cannot start the player service", e)
        }
    }

    private fun reconcile() {
        val st = lastState ?: return
        val svc = MusicService.instance ?: run { ensureService(); return }
        val tr0 = guestTrack(st)
        val cur0 = svc.current
        // The state arrived before the player was ready (or the player was cleared): apply it again.
        if (st.vid.isNotBlank() && tr0 != null && !sameSong(cur0, tr0)) {
            applyState(st); return
        }
        val cur = svc.current ?: return
        val tr = guestTrack(st) ?: return
        if (st.vid.isBlank() || !sameSong(cur, tr)) return
        if (isLive(st)) return // nothing to seek in a live stream
        val expected = expectedPos(st)
        if (needsInitial) {
            if (!svc.isPlaying || svc.position <= 0) return // not started yet
            needsInitial = false
            if (!st.playing) svc.pause()
            seek(svc, expected)
            return
        }
        if (st.playing && svc.isPlaying && svc.position > 0 &&
            abs(svc.position - expected) > DRIFT_MS && System.currentTimeMillis() - lastSeekAt > 5000
        ) seek(svc, expected)
    }

    // ------------------------------------------------------------------ shared queue

    /** A guest suggests a YouTube song: it lands in rooms/<code>/requests and the host queues it next. */
    fun request(t: Track, done: (Boolean) -> Unit) {
        if (!active || t.source != Track.SOURCE_YOUTUBE || t.remoteId.isBlank()) { done(false); return }
        val room = code
        val my = session
        val json = JSONObject()
            .put("vid", t.remoteId).put("title", t.title).put("artist", t.artist).put("art", t.artUrl)
            .put("dur", t.durationMs).put("by", uid).put("t", System.currentTimeMillis())
            .toString()
        val id = randomId(8)
        io.execute {
            val ok = put("rooms/$room/requests/$id", json) != null
            main.post { if (my == session) done(ok) }
        }
    }

    /** Every 3 s: the host takes in the guests' suggestions and publishes the queue; guests read the queue. */
    private fun startQueueSync(my: Int) {
        io.execute {
            while (my == session) {
                try { Thread.sleep(3_000L) } catch (_: InterruptedException) { return@execute }
                if (my != session) return@execute
                val room = code
                if (room.isEmpty()) return@execute
                if (role == Role.HOST) hostQueueTick(my, room) else guestQueueTick(my, room)
            }
        }
    }

    private class Req(val id: String, val t: Long, val track: Track)

    private fun hostQueueTick(my: Int, room: String) {
        val body = get("rooms/$room/requests")
        val o = if (body.isNullOrEmpty() || body == "null") null else try { JSONObject(body) } catch (e: Exception) { null }
        if (o != null) {
            val reqs = ArrayList<Req>()
            for (k in o.keys()) {
                val r = o.optJSONObject(k)
                val vid = r?.optString("vid").orEmpty()
                if (r == null || vid.isBlank()) { delete("rooms/$room/requests/$k"); continue }
                reqs.add(Req(k, r.optLong("t"), Track(
                    0, r.optString("title"), r.optString("artist"), "", r.optLong("dur"),
                    Track.SOURCE_YOUTUBE, vid, r.optString("art")
                )))
            }
            reqs.sortBy { it.t }
            val take = reqs.take(10)
            for (r in take) delete("rooms/$room/requests/${r.id}") // handed over: remove it so it is not queued twice
            if (take.isNotEmpty()) main.post {
                if (my == session && role == Role.HOST) take.forEach { enqueueRequest(it.track) }
            }
        }
        main.post { if (my == session && role == Role.HOST) publishUpNext() }
    }

    /** Puts a guest's song right after the current one (after earlier suggestions), unless it is already waiting. */
    private fun enqueueRequest(t: Track) {
        val svc = MusicService.instance ?: return
        if (svc.current == null) { svc.playNext(t); return }
        val waiting = svc.queueTracks.drop(svc.queueIndex + 1)
        if (waiting.any { it.key == t.key }) return
        svc.playNext(t)
    }

    /** Host: drops the [pos]-th song of [upNext] from the queue ([title] guards against the list having moved on). */
    fun removeUpNext(pos: Int, title: String) {
        if (role != Role.HOST) return
        val svc = MusicService.instance ?: return
        var n = -1
        for (i in svc.queueIndex + 1 until svc.queueTracks.size) {
            val t = svc.queueTracks[i]
            if (t.source == Track.SOURCE_RADIO) continue
            n++
            if (n == pos) {
                if (t.title == title) svc.removeFromQueue(i)
                break
            }
        }
        publishUpNext()
    }

    /** Writes the songs after the current one so the guests can see them (only when it changed). */
    private fun publishUpNext() {
        val svc = MusicService.instance
        val list = if (svc == null || svc.queueIndex < 0) emptyList()
        else svc.queueTracks.drop(svc.queueIndex + 1).filter { it.source != Track.SOURCE_RADIO }.take(15)
        val arr = org.json.JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("artist", it.artist)) }
        val json = arr.toString()
        if (json == lastUpNextJson) return
        lastUpNextJson = json
        upNext = list.map { it.title to it.artist }
        onChange?.invoke()
        val room = code
        io.execute { if (list.isEmpty()) delete("rooms/$room/upnext") else put("rooms/$room/upnext", json) }
    }

    private fun guestQueueTick(my: Int, room: String) {
        val body = get("rooms/$room/upnext") ?: return
        val list = ArrayList<Pair<String, String>>()
        if (body.isNotEmpty() && body != "null") {
            try {
                val a = org.json.JSONArray(body)
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    list.add(e.optString("title") to e.optString("artist"))
                }
            } catch (e: Exception) { return }
        }
        main.post {
            if (my == session && role == Role.GUEST && list != upNext) {
                upNext = list
                onChange?.invoke()
            }
        }
    }

    // ------------------------------------------------------------------ presence

    /** Every 20 s (first run after 1.5 s): refresh our heartbeat and list who beat within the last minute. */
    private fun startPresence(my: Int) {
        io.execute {
            var first = true
            app?.let { a -> val n = myName(a); if (n.isNotEmpty()) publishName(code, uid, n) }
            while (my == session) {
                try {
                    Thread.sleep(if (first) 1_500L else 20_000L)
                } catch (_: InterruptedException) { return@execute }
                first = false
                if (my != session) return@execute
                val room = code
                put("rooms/$room/members/$uid/t", "{\".sv\":\"timestamp\"}")
                val body = get("rooms/$room/members") ?: continue
                val o = try { JSONObject(body) } catch (e: Exception) { null } ?: continue
                val limit = serverNow() - 60_000
                val ids = ArrayList<String>()
                val names = HashMap<String, String>()
                for (k in o.keys()) {
                    val m = o.optJSONObject(k) ?: continue
                    if (m.optLong("t") > limit) {
                        ids.add(k)
                        m.optString("n").filter { !it.isISOControl() }.trim().take(MAX_NAME)
                            .takeIf { it.isNotEmpty() }?.let { names[k] = it }
                    }
                }
                ids.sort()
                main.post {
                    if (my == session && ids.isNotEmpty()) {
                        members = ids.size
                        memberIds = ids
                        memberNames = names
                        onChange?.invoke()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ misc

    fun shareLink(): String = if (code.isBlank()) "" else "starrysound://room?id=$code"

    private fun randomId(n: Int, alphabet: String = "abcdefghijklmnopqrstuvwxyz0123456789"): String {
        val r = java.security.SecureRandom()
        return String(CharArray(n) { alphabet[r.nextInt(alphabet.length)] })
    }
}
