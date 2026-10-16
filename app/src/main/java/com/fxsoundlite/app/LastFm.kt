package com.fxsoundlite.app

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executors

/**
 * Last.fm: scrobbling plus a taste profile for the AI features.
 *
 * - The user brings their own free API key + shared secret (last.fm/api/account/create), like the
 *   other online features here, so no secret is baked into the APK. They stay on this device and
 *   are never uploaded to Drive.
 * - Login uses Last.fm's web authorisation (the password is typed on last.fm, never here): we ask
 *   for a token, the user approves it in the browser, then we exchange it for a session key.
 * - Scrobbling follows Last.fm's rules: a song counts after min(half its length, 4 minutes) of
 *   listening, and only if it is longer than 30 seconds. "Now playing" is sent after 5 seconds.
 *   Scrobbles that cannot be sent (offline) wait in a small persistent queue and go out later.
 * - Taste: top artists and loved tracks are cached for [TASTE_TTL_MS] and read by
 *   [AiRecommender] / [PlaylistGenerator] (the cache read never touches the network).
 */
object LastFm {
    private const val API = "https://ws.audioscrobbler.com/2.0/"
    private const val AUTH_PAGE = "https://www.last.fm/api/auth/"

    private const val PREFS = "lastfm_prefs"
    private const val K_API_KEY = "api_key"
    private const val K_SECRET = "api_secret"
    private const val K_SESSION = "session_key"
    private const val K_USER = "user"
    private const val K_TOKEN = "pending_token"
    private const val K_SCROBBLE = "scrobble_on"
    private const val K_TASTE = "taste_on"
    private const val K_QUEUE = "queue"
    private const val K_TASTE_AT = "taste_at"
    private const val K_TASTE_ARTISTS = "taste_artists"
    private const val K_TASTE_LOVED = "taste_loved"

    private const val QUEUE_MAX = 500
    private const val BATCH = 50
    private const val MAX_AGE_SEC = 14 * 24 * 3600L          // Last.fm refuses older scrobbles
    private const val MIN_TRACK_MS = 30_000L
    private const val MAX_THRESHOLD_MS = 240_000L
    private const val TASTE_TTL_MS = 12 * 60 * 60_000L
    private const val TASTE_ARTISTS_N = 20
    private const val TASTE_LOVED_N = 20

    class LastFmException(val code: Int, message: String, val network: Boolean = false) : Exception(message)

    class Taste(val artists: List<String>, val loved: List<Pair<String, String>>) {
        val isEmpty get() = artists.isEmpty() && loved.isEmpty()
    }

    private class Meta(val artist: String, val title: String, val album: String, val durationSec: Long)

    /** One thread: keeps the queue ordered and keeps the network off the player's thread. */
    private val io = Executors.newSingleThreadExecutor()

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ settings

    fun apiKey(c: Context): String = p(c).getString(K_API_KEY, "").orEmpty().trim()
    fun setApiKey(c: Context, v: String) = p(c).edit().putString(K_API_KEY, v.trim()).apply()
    fun apiSecret(c: Context): String = p(c).getString(K_SECRET, "").orEmpty().trim()
    fun setApiSecret(c: Context, v: String) = p(c).edit().putString(K_SECRET, v.trim()).apply()

    fun username(c: Context): String = p(c).getString(K_USER, "").orEmpty()
    fun hasKeys(c: Context): Boolean = apiKey(c).isNotBlank() && apiSecret(c).isNotBlank()
    fun isConnected(c: Context): Boolean = p(c).getString(K_SESSION, "").orEmpty().isNotBlank() && hasKeys(c)
    fun hasPendingAuth(c: Context): Boolean = p(c).getString(K_TOKEN, "").orEmpty().isNotBlank()

    fun scrobbleOn(c: Context): Boolean = p(c).getBoolean(K_SCROBBLE, true)
    fun setScrobbleOn(c: Context, v: Boolean) = p(c).edit().putBoolean(K_SCROBBLE, v).apply()
    fun tasteOn(c: Context): Boolean = p(c).getBoolean(K_TASTE, true)
    fun setTasteOn(c: Context, v: Boolean) = p(c).edit().putBoolean(K_TASTE, v).apply()

    fun scrobblingEnabled(c: Context): Boolean = isConnected(c) && scrobbleOn(c)
    fun tasteEnabled(c: Context): Boolean = isConnected(c) && tasteOn(c)

    fun queueSize(c: Context): Int = readQueue(c).length()

    // ------------------------------------------------------------------ HTTP + signing

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it) }

    /** api_sig: parameter names sorted, "name+value" glued together, the secret appended, MD5. */
    internal fun sign(params: Map<String, String>, secret: String): String {
        val sb = StringBuilder()
        TreeMap(params).forEach { (k, v) -> if (k != "format" && k != "callback") sb.append(k).append(v) }
        return md5(sb.append(secret).toString())
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun form(params: Map<String, String>) = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

    /** Signed POST (auth + write methods). */
    private fun postSigned(c: Context, method: String, extra: Map<String, String>, withSession: Boolean): JSONObject {
        val params = LinkedHashMap<String, String>()
        params["method"] = method
        params["api_key"] = apiKey(c)
        if (withSession) params["sk"] = p(c).getString(K_SESSION, "").orEmpty()
        params.putAll(extra)
        params["api_sig"] = sign(params, apiSecret(c))
        params["format"] = "json"
        return parse(try {
            Http.request("POST", API, body = form(params), contentType = "application/x-www-form-urlencoded", readTimeoutMs = 20_000)
        } catch (e: java.io.IOException) {
            throw LastFmException(-1, e.message ?: "network", network = true)
        })
    }

    /** Unsigned GET (read-only public data such as a user's top artists). */
    private fun getPublic(c: Context, method: String, extra: Map<String, String>): JSONObject {
        val params = LinkedHashMap<String, String>()
        params["method"] = method
        params["api_key"] = apiKey(c)
        params.putAll(extra)
        params["format"] = "json"
        return parse(try {
            Http.request("GET", API + "?" + form(params), readTimeoutMs = 20_000)
        } catch (e: java.io.IOException) {
            throw LastFmException(-1, e.message ?: "network", network = true)
        })
    }

    private fun parse(r: Http.Response): JSONObject {
        val o = try { JSONObject(r.body) } catch (e: Exception) {
            throw LastFmException(r.code, "HTTP ${r.code}", network = r.code >= 500)
        }
        if (o.has("error")) throw LastFmException(o.optInt("error"), o.optString("message"))
        if (r.code !in 200..299) throw LastFmException(r.code, "HTTP ${r.code}", network = r.code >= 500)
        return o
    }

    @StringRes
    fun errorRes(e: LastFmException): Int = when {
        e.network -> R.string.lastfm_err_network
        e.code == 10 -> R.string.lastfm_err_key
        e.code == 13 -> R.string.lastfm_err_signature
        e.code == 14 || e.code == 15 -> R.string.lastfm_err_not_authorized
        else -> R.string.lastfm_err_generic
    }

    // ------------------------------------------------------------------ login

    /** Step 1 (blocking): gets a token and returns the last.fm page where the user approves it. */
    fun startAuth(c: Context): String {
        val token = postSigned(c, "auth.getToken", emptyMap(), withSession = false).optString("token")
        if (token.isBlank()) throw LastFmException(0, "no token")
        p(c).edit().putString(K_TOKEN, token).apply()
        return AUTH_PAGE + "?api_key=" + enc(apiKey(c)) + "&token=" + enc(token)
    }

    /** Step 2 (blocking), after the user approved the token in the browser: turns it into a session. */
    fun finishAuth(c: Context) {
        val token = p(c).getString(K_TOKEN, "").orEmpty()
        if (token.isBlank()) throw LastFmException(15, "no pending token")
        val s = postSigned(c, "auth.getSession", mapOf("token" to token), withSession = false).optJSONObject("session")
        val key = s?.optString("key").orEmpty()
        if (key.isBlank()) throw LastFmException(0, "no session")
        p(c).edit().putString(K_SESSION, key).putString(K_USER, s?.optString("name").orEmpty())
            .remove(K_TOKEN).apply()
        io.execute {
            refreshTaste(c, force = true)
            flush(c)
        }
    }

    fun cancelAuth(c: Context) = p(c).edit().remove(K_TOKEN).apply()

    /** Forgets the account (keeps the API key and secret). Songs still waiting to be sent are dropped. */
    fun disconnect(c: Context) {
        p(c).edit().remove(K_SESSION).remove(K_USER).remove(K_TOKEN).remove(K_QUEUE)
            .remove(K_TASTE_AT).remove(K_TASTE_ARTISTS).remove(K_TASTE_LOVED).apply()
        AiRecommender.clearCache(c)
    }

    // ------------------------------------------------------------------ what gets scrobbled

    private val NOISE = Regex(
        "\\s*[\\(\\[][^)\\]]*(official|video|audio|lyric|visuali[sz]er|\\bmv\\b|m/v|\\bhd\\b|4k|clip)[^)\\]]*[\\)\\]]",
        RegexOption.IGNORE_CASE
    )

    private fun cleanArtist(s: String): String =
        s.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "")
            .trim()

    /** Radio is never scrobbled. YouTube titles are tidied: "Artist - Title (Official Video)" -> Artist / Title. */
    private fun meta(t: Track): Meta? {
        if (t.source == Track.SOURCE_RADIO) return null
        var artist = cleanArtist(t.artist)
        var title = t.title.trim()
        if (t.source == Track.SOURCE_YOUTUBE) {
            title = title.replace(NOISE, "").trim()
            val topicChannel = t.artist.trim().endsWith("- Topic", ignoreCase = true)
            val dash = title.indexOf(" - ")
            if (!topicChannel && dash > 0 && dash < title.length - 3) {
                artist = title.substring(0, dash).trim()
                title = title.substring(dash + 3).trim()
            }
        }
        if (artist.isBlank() || title.isBlank() || artist.equals("<unknown>", ignoreCase = true)) return null
        return Meta(artist.take(200), title.take(200), t.album.trim().take(200), t.durationMs / 1000)
    }

    /** Listening time after which [t] is scrobbled; Long.MAX_VALUE = never (radio, under 30 s, unusable tags). */
    fun thresholdMs(t: Track): Long {
        if (meta(t) == null) return Long.MAX_VALUE
        if (t.durationMs in 1 until MIN_TRACK_MS) return Long.MAX_VALUE
        if (t.durationMs <= 0) return MAX_THRESHOLD_MS
        return minOf(t.durationMs / 2, MAX_THRESHOLD_MS)
    }

    // ------------------------------------------------------------------ now playing + scrobble queue

    fun nowPlaying(c: Context, t: Track) {
        val app = c.applicationContext
        val m = meta(t) ?: return
        io.execute {
            if (!scrobblingEnabled(app)) return@execute
            val extra = LinkedHashMap<String, String>()
            extra["artist"] = m.artist
            extra["track"] = m.title
            if (m.album.isNotBlank()) extra["album"] = m.album
            if (m.durationSec > 0) extra["duration"] = m.durationSec.toString()
            try { postSigned(app, "track.updateNowPlaying", extra, withSession = true) } catch (e: Exception) { /* cosmetic */ }
        }
    }

    /** Queues a finished listen ([startedAtSec] = epoch seconds when it began) and tries to send the queue. */
    fun scrobble(c: Context, t: Track, startedAtSec: Long) {
        val app = c.applicationContext
        val m = meta(t) ?: return
        io.execute {
            if (!scrobblingEnabled(app)) return@execute
            val q = readQueue(app)
            q.put(
                JSONObject().put("a", m.artist).put("t", m.title).put("al", m.album)
                    .put("d", m.durationSec).put("ts", startedAtSec)
            )
            writeQueue(app, q)
            flush(app)
        }
    }

    /** Sends whatever is waiting (e.g. at app start, after being offline). Safe to call any time. */
    fun flushAsync(c: Context) {
        val app = c.applicationContext
        io.execute { flush(app) }
    }

    private fun readQueue(c: Context): JSONArray =
        try { JSONArray(p(c).getString(K_QUEUE, null) ?: "[]") } catch (e: Exception) { JSONArray() }

    private fun writeQueue(c: Context, q: JSONArray) {
        var arr = q
        if (arr.length() > QUEUE_MAX) {
            arr = JSONArray()
            for (i in q.length() - QUEUE_MAX until q.length()) arr.put(q.get(i))
        }
        p(c).edit().putString(K_QUEUE, arr.toString()).apply()
    }

    /** Must run on [io]. Sends the queue in batches of up to [BATCH]. */
    private fun flush(c: Context) {
        if (!scrobblingEnabled(c)) return
        repeat(20) {
            val now = System.currentTimeMillis() / 1000
            // Drop what Last.fm would refuse anyway, so one stale entry never blocks the rest.
            val all = readQueue(c)
            val fresh = JSONArray()
            for (i in 0 until all.length()) {
                val o = all.optJSONObject(i) ?: continue
                if (now - o.optLong("ts") <= MAX_AGE_SEC) fresh.put(o)
            }
            if (fresh.length() == 0) { writeQueue(c, fresh); return }

            val n = minOf(BATCH, fresh.length())
            val extra = LinkedHashMap<String, String>()
            for (i in 0 until n) {
                val o = fresh.getJSONObject(i)
                extra["artist[$i]"] = o.optString("a")
                extra["track[$i]"] = o.optString("t")
                extra["timestamp[$i]"] = o.optLong("ts").toString()
                o.optString("al").takeIf { it.isNotBlank() }?.let { extra["album[$i]"] = it }
                o.optLong("d").takeIf { it > 0 }?.let { extra["duration[$i]"] = it.toString() }
            }
            var done = false
            try {
                postSigned(c, "track.scrobble", extra, withSession = true)
                done = true // accepted or ignored per song by Last.fm: either way it is handled
            } catch (e: LastFmException) {
                when {
                    e.network || e.code == 11 || e.code == 16 || e.code == 29 -> { writeQueue(c, fresh); return } // try later
                    e.code == 9 || e.code == 4 -> { // session no longer valid: reconnect needed, keep the songs
                        p(c).edit().remove(K_SESSION).apply()
                        writeQueue(c, fresh)
                        return
                    }
                    e.code == 10 || e.code == 13 || e.code == 26 -> { writeQueue(c, fresh); return } // bad key / secret
                    else -> done = true // this batch is unusable: drop it instead of blocking the queue
                }
            }
            if (done) {
                val rest = JSONArray()
                for (i in n until fresh.length()) rest.put(fresh.get(i))
                writeQueue(c, rest)
                if (rest.length() == 0) return
            }
        }
    }

    // ------------------------------------------------------------------ taste profile

    /** Last cached taste (no network). Empty when Last.fm taste is off or nothing was fetched yet. */
    fun cachedTaste(c: Context): Taste {
        if (!tasteEnabled(c)) return Taste(emptyList(), emptyList())
        val prefs = p(c)
        val artists = try {
            val a = JSONArray(prefs.getString(K_TASTE_ARTISTS, null) ?: "[]")
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
        val loved = try {
            val a = JSONArray(prefs.getString(K_TASTE_LOVED, null) ?: "[]")
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("t")
                val ar = o.optString("a")
                if (t.isBlank() || ar.isBlank()) null else t to ar
            }
        } catch (e: Exception) { emptyList() }
        return Taste(artists, loved)
    }

    /** Blocking. Refreshes the cached taste when it is older than [TASTE_TTL_MS]; errors keep the old cache. */
    fun refreshTaste(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        if (!tasteEnabled(app)) return
        val prefs = p(app)
        if (!force && System.currentTimeMillis() - prefs.getLong(K_TASTE_AT, 0L) < TASTE_TTL_MS) return
        val user = username(app)
        if (user.isBlank()) return
        try {
            var artists = topArtists(app, user, "6month")
            if (artists.isEmpty()) artists = topArtists(app, user, "overall")
            val loved = lovedTracks(app, user)
            val lovedArr = JSONArray().also { a -> loved.forEach { a.put(JSONObject().put("t", it.first).put("a", it.second)) } }
            prefs.edit()
                .putString(K_TASTE_ARTISTS, JSONArray(artists).toString())
                .putString(K_TASTE_LOVED, lovedArr.toString())
                .putLong(K_TASTE_AT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            // offline or Last.fm hiccup: whatever is cached stays in use
        }
    }

    private fun topArtists(c: Context, user: String, period: String): List<String> {
        val root = getPublic(c, "user.getTopArtists", mapOf("user" to user, "period" to period, "limit" to TASTE_ARTISTS_N.toString()))
        return items(root.optJSONObject("topartists"), "artist").map { it.optString("name") }.filter { it.isNotBlank() }
    }

    private fun lovedTracks(c: Context, user: String): List<Pair<String, String>> {
        val root = getPublic(c, "user.getLovedTracks", mapOf("user" to user, "limit" to TASTE_LOVED_N.toString()))
        return items(root.optJSONObject("lovedtracks"), "track").mapNotNull {
            val title = it.optString("name")
            val artist = it.optJSONObject("artist")?.optString("name").orEmpty()
            if (title.isBlank() || artist.isBlank()) null else title to artist
        }
    }

    /** Last.fm's JSON gives an array, or a bare object when there is only one item. */
    private fun items(parent: JSONObject?, key: String): List<JSONObject> {
        if (parent == null) return emptyList()
        parent.optJSONArray(key)?.let { a -> return (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        return parent.optJSONObject(key)?.let { listOf(it) } ?: emptyList()
    }
}
