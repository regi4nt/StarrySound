package com.fxsoundlite.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
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

    /**
     * True when the native Discord bridge could be loaded. Loads it on first call, so only call this once the
     * user actually turned the feature on (never from app startup: a broken SDK must not take the app down).
     */
    fun sdkAvailable(): Boolean = DiscordNative.ensureLoaded()

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var started = false
    private var initedId = 0L
    private var shown = false
    private var lastKey = ""
    private var lastStart = 0L
    private var pending = false
    private var lastActivity: WeakReference<Activity>? = null
    private var engineSet = false

    /** Call once from [App.onCreate]. Never touches the native library. */
    fun start(c: Context) {
        app = c.applicationContext
        if (started) return
        started = true
        MusicService.addGlobalListener(this)
        // The Social SDK needs an Activity handed to DiscordSocialSdkInit before the first use.
        (c.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                lastActivity = WeakReference(a)
                if (!engineSet) schedule(0)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
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
        val svc = MusicService.instance
        val t = svc?.current
        val playing = svc != null && t != null && svc.isPlaying && t.title.isNotBlank()
        val id = appId(c).toLongOrNull()
        // Decide first, from plain prefs: when the feature is off we must not load any native code.
        if (!enabled(c) || MusicPrefs.privateMode(c) || !playing || id == null || id <= 0L) {
            clear()
            return
        }
        if (!DiscordNative.ensureLoaded()) return
        if (!ensureEngineActivity()) return // no Activity yet; retried when one resumes
        if (initedId != id) {
            initedId = if (try { DiscordNative.nativeInit(id) } catch (_: Throwable) { false }) id else 0L
            if (initedId == 0L) return
            shown = false
        }
        if (svc == null || t == null) return
        val nowSec = System.currentTimeMillis() / 1000
        val start = nowSec - svc.position / 1000
        if (shown && t.key == lastKey && abs(start - lastStart) <= 3) return // same song, no seek
        val live = t.source == Track.SOURCE_RADIO || svc.duration <= 0
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

    /** Hands the foreground Activity to the SDK's Java half (it derives its Context from it). */
    private fun ensureEngineActivity(): Boolean {
        if (engineSet) return true
        val a = lastActivity?.get() ?: return false
        return try {
            com.discord.socialsdk.DiscordSocialSdkInit.setEngineActivity(a)
            engineSet = true
            true
        } catch (e: Throwable) {
            android.util.Log.w("DiscordPresence", "SDK activity init failed", e)
            false
        }
    }

    private fun clear() {
        if (!shown) return
        shown = false
        lastKey = ""
        if (DiscordNative.available) try { DiscordNative.nativeClear() } catch (e: Throwable) { android.util.Log.d("DiscordPresence", "ignored: " + e) }
    }
}
