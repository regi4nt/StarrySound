package com.fxsoundlite.app

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler

/**
 * "Sound appeared / stopped" signal for the Spotify remote, independent of notification access.
 *
 * While a Spotify song is the current one, StarrySound itself makes no sound (Spotify's app does), so "some media is
 * playing on the phone" is Spotify. Android tells us at once when a player starts or stops ([AudioManager.AudioPlaybackCallback]),
 * which is faster than the Web API poll and works when Spotify's media session cannot be read.
 * It only says "sound / no sound": title, cover and position still come from the media session or the Web API.
 * The answer is debounced, because the sound briefly drops between two songs.
 */
class SpotifyAudioWatch(
    context: Context,
    private val handler: Handler,
    private val onChange: (active: Boolean) -> Unit
) {
    private val am = context.applicationContext.getSystemService(AudioManager::class.java)
    private var registered = false
    private var last = false

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = schedule()
    }

    private val check = Runnable {
        val on = try { am?.isMusicActive == true } catch (e: Exception) { return@Runnable }
        if (on != last) { last = on; onChange(on) }
    }

    private fun schedule() {
        handler.removeCallbacks(check)
        handler.postDelayed(check, DEBOUNCE_MS)
    }

    val active: Boolean get() = last

    fun start() {
        if (registered || am == null) return
        try {
            am.registerAudioPlaybackCallback(callback, handler)
            registered = true
            last = am.isMusicActive // the starting state is not a change
        } catch (e: Exception) {
            android.util.Log.d("SpotifyAudioWatch", "ignored: $e")
        }
    }

    fun stop() {
        handler.removeCallbacks(check)
        if (!registered) return
        try { am?.unregisterAudioPlaybackCallback(callback) } catch (e: Exception) { android.util.Log.d("SpotifyAudioWatch", "ignored: $e") }
        registered = false
    }

    private companion object { const val DEBOUNCE_MS = 400L }
}
