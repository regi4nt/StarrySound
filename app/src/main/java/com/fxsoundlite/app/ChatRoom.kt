package com.fxsoundlite.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Chat between people, built the same way as [ListenTogether]: a Firebase Realtime Database used over its plain
 * REST API (same server URL, no SDK), a 6-character room code, anonymous ids, and a display name ([ListenTogether.myName]).
 *
 *   rooms/<CODE>/host        uid of the creator (only for rooms made here; deleting the room ends it for everyone)
 *   rooms/<CODE>/chat/<id>   one message: u = uid, n = display name, m = text, t = server timestamp
 *
 * A chat room is a normal room, so when you are in a Listen together room the chat simply opens THAT room's chat
 * ([attachToListen]) and everyone who listens together can talk. Without one, you can make or join a chat-only room.
 * New messages are fetched by polling (every 2 s while the chat screen is open, every 10 s otherwise), like the
 * shared queue of Listen together.
 *
 * All service calls and UI callbacks run on the main thread; network work runs on [io].
 */
object ChatRoom {
    class Msg(val id: String, val uid: String, val name: String, val text: String, val t: Long)

    enum class Fail { NOT_CONFIGURED, NETWORK, NOT_FOUND, BUSY }

    const val MAX_LEN = 500
    private const val KEEP = 80
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    @Volatile var code = ""
        private set
    @Volatile var myId = ""
        private set
    /** True when we made this room here (we delete it when we leave). */
    @Volatile var isHost = false
        private set
    /** True when this is the chat of the Listen together room we are in (it ends together with that room). */
    @Volatile var linked = false
        private set
    @Volatile var messages: List<Msg> = emptyList()
        private set
    /** True while the chat screen is on show: polling is faster then. */
    @Volatile var watching = false

    /** Called on the main thread whenever messages / membership change. */
    var onChange: (() -> Unit)? = null

    val active: Boolean get() = code.isNotEmpty()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private var base = ""
    private var app: Context? = null
    @Volatile private var session = 0

    // ------------------------------------------------------------------ REST helpers (io thread)

    private fun url(path: String, query: String = "") = "$base/$path.json" + if (query.isEmpty()) "" else "?$query"

    private fun get(path: String, query: String = ""): String? = try {
        Http.request("GET", url(path, query)).takeIf { it.code in 200..299 }?.body?.trim()
    } catch (e: Exception) { null }

    private fun put(path: String, json: String): Boolean = try {
        Http.request("PUT", url(path), body = json, contentType = "application/json").code in 200..299
    } catch (e: Exception) { false }

    private fun delete(path: String) {
        try { Http.request("DELETE", url(path)) } catch (e: Exception) { android.util.Log.d("ChatRoom", "ignored: $e") }
    }

    private fun randomId(n: Int, alphabet: String = "abcdefghijklmnopqrstuvwxyz0123456789"): String {
        val r = java.security.SecureRandom()
        return String(CharArray(n) { alphabet[r.nextInt(alphabet.length)] })
    }

    // ------------------------------------------------------------------ start / leave

    private fun begin(room: String, me: String, host: Boolean, isLinked: Boolean, my: Int) {
        code = room
        myId = me
        isHost = host
        linked = isLinked
        messages = emptyList()
        startPoll(my)
        onChange?.invoke()
    }

    /** Opens the chat of the Listen together room we are in (no-op when not in one, or already in a chat). */
    fun attachToListen(c: Context) {
        if (active || !ListenTogether.active || !ListenTogether.isConfigured(c)) return
        base = ListenTogether.dbUrl(c)
        app = c.applicationContext
        begin(ListenTogether.code, ListenTogether.myId, host = false, isLinked = true, my = ++session)
    }

    /** Makes a new chat-only room. [done]: null = ok + the room code. */
    fun create(c: Context, done: (Fail?, String) -> Unit) {
        if (active) { done(Fail.BUSY, ""); return }
        if (!ListenTogether.isConfigured(c)) { done(Fail.NOT_CONFIGURED, ""); return }
        base = ListenTogether.dbUrl(c)
        app = c.applicationContext
        val my = ++session
        val me = randomId(10)
        io.execute {
            var room = ""
            for (i in 0 until 6) {
                val candidate = randomId(6, ALPHABET)
                val existing = get("rooms/$candidate/host") ?: run { fail(my, Fail.NETWORK, done); return@execute }
                if (existing == "null" || existing.isEmpty()) { room = candidate; break }
            }
            if (room.isEmpty() || !put("rooms/$room/host", JSONObject.quote(me))) { fail(my, Fail.NETWORK, done); return@execute }
            main.post {
                if (my != session) return@post
                begin(room, me, host = true, isLinked = false, my = my)
                done(null, room)
            }
        }
    }

    /** Joins the room with this code (a Listen together room works too). */
    fun join(c: Context, rawCode: String, done: (Fail?, String) -> Unit) {
        if (active) { done(Fail.BUSY, ""); return }
        if (!ListenTogether.isConfigured(c)) { done(Fail.NOT_CONFIGURED, ""); return }
        val room = rawCode.trim().uppercase().filter { it in ALPHABET }
        if (room.length != 6) { done(Fail.NOT_FOUND, ""); return }
        base = ListenTogether.dbUrl(c)
        app = c.applicationContext
        val my = ++session
        val me = randomId(10)
        io.execute {
            val host = get("rooms/$room/host") ?: run { fail(my, Fail.NETWORK, done); return@execute }
            if (host == "null" || host.isEmpty()) { fail(my, Fail.NOT_FOUND, done); return@execute }
            main.post {
                if (my != session) return@post
                begin(room, me, host = false, isLinked = false, my = my)
                done(null, room)
            }
        }
    }

    private fun fail(my: Int, why: Fail, done: (Fail?, String) -> Unit) {
        main.post { if (my == session) done(why, "") }
    }

    /** Leaves the chat. The creator of a chat-only room removes it for everyone. */
    fun leave() {
        if (!active) return
        val removeRoom = isHost && !linked
        val room = code
        session++ // the polling of the old session stops
        code = ""
        myId = ""
        isHost = false
        linked = false
        messages = emptyList()
        if (removeRoom) io.execute { delete("rooms/$room") }
        onChange?.invoke()
    }

    private fun endedByRemote() {
        if (!active) return
        leave()
        app?.let { android.widget.Toast.makeText(it, R.string.listen_ended, android.widget.Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ messages

    /** Sends [raw] to the room; [done] gets whether it went through. */
    fun send(c: Context, raw: String, done: (Boolean) -> Unit) {
        val text = raw.filter { !it.isISOControl() || it == '\n' }.trim().take(MAX_LEN)
        if (!active || text.isEmpty()) { done(false); return }
        val room = code
        val my = session
        val json = JSONObject()
            .put("u", myId).put("n", ListenTogether.myName(c)).put("m", text)
            .put("t", JSONObject().put(".sv", "timestamp"))
            .toString()
        io.execute {
            val ok = try {
                Http.request("POST", url("rooms/$room/chat"), body = json, contentType = "application/json").code in 200..299
            } catch (e: Exception) { false }
            if (ok) fetchOnce(my, room)
            main.post { if (my == session) done(ok) }
        }
    }

    /** Fetches the latest messages now (io thread) and publishes them when something changed. */
    private fun fetchOnce(my: Int, room: String) {
        val body = get("rooms/$room/chat", "orderBy=%22%24key%22&limitToLast=$KEEP") ?: return
        val list = ArrayList<Msg>()
        if (body.isNotEmpty() && body != "null") {
            val o = try { JSONObject(body) } catch (e: Exception) { return }
            for (k in o.keys()) {
                val m = o.optJSONObject(k) ?: continue
                val text = m.optString("m")
                if (text.isBlank()) continue
                list.add(Msg(k, m.optString("u"), m.optString("n").filter { !it.isISOControl() }.trim().take(ListenTogether.MAX_NAME), text, m.optLong("t")))
            }
            list.sortWith(compareBy<Msg> { it.t }.thenBy { it.id })
        }
        main.post {
            if (my != session) return@post
            val cur = messages
            if (cur.size == list.size && cur.lastOrNull()?.id == list.lastOrNull()?.id) return@post
            messages = list
            onChange?.invoke()
        }
    }

    /** Asks for the latest messages right away (e.g. when the chat screen opens). */
    fun refresh() {
        if (!active) return
        val my = session
        val room = code
        io.execute { fetchOnce(my, room) }
    }

    private fun startPoll(my: Int) {
        io.execute {
            var round = 0
            while (my == session) {
                val room = code
                if (room.isEmpty()) return@execute
                if (linked && !ListenTogether.active) { // the Listen together room we rode on is gone
                    main.post { if (my == session) leave() }
                    return@execute
                }
                fetchOnce(my, room)
                // A room somebody else made: when the creator closes it the host entry disappears.
                if (!isHost && !linked && round % 5 == 4) {
                    val host = get("rooms/$room/host")
                    if (host != null && (host == "null" || host.isEmpty())) {
                        main.post { if (my == session) endedByRemote() }
                        return@execute
                    }
                }
                round++
                try { Thread.sleep(if (watching) 2_000L else 10_000L) } catch (_: InterruptedException) { return@execute }
            }
        }
    }
}
