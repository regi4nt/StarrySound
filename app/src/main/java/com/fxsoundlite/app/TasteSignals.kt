package com.fxsoundlite.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extra taste signals, kept apart from [StatsStore] so the Home statistics stay "what you played in
 * StarrySound". Everything stays on the device (SharedPreferences); nothing is synced to Drive.
 *
 *  1. Other apps: what YouTube / YouTube Music / Spotify plays on this phone, noticed by [NowPlayingListenerService]
 *     through the notification-access permission. Counted only from the moment the switch is turned on, and
 *     never while private mode is on.
 *  2. YouTube account: artists of the music videos the account liked, and the music channels it subscribes
 *     to, read with the `youtube.readonly` scope ([YouTubeAccount]). A snapshot refreshed now and then.
 *
 * [addTo] feeds both into the same artist-weight maps that Quick, the Home shelves, AI picks and the
 * generated playlists already build, so each of them only needs one extra line.
 */
object TasteSignals {
    private const val PREFS = "taste_signals"
    private const val K_EXT_ON = "ext_on"
    private const val K_EXT_SINCE = "ext_since"
    private const val K_EXT_ARTISTS = "ext_artists"
    private const val K_EXT_PLAYS = "ext_plays"
    private const val K_ACC_ON = "acc_on"
    private const val K_ACC_AT = "acc_at"
    private const val K_ACC_LIKES = "acc_likes"
    private const val K_ACC_SUBS = "acc_subs"
    private const val K_ACC_EMAIL = "acc_email"
    private const val MAX_ENTRIES = 150

    /** One song heard in another app counts like a play inside StarrySound. */
    private const val WEIGHT_EXT_PLAY = 1
    /** A liked music video: a little more than a play, but capped per artist so one fan-playlist cannot dominate. */
    private const val WEIGHT_LIKE = 2
    private const val LIKE_CAP = 5
    /** A subscribed music channel: a flat bonus (it says "I like this artist", not "how often"). */
    private const val WEIGHT_SUB = 3

    private const val STALE_MS = 24 * 60 * 60_000L

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun readJson(raw: String?): JSONObject = try { JSONObject(raw ?: "{}") } catch (e: Exception) { JSONObject() }

    // ------------------------------------------------------------------ 1. other apps

    fun externalEnabled(c: Context): Boolean = p(c).getBoolean(K_EXT_ON, false)

    fun setExternalEnabled(c: Context, on: Boolean) {
        val e = p(c).edit().putBoolean(K_EXT_ON, on)
        if (on && p(c).getLong(K_EXT_SINCE, 0L) == 0L) e.putLong(K_EXT_SINCE, System.currentTimeMillis())
        e.apply()
    }

    /** When the first play was allowed to be counted (0 while the switch was never turned on). */
    fun externalSince(c: Context): Long = p(c).getLong(K_EXT_SINCE, 0L)

    fun externalPlays(c: Context): Int = p(c).getInt(K_EXT_PLAYS, 0)

    fun externalArtistCount(c: Context): Int = readJson(p(c).getString(K_EXT_ARTISTS, null)).length()

    /** Called by [NowPlayingListenerService] once a song has been heard for [StatsStore.PLAY_THRESHOLD_MS]. */
    @Synchronized
    fun recordExternalPlay(c: Context, artist: String) {
        if (!externalEnabled(c) || MusicPrefs.privateMode(c)) return
        val a = cleanName(artist)
        if (a.isEmpty()) return
        val prefs = p(c)
        val o = readJson(prefs.getString(K_EXT_ARTISTS, null))
        o.put(a, o.optInt(a) + 1)
        prefs.edit()
            .putString(K_EXT_ARTISTS, pruneInts(o).toString())
            .putInt(K_EXT_PLAYS, prefs.getInt(K_EXT_PLAYS, 0) + 1)
            .apply()
    }

    @Synchronized
    fun clearExternal(c: Context) {
        p(c).edit().remove(K_EXT_ARTISTS).remove(K_EXT_PLAYS).putLong(K_EXT_SINCE, if (externalEnabled(c)) System.currentTimeMillis() else 0L).apply()
    }

    // ------------------------------------------------------------------ 2. YouTube account

    fun accountEnabled(c: Context): Boolean = p(c).getBoolean(K_ACC_ON, false)

    fun setAccountEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean(K_ACC_ON, on).apply()

    fun accountSyncedAt(c: Context): Long = p(c).getLong(K_ACC_AT, 0L)

    fun accountLikedArtists(c: Context): Int = readJson(p(c).getString(K_ACC_LIKES, null)).length()

    fun accountSubscriptions(c: Context): Int = try { JSONArray(p(c).getString(K_ACC_SUBS, "[]")).length() } catch (e: Exception) { 0 }

    /** Blocking (network): reads likes + subscriptions and stores them. Throws like [YouTubeAccount] does
     *  (UserRecoverableAuthException when the consent screen is still needed). */
    @Synchronized
    fun syncAccount(c: Context) {
        val app = c.applicationContext
        val likes = YouTubeAccount.likedMusicArtists(app)
        val subs = YouTubeAccount.musicSubscriptions(app)
        val likesJson = JSONObject().also { o -> likes.forEach { (a, n) -> o.put(a, n) } }
        p(app).edit()
            .putString(K_ACC_LIKES, pruneInts(likesJson).toString())
            .putString(K_ACC_SUBS, JSONArray(subs.take(MAX_ENTRIES)).toString())
            .putString(K_ACC_EMAIL, DriveSync.email(app))
            .putLong(K_ACC_AT, System.currentTimeMillis())
            .apply()
    }

    /** True when the switch is on, an account is signed in and the last snapshot is older than a day. */
    fun accountStale(c: Context): Boolean =
        accountEnabled(c) && YouTubeAccount.isSignedIn(c) &&
            System.currentTimeMillis() - accountSyncedAt(c) > STALE_MS

    @Volatile private var autoSyncing = false

    /** Quiet background refresh (called when the app comes to the front); failures are ignored, the next try is later. */
    fun refreshIfStale(c: Context) {
        if (autoSyncing || !accountStale(c)) return
        autoSyncing = true
        val app = c.applicationContext
        Thread {
            try { syncAccount(app) } catch (e: Exception) { android.util.Log.d("TasteSignals", "ignored: " + e) } finally { autoSyncing = false }
        }.start()
    }

    @Synchronized
    fun clearAccount(c: Context) {
        p(c).edit().remove(K_ACC_LIKES).remove(K_ACC_SUBS).remove(K_ACC_AT).remove(K_ACC_EMAIL).apply()
    }

    // ------------------------------------------------------------------ feeding the profiles

    /**
     * Adds the enabled signals to a profile: [bump] is the `bump(artist, weight)` helper every recommender
     * already has. Account data is ignored when a different Google account is signed in now than the one
     * it was read from.
     */
    fun addTo(c: Context, bump: (String, Int) -> Unit) {
        val prefs = p(c)
        if (externalEnabled(c)) {
            val o = readJson(prefs.getString(K_EXT_ARTISTS, null))
            o.keys().forEach { a -> bump(a, o.optInt(a) * WEIGHT_EXT_PLAY) }
        }
        if (accountEnabled(c) && accountSyncedAt(c) > 0L &&
            prefs.getString(K_ACC_EMAIL, "") == DriveSync.email(c)
        ) {
            val likes = readJson(prefs.getString(K_ACC_LIKES, null))
            likes.keys().forEach { a -> bump(a, minOf(likes.optInt(a), LIKE_CAP) * WEIGHT_LIKE) }
            try {
                val subs = JSONArray(prefs.getString(K_ACC_SUBS, "[]"))
                for (i in 0 until subs.length()) bump(subs.optString(i), WEIGHT_SUB)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** "Artist - Topic" channel names -> plain artist. */
    fun cleanName(s: String): String =
        s.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "").trim()

    private fun pruneInts(o: JSONObject): JSONObject {
        if (o.length() <= MAX_ENTRIES) return o
        val entries = o.keys().asSequence().map { it to o.optInt(it) }.sortedByDescending { it.second }
        val out = JSONObject()
        entries.take(MAX_ENTRIES).forEach { (k, n) -> out.put(k, n) }
        return out
    }
}
