package com.fxsoundlite.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * "Listening to StarrySound" on the user's Discord profile, like Spotify's activity: song title, artist
 * and a progress bar. Uses the official Discord Social SDK ([DiscordNative]); on Android it needs the
 * Discord app installed and signed in, plus the Application ID of the user's own Discord application
 * (Settings > Online music > Discord).
 *
 * Follows [MusicService] through a global listener: playing a song shows it, pausing / stopping / Private
 * mode clears it. Updates are throttled (Discord allows only a few per 20 s) and a seek or resume refreshes
 * the progress bar.
 */
object DiscordPresence : MusicService.Listener {
    private const val PREFS = "discord_presence"
    /** Key of the large image uploaded under Rich Presence > Art Assets in the Discord Developer Portal. */
    private const val ASSET_KEY = "starrysound"
    private const val APP_NAME = "StarrySound"
    private const val THROTTLE_MS = 2_000L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("enabled", v).apply()

    /** What the user typed ("" = none). */
    fun appId(c: Context): String = prefs(c).getString("app_id", "").orEmpty().trim()
    fun setAppId(c: Context, v: String) = prefs(c).edit().putString("app_id", v.filter { it.isDigit() }).apply()

    /** True when the app was built with the Discord Social SDK. */
    val sdkAvailable: Boolean get() = DiscordNative.available

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var started = false
    private var initedId = 0L
    private var shown = false
    private var lastKey = ""
    private var lastStart = 0L
    private var pending = false

    /** Call once from [App.onCreate]. */
    fun start(c: Context) {
        app = c.applicationContext
        if (started) return
        started = true
        MusicService.addGlobalListener(this)
        schedule(0)
    }

    /** The setting (switch / Application ID) changed: apply it now. */
    fun refresh(c: Context) {
        app = c.applicationContext
        schedule(0)
    }

    override fun onMusicStateChanged() = schedule(THROTTLE_MS)

    private val push = Runnable { pending = false; apply() }

    private fun schedule(delayMs: Long) {
        if (pending) return
        pending = true
        main.postDelayed(push, delayMs)
    }

    private fun apply() {
        val c = app ?: return
        if (!DiscordNative.available) return
        val svc = MusicService.instance
        val t = svc?.current
        val playing = svc != null && t != null && svc.isPlaying && t.title.isNotBlank()
        val id = appId(c).toLongOrNull()
        if (!enabled(c) || MusicPrefs.privateMode(c) || !playing || id == null || id <= 0L) {
            clear()
            return
        }
        if (initedId != id) {
            initedId = if (try { DiscordNative.nativeInit(id) } catch (_: Throwable) { false }) id else 0L
            if (initedId == 0L) return
            shown = false
        }
        val nowSec = System.currentTimeMillis() / 1000
        val start = nowSec - svc!!.position / 1000
        if (shown && t!!.key == lastKey && abs(start - lastStart) <= 3) return // same song, no seek
        val live = t!!.source == Track.SOURCE_RADIO || svc.duration <= 0
        val end = if (live) 0L else start + svc.duration / 1000
        val artist = if (t.source == Track.SOURCE_RADIO && t.artist.isBlank()) c.getString(R.string.source_radio) else t.artist
        try {
            DiscordNative.nativeUpdate(APP_NAME, t.title, artist, start, end, ASSET_KEY, APP_NAME)
            shown = true
            lastKey = t.key
            lastStart = start
        } catch (e: Throwable) {
            android.util.Log.w("DiscordPresence", "update failed", e)
        }
    }

    private fun clear() {
        if (!shown) return
        shown = false
        lastKey = ""
        try { DiscordNative.nativeClear() } catch (_: Throwable) { }
    }
}
